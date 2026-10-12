package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What carries work between two tenants: up from the one that asked to the
 * lowest zone both belong to, and down to the one whose data it is.
 *
 * <p><b>A participant like any other, holding claims and no keys.</b> It
 * finds a run a tenant asked for on the exchange its zone declares, and
 * claims it on that tenant's own lane, so the asker's trail records the
 * hand-over exactly as it records any participant taking work. Every zone on
 * the way records that the crossing passed, and nothing of what it was. At
 * the owner it authors the run that does the work, over the same inputs,
 * keyed by the crossing so that carrying it again finds the run already
 * there rather than authoring a second.
 *
 * <p>In the platform plane, because only the plane holds every tenant of the
 * deployment: nothing here is a query across tenants, and no tenant's
 * credential is held by anything but that tenant.
 */
final class Crossings {

    /** Who carries: one participant for the deployment, whatever the zones. */
    static final Executor CARRIER =
            new Executor("dbo-crossings", "1", "cloud.jengu.dbo", Scope.BASELINE);

    /** How long the carrier holds what it took before another pass may take it again. */
    static final Duration HOLD = Duration.ofMinutes(10);

    /** What the carrier reaches of the deployment, and nothing more. */
    interface Plane {

        /** The tenants serving now. */
        Set<String> serving();

        /** The jurisdictions as the deployment last read them. */
        ZoneTree zones();

        /** A lane into a tenant for one step, as the carrier. */
        Optional<Lane> lane(String tenant, String step);

        /** A tenant's store, for authoring the run that does the work and for a zone's trail. */
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> store(String tenant);

        /**
         * The key a participant of a tenant is sealed to, as that tenant's own
         * authority holds it: the public half it offered at enrolment.
         */
        Optional<cloud.jengu.dbo.core.api.seal.ParticipantKey> sealedTo(String tenant,
                String participant);
    }

    private final Plane plane;
    /** The tenant and step pairs this carrier has said it takes, so it says each once. */
    private final Set<String> declared = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Crossings(Plane plane) {
        this.plane = plane;
    }

    /**
     * One pass over every tenant: what has been asked for and not yet
     * carried, carried; what has been answered and not yet carried back,
     * carried back.
     */
    int crossOnce() {
        int carried = 0;
        ZoneTree zones = plane.zones();
        for (String owner : plane.serving()) {
            for (TenantSpec.Step step : zones.exchangeSteps(owner)) {
                carried += carryBack(zones, owner, step);
            }
        }
        for (String asker : plane.serving()) {
            for (TenantSpec.Step step : zones.exchangeSteps(asker)) {
                Optional<Lane> lane = plane.lane(asker, step.code());
                if (lane.isEmpty()) {
                    continue;
                }
                int dot = step.code().lastIndexOf('.');
                String process = step.code().substring(0, dot);
                String bare = step.code().substring(dot + 1);
                // Announced as any participant announces itself, because a
                // run is taken only by an executor that said it performs the
                // step: the carrier performs this one by carrying it.
                if (declared.add(asker + " " + step.code())) {
                    lane.get().declare(new cloud.jengu.dbo.work.Declarations.Declared(process,
                            bare, CARRIER.name(), CARRIER.version(), CARRIER.provider(),
                            CARRIER.scope(), CARRIER.name()));
                }
                for (Run asked : lane.get().poll(Set.of(bare), 32)) {
                    // Asked for here, as opposed to arrived here: a run whose
                    // crossing already names the run it answers is the
                    // owner's to perform, not this carrier's to take.
                    if (asked.crossing() != null && asked.crossing().askerRun() == null
                            && asked.crossing().arrived() == null
                            && carry(zones, asker, step, lane.get(), asked)) {
                        carried++;
                    }
                }
            }
        }
        return carried;
    }

    /**
     * The owner's answers, back to the runs that asked.
     *
     * <p>What the answer names is sealed to the reader the asker named and to
     * nobody else, under the key the asker's own authority holds for it — so
     * nothing this carrier holds opens it, and nothing on the way back reads
     * it. Every zone records that the crossing passed again, the other way.
     */
    private int carryBack(ZoneTree zones, String owner, TenantSpec.Step step) {
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> ownerStore = plane.store(owner);
        if (ownerStore.isEmpty()) {
            return 0;
        }
        int dot = step.code().lastIndexOf('.');
        int carried = 0;
        for (Run answered : new Runs(ownerStore.get()).matching(step.code().substring(0, dot),
                step.code().substring(dot + 1), null, 50)) {
            Run.Crossing crossing = answered.crossing();
            if (crossing == null || crossing.askerRun() == null
                    || answered.status() != cloud.jengu.dbo.work.Status.COMPLETED) {
                continue;
            }
            Optional<cloud.jengu.dbo.policy.PolicyObjectStore> askerStore =
                    plane.store(crossing.from());
            if (askerStore.isEmpty()) {
                continue;
            }
            Runs askerRuns = new Runs(askerStore.get());
            Optional<Run> asking = askerRuns.byKey(crossing.askerRun());
            if (asking.isEmpty() || !asking.get().open()
                    || asking.get().crossing().arrived() != null
                    || asking.get().assignment() == null
                    || asking.get().assignment().executor() == null
                    || !CARRIER.name().equals(asking.get().assignment().executor().name())) {
                continue;
            }
            Optional<cloud.jengu.dbo.core.api.seal.ParticipantKey> key =
                    plane.sealedTo(crossing.from(), crossing.reader());
            if (key.isEmpty()) {
                // Nothing to seal to is nothing to carry: the answer stays the
                // owner's, and the asker reads why.
                askerRuns.released(asking.get(), CARRIER, "'" + crossing.reader()
                        + "' offered no key at enrolment, so nothing can be sealed to it",
                        cloud.jengu.dbo.work.Failure.RECORD);
                continue;
            }
            List<cloud.jengu.dbo.work.SealedPayload> sealed = sealAnswer(ownerStore.get(),
                    answered, crossing.reader(), key.get());
            List<String> back = new ArrayList<>(zones.route(crossing.from(), owner).zones());
            java.util.Collections.reverse(back);
            List<String> path = new ArrayList<>();
            path.add(owner);
            path.addAll(back);
            path.add(crossing.from());
            for (int at = 1; at < path.size() - 1; at++) {
                Optional<cloud.jengu.dbo.policy.PolicyObjectStore> zone =
                        plane.store(path.get(at));
                if (zone.isEmpty()) {
                    return carried;
                }
                ZoneTrail.passed(zone.get(), crossing.id(), path.get(at - 1), path.get(at + 1));
            }
            askerRuns.answered(asking.get(), CARRIER, cloud.jengu.dbo.core.wire.RecordWire
                    .write(cloud.jengu.dbo.core.wire.RecordWire.encode(
                            new cloud.jengu.dbo.work.SealedWork(null, sealed))));
            carried++;
        }
        return carried;
    }

