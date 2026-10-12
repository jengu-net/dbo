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
    }

    private final Plane plane;
    /** The tenant and step pairs this carrier has said it takes, so it says each once. */
    private final Set<String> declared = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Crossings(Plane plane) {
        this.plane = plane;
    }

    /** One pass over every tenant: what has been asked for and not yet carried, carried. */
    int crossOnce() {
        int carried = 0;
        ZoneTree zones = plane.zones();
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
                            && carry(zones, asker, step, lane.get(), asked)) {
                        carried++;
                    }
                }
            }
        }
        return carried;
    }

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
