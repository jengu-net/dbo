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

        /** A lane into a tenant for the steps named, as the carrier. */
        Optional<Lane> lane(String tenant, Set<String> steps);

        /** A tenant's store, for authoring the run that does the work and for a zone's trail. */
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> store(String tenant);

        /**
         * The key a participant of a tenant is sealed to, as that tenant's own
         * authority holds it: the public half it offered at enrolment.
         */
        Optional<cloud.jengu.dbo.core.api.seal.ParticipantKey> sealedTo(String tenant,
                String participant);

        /** Holds what came back on a crossing beside the run that asked, in that tenant. */
        void hold(String tenant, String crossing, String answer);

        /** Removes what came back on a crossing; whether anything was held. */
        boolean drop(String tenant, String crossing);
    }

    /** What a run that asked across says once its answer is held beside it. */
    static final String HELD = "held";

    /** How many waiting crossings of one step are read at a time. */
    static final int PAGE = 32;

    private final Plane plane;
    /** The tenant and step pairs this carrier has said it takes, so it says each once. */
    private final Set<String> declared = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The crossings a pass found and could not carry, by the asker's run, with
     * when that was first so: one still waiting on a later pass says so.
     */
    private final java.util.Map<String, java.time.Instant> uncarried =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                carried += reportOpenings(zones, asker, step);
                carried += letGo(asker, step);
            }
        }
        for (String asker : plane.serving()) {
            carried += carryAsked(zones, asker);
        }
        return carried;
    }

    /**
     * Every crossing a tenant asked that waits to be carried, carried.
     *
     * <p>FOUND IN THE ASKER'S STORE, never after a position in its work. A
     * participant's poll moves past what it read whether or not it acted on
     * it, so a crossing the pass did not carry — one whose carrying threw,
     * or that was read behind one that did — was never offered again, and
     * the asker's run stayed ready with nothing to say why. What waits is
     * asked for afresh on every pass instead; the lane is used only to take
     * it, so the asker's trail records the hand-over as it records any.
     */
    private int carryAsked(ZoneTree zones, String asker) {
        List<TenantSpec.Step> asking = zones.exchangeSteps(asker);
        if (asking.isEmpty()) {
            return 0;
        }
        Set<String> codes = new java.util.LinkedHashSet<>();
        asking.forEach(step -> codes.add(step.code()));
        Optional<Lane> lane = plane.lane(asker, codes);
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> askerStore = plane.store(asker);
        if (lane.isEmpty() || askerStore.isEmpty()) {
            return 0;
        }
        Runs askerRuns = new Runs(askerStore.get());
        int carried = 0;
        for (TenantSpec.Step step : asking) {
            int dot = step.code().lastIndexOf('.');
            String process = step.code().substring(0, dot);
            String bare = step.code().substring(dot + 1);
            // Announced as any participant announces itself, because a run is
            // taken only by an executor that said it performs the step: the
            // carrier performs this one by carrying it.
            if (declared.add(asker + " " + step.code())) {
                lane.get().declare(new cloud.jengu.dbo.work.Declarations.Declared(process, bare,
                        CARRIER.name(), CARRIER.version(), CARRIER.provider(), CARRIER.scope(),
                        CARRIER.name()));
            }
            // A page at a time, oldest first, for as long as a page is full
            // and moved: what was carried is no longer waiting, so the next
            // read starts past it, and a page of what cannot be carried ends
            // the pass rather than being read again.
            boolean more = true;
            while (more) {
                List<Run> waiting = askerRuns.askedAcross(process, bare, PAGE);
                int moved = 0;
                for (Run asked : waiting) {
                    if (carriedOrSaid(zones, asker, step, lane.get(), askerRuns, asked)) {
                        moved++;
                    }
                }
                carried += moved;
                more = waiting.size() == PAGE && moved > 0;
            }
        }
        return carried;
    }

    /**
     * One crossing carried — or, where it cannot be, the asker's run saying
     * why once it has waited a pass for it. A crossing whose carrying throws
     * is handed back with what was thrown, and stops nothing else this pass.
     */
    private boolean carriedOrSaid(ZoneTree zones, String asker, TenantSpec.Step step, Lane lane,
            Runs askerRuns, Run asked) {
        // Asked for here, as opposed to arrived here: a run whose crossing
        // already names the run it answers is the owner's to perform, not
        // this carrier's to take.
        if (asked.crossing() == null || asked.crossing().askerRun() != null
                || asked.crossing().arrived() != null) {
            return false;
        }
        String why;
        try {
            if (carry(zones, asker, step, lane, asked)) {
                uncarried.remove(asked.key());
                return true;
            }
            why = "the carrier could not take it";
        } catch (RuntimeException failed) {
            why = "carrying it failed: " + failed.getMessage();
        }
        java.time.Instant since = uncarried.putIfAbsent(asked.key(), java.time.Instant.now());
        if (since != null && askerRuns.waiting(asked, "not carried since " + since + ": "
                + why).isEmpty()) {
            // Taken meanwhile, by another carrier or a later pass of this one.
            uncarried.remove(asked.key());
        }
        return false;
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
            if (crossing == null || crossing.askerRun() == null || answered.open()) {
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
            // NOT ANSWERED, or nothing to bring back but how it ended: the
            // asker's run ends as the owner's did, and why, so an asker is
            // never left open because the owner refused.
            if (answered.status() != cloud.jengu.dbo.work.Status.COMPLETED
                    || step.awaits() != TenantSpec.Step.Awaits.RECORDS) {
                passedBack(zones, owner, crossing);
                String because = answered.refused() != null ? answered.refused()
                        : answered.statusReason() != null ? answered.statusReason()
                        : owner + " " + answered.status().wire() + " " + answered.key();
                askerRuns.endedAcross(asking.get(), CARRIER, answered.status(), because);
                carried++;
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
            if (!passedBack(zones, owner, crossing)) {
                return carried;
            }
            plane.hold(crossing.from(), crossing.id(), cloud.jengu.dbo.core.wire.RecordWire
                    .write(cloud.jengu.dbo.core.wire.RecordWire.encode(
                            new cloud.jengu.dbo.work.SealedWork(null, sealed))));
            askerRuns.answered(asking.get(), CARRIER, HELD);
            carried++;
        }
        return carried;
    }

    /**
     * Every zone the way back passes records that the crossing passed again;
     * false where one of them is not serving, and nothing more is carried.
     */
    private boolean passedBack(ZoneTree zones, String owner, Run.Crossing crossing) {
        List<String> back = new ArrayList<>(zones.route(crossing.from(), owner).zones());
        java.util.Collections.reverse(back);
        List<String> path = new ArrayList<>();
        path.add(owner);
        path.addAll(back);
        path.add(crossing.from());
        for (int at = 1; at < path.size() - 1; at++) {
            Optional<cloud.jengu.dbo.policy.PolicyObjectStore> zone = plane.store(path.get(at));
            if (zone.isEmpty()) {
                return false;
            }
            ZoneTrail.passed(zone.get(), crossing.id(), path.get(at - 1), path.get(at + 1));
        }
        return true;
    }

    /**
     * What the asker's reader opened, reported to every zone on the way and
     * to the owner.
     *
     * <p>Read from the asker's own trail, where the opening was recorded
     * before the reader read anything, and reported from there on every pass
     * until each side holds it — once, because each report is keyed by the
     * opening as the asker recorded it. The owner records the access on its
     * own document, under its own run, naming the reader and the run it was
     * opened for, with the reader's signed link as evidence; it is a report
     * of what happened elsewhere, so it is on the owner's trail and not a
     * link of its run's chain.
     */
    private int reportOpenings(ZoneTree zones, String asker, TenantSpec.Step step) {
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> askerStore = plane.store(asker);
        if (askerStore.isEmpty()) {
            return 0;
        }
        int dot = step.code().lastIndexOf('.');
        int reported = 0;
        for (Run asking : new Runs(askerStore.get()).matching(step.code().substring(0, dot),
                step.code().substring(dot + 1), null, 50)) {
            Run.Crossing crossing = asking.crossing();
            if (crossing == null || crossing.askerRun() != null || crossing.arrived() == null) {
                continue;
            }
            Optional<cloud.jengu.dbo.policy.PolicyObjectStore> ownerStore =
                    plane.store(crossing.to());
            if (ownerStore.isEmpty()) {
                continue;
            }
            List<String> path;
            try {
                path = zones.route(asker, crossing.to()).zones();
            } catch (IllegalArgumentException noLongerAWay) {
                continue;
            }
            String ownerRun = step.code() + "/crossing-" + crossing.id();
            for (cloud.jengu.dbo.core.api.StoredObject entry : askerStore.get().select(
                    cloud.jengu.dbo.core.api.Criteria.of("AuditEntry").eq("run",
                            cloud.jengu.dbo.core.api.EnvelopeValue.of(asking.key())))) {
                Object node = cloud.jengu.dbo.core.wire.RecordWire.read(new String(
                        entry.payload(), java.nio.charset.StandardCharsets.UTF_8));
                if (!(node instanceof java.util.Map<?, ?> said)
                        || !"access".equals(said.get("code"))
                        || !(said.get("detail") instanceof java.util.Map<?, ?> detail)
                        || !(detail.get("link") instanceof String link)
                        || !crossing.reader().equals(detail.get("by"))) {
                    continue;
                }
                for (String zone : path) {
                    Optional<cloud.jengu.dbo.policy.PolicyObjectStore> zoneStore =
                            plane.store(zone);
                    if (zoneStore.isPresent()) {
                        ZoneTrail.opened(zoneStore.get(), crossing.id(), crossing.reader(),
                                asker, link);
                    }
                }
                java.util.Map<String, String> owned = new java.util.LinkedHashMap<>();
                owned.put("by", crossing.reader());
                owned.put("in", asker);
                owned.put("run", ownerRun);
                owned.put("askerRun", asking.key());
                owned.put("crossing", crossing.id());
                owned.put("evidence", link);
                if (detail.get("signature") instanceof String signature) {
                    owned.put("signature", signature);
                }
                try (cloud.jengu.dbo.core.api.Caller.InRun named =
                        cloud.jengu.dbo.core.api.Caller.writingFor(ownerRun)) {
                    if (ownerStore.get().recordForwarded("access",
                            String.valueOf(said.get("targetType")),
                            String.valueOf(said.get("targetId")), owned, null,
                            "crossing:" + crossing.id() + ":opened:" + link).created()) {
                        reported++;
                    }
                }
            }
        }
        return reported;
    }

    /**
     * What came back, let go once the work it was asked for is over.
     *
     * <p>The answer was asked for a purpose, and the run that asked is that
     * purpose: once it is over the answer goes, at once unless the
     * jurisdiction declared — as its exchange's answer window — that it may be
     * kept, and then once that has passed. The owner is told, once, so its
     * account of what it shared says when the copy it handed over ended.
     */
    private int letGo(String asker, TenantSpec.Step step) {
        Optional<cloud.jengu.dbo.policy.PolicyObjectStore> askerStore = plane.store(asker);
        if (askerStore.isEmpty()) {
            return 0;
        }
        java.time.Duration kept = step.answer() == null ? null : step.answer().collect();
        int dot = step.code().lastIndexOf('.');
        int let = 0;
        for (Run asking : new Runs(askerStore.get()).matching(step.code().substring(0, dot),
                step.code().substring(dot + 1), null, 50)) {
            Run.Crossing crossing = asking.crossing();
            if (crossing == null || crossing.askerRun() != null
                    || !HELD.equals(crossing.arrived()) || asking.open()) {
                continue;
            }
            java.time.Instant ended = askerStore.get().get(cloud.jengu.dbo.work.WorkModel.TYPE,
                    asking.id()).map(cloud.jengu.dbo.core.api.StoredObject::lastUpdated)
                    .orElse(java.time.Instant.now());
            if (!HeldAnswers.due(ended, kept, java.time.Instant.now())
                    || !plane.drop(asker, crossing.id())) {
                continue;
            }
            let++;
            Optional<cloud.jengu.dbo.policy.PolicyObjectStore> ownerStore =
                    plane.store(crossing.to());
            if (ownerStore.isEmpty()) {
                continue;
            }
            String ownerRun = step.code() + "/crossing-" + crossing.id();
            java.util.Map<String, String> told = new java.util.LinkedHashMap<>();
            told.put("in", asker);
            told.put("askerRun", asking.key());
            told.put("crossing", crossing.id());
            try (cloud.jengu.dbo.core.api.Caller.InRun named =
                    cloud.jengu.dbo.core.api.Caller.writingFor(ownerRun)) {
                ownerStore.get().recordForwarded("erased", "Crossing", crossing.id(), told,
                        null, "crossing:" + crossing.id() + ":erased");
            }
        }
        return let;
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
        try {
            return carry(zones, asker, step, lane, held, held.crossing());
        } catch (RuntimeException failed) {
            // Handed back rather than held to its deadline: the next pass
            // tries again, and the asker reads what went wrong meanwhile.
            lane.released(held, "carrying it failed: " + failed.getMessage(), null);
            throw failed;
        }
    }

    private boolean carry(ZoneTree zones, String asker, TenantSpec.Step step, Lane lane,
            Run held, Run.Crossing crossing) {
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
        // Asked for nothing back: the work is the owner's once it is there.
        if (step.awaits() == TenantSpec.Step.Awaits.NONE) {
            plane.store(asker).ifPresent(askerStore -> new Runs(askerStore).endedAcross(held,
                    CARRIER, cloud.jengu.dbo.work.Status.COMPLETED, "authored at " + owner));
        }
        return true;
    }
}