    /**
     * Each record the owner's answer names, sealed to the reader. Read as the
     * machinery reads what it seals, which is no disclosure: the opening is
     * recorded where the key is used.
     */
    private static List<cloud.jengu.dbo.work.SealedPayload> sealAnswer(
            cloud.jengu.dbo.policy.PolicyObjectStore owner, Run answered, String reader,
            cloud.jengu.dbo.core.api.seal.ParticipantKey key) {
        List<cloud.jengu.dbo.work.SealedPayload> sealed = new ArrayList<>();
        cloud.jengu.dbo.core.api.Disclosure.toSeal();
        try {
            for (String version : answered.produced().versions()) {
                java.util.regex.Matcher list = LIST.matcher(version);
                if (!list.find()) {
                    continue;
                }
                Optional<cloud.jengu.dbo.core.api.StoredObject> answer =
                        owner.get("List", list.group(1));
                if (answer.isEmpty()) {
                    continue;
                }
                java.util.regex.Matcher named = REFERENCE.matcher(new String(
                        answer.get().payload(), java.nio.charset.StandardCharsets.UTF_8));
                while (named.find()) {
                    String type = named.group(1);
                    String id = named.group(2);
                    owner.get(type, id).ifPresent(document -> sealed.add(
                            cloud.jengu.dbo.work.SealedPayload.seal("shared", type + "/" + id,
                                    document, java.util.Map.of(reader, key))));
                }
            }
        } finally {
            cloud.jengu.dbo.core.api.Disclosure.clear();
        }
        return sealed;
    }

    private static final java.util.regex.Pattern LIST =
            java.util.regex.Pattern.compile("List/([0-9a-f-]{36})");
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile(
            "\"reference\"\\s*:\\s*\"([A-Z][A-Za-z]+)/([0-9a-f-]{36})\"");

    private boolean carry(ZoneTree zones, String asker, TenantSpec.Step step, Lane lane,
            Run asked) {
        Optional<Run> claimed = lane.claim(asked, HOLD);
        if (claimed.isEmpty()) {
            return false;
        }
        Run held = claimed.get();
        Run.Crossing crossing = held.crossing();
        String owner = crossing.to();
        ZoneTree.Route route;
        try {
            route = zones.route(asker, owner);
        } catch (IllegalArgumentException noWay) {
            lane.released(held, noWay.getMessage(), cloud.jengu.dbo.work.Failure.UNREACHABLE);
            return false;
        }
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> ownerStore = plane.store(owner);
        if (ownerStore.isEmpty() || !plane.serving().contains(owner)) {
            lane.released(held, owner + " is not serving, so nothing can perform the work "
                    + "there yet", cloud.jengu.dbo.work.Failure.UNREACHABLE);
            return false;
        }
        // EVERY ZONE ON THE WAY, in the order the work passes them: each
        // records where it came from and where it went.
        List<String> path = new ArrayList<>();
        path.add(asker);
        path.addAll(route.zones());
        path.add(owner);
        for (int at = 1; at < path.size() - 1; at++) {
            String zone = path.get(at);
            Optional<cloud.jengu.dbo.policy.PolicyObjectStore> zoneStore = plane.store(zone);
            if (zoneStore.isEmpty()) {
                lane.released(held, "the zone " + zone + " on the way is not serving",
                        cloud.jengu.dbo.work.Failure.UNREACHABLE);
                return false;
            }
            ZoneTrail.passed(zoneStore.get(), crossing.id(), path.get(at - 1),
                    path.get(at + 1));
        }
        // THE OWNER'S RUN, over the same inputs and naming the run it answers.
        new Runs(ownerStore.get()).filling(
                StepSurface.declaration(step.code(), step.slots()), RunKind.PIPELINE,
                "crossing-" + crossing.id(), held.inputs(), null, null, null, null,
                new Run.Crossing(crossing.id(), asker, owner, held.key(), crossing.reader()));
        return true;
    }
}
