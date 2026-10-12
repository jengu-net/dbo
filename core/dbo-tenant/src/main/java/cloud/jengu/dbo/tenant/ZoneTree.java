package cloud.jengu.dbo.tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The jurisdictions a deployment's tenants belong to, as a tree, and the
 * route work takes between two of them.
 *
 * <p>Every tenant belongs to one zone, and a zone to at most one zone above
 * it: Estonia to the EU, Germany to the EU. Two regular tenants never deal
 * with each other directly. What crosses between them is carried up to the
 * lowest zone both belong to and down again, and that zone's rules govern
 * it — the zones on the way may be stricter on their own leg and never
 * looser, because a jurisdiction is bound by the one it belongs to.
 *
 * <p>Read from declarations alone, so that what it refuses can be asked
 * without a deployment to ask it of, and refused when a file is read rather
 * than when work first tries to cross.
 */
final class ZoneTree {

    private final Map<String, TenantSpec> declared;

    private ZoneTree(Map<String, TenantSpec> declared) {
        this.declared = declared;
    }

    static ZoneTree of(Collection<TenantSpec> declarations) {
        return new ZoneTree(declarations.stream().collect(Collectors.toMap(
                TenantSpec::code, Function.identity(), (first, second) -> first)));
    }

    /**
     * The zones above a tenant, nearest first, up to a root or to a zone no
     * file declares yet. A zone is not above itself.
     */
    List<String> chainOf(String tenant) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(tenant);
        TenantSpec at = declared.get(tenant);
        while (at != null && at.zone() != null && seen.add(at.zone())) {
            chain.add(at.zone());
            at = declared.get(at.zone());
        }
        return chain;
    }

    /**
     * A step a zone above this tenant declares within a process it permits:
     * where its members ask for an exchange, nearest zone first.
     */
    java.util.Optional<TenantSpec.Step> exchangeStep(String tenant, String stepCode) {
        for (String zone : chainOf(tenant)) {
            TenantSpec spec = declared.get(zone);
            if (spec == null) {
                continue;
            }
            for (TenantSpec.Step step : spec.steps()) {
                if (step.code().equals(stepCode)
                        && spec.exchanges().contains(processOf(stepCode))) {
                    return java.util.Optional.of(step);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** Every exchange step the zones above this tenant offer it, nearest zone first. */
    List<TenantSpec.Step> exchangeSteps(String tenant) {
        List<TenantSpec.Step> offered = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String zone : chainOf(tenant)) {
            TenantSpec spec = declared.get(zone);
            if (spec == null) {
                continue;
            }
            for (TenantSpec.Step step : spec.steps()) {
                if (spec.exchanges().contains(processOf(step.code())) && seen.add(step.code())) {
                    offered.add(step);
                }
            }
        }
        return offered;
    }

    /** The process a step code is part of: its two leading parts. */
    static String processOf(String stepCode) {
        int last = stepCode.lastIndexOf('.');
        return last < 0 ? stepCode : stepCode.substring(0, last);
    }

    /** What a zone permits its members to do for each other; nothing, if it says nothing. */
    Set<String> permitted(String zone) {
        TenantSpec spec = declared.get(zone);
        return spec == null ? Set.of() : spec.exchanges();
    }

    /**
     * Refuses a declaration the tree cannot hold, naming what is wrong and
     * which file says so.
     *
     * <p>A zone this tenant names that nothing declares yet is not refused:
     * it is a zone not up yet, which the tenant waits for at bring-up.
     */
    void refuse(TenantSpec spec) {
        if (spec.zone() == null) {
            return;
        }
        for (String zone : chainOf(spec.code())) {
            Set<String> obliged = new TreeSet<>(spec.declines());
            obliged.retainAll(permitted(zone));
            if (!obliged.isEmpty()) {
                throw new IllegalArgumentException(spec.code() + ": declines " + obliged
                        + ", and its zone " + zone + " declares "
                        + (obliged.size() == 1 ? "it" : "them") + ". A jurisdiction's process "
                        + "binds its members, and is not a member's to decline");
            }
        }
        TenantSpec parent = declared.get(spec.zone());
        if (parent != null) {
            TenantRuntimeManager.aZoneIsDeclaredByItself(spec, parent);
        }
        List<String> around = new ArrayList<>(List.of(spec.code()));
        for (String zone : chainOf(spec.code())) {
            around.add(zone);
            TenantSpec above = declared.get(zone);
            if (above != null && spec.code().equals(above.zone())) {
                around.add(spec.code());
                throw new IllegalArgumentException(spec.code() + ": its zones lead back to "
                        + "itself — " + String.join(" → ", around) + ". Zones form a tree: a "
                        + "jurisdiction above its own is bound by nothing, so one of these "
                        + "files names the wrong zone");
            }
        }
        if (spec.zoneRoot() && parent != null) {
            Set<String> beyond = new TreeSet<>(spec.exchanges());
            beyond.removeAll(parent.exchanges());
            if (!beyond.isEmpty()) {
                throw new IllegalArgumentException(spec.code() + ": permits exchanges "
                        + beyond + " that its zone " + parent.code() + " does not. A zone "
                        + "may only narrow what the jurisdiction it belongs to permits");
            }
        }
    }

    /**
     * The way work takes from the tenant asking for it to the tenant whose
     * data it is.
     *
     * @throws IllegalArgumentException naming why there is none
     */
    Route route(String asker, String owner) {
        if (asker.equals(owner)) {
            throw new IllegalArgumentException(asker + ": a tenant's own work does not cross");
        }
        List<String> fromAsker = pathOf(asker);
        List<String> fromOwner = pathOf(owner);
        for (int up = 0; up < fromAsker.size(); up++) {
            int down = fromOwner.indexOf(fromAsker.get(up));
            if (down >= 0) {
                List<String> ascending = without(fromAsker.subList(0, up), asker);
                List<String> descending = new ArrayList<>(
                        without(fromOwner.subList(0, down), owner));
                java.util.Collections.reverse(descending);
                return new Route(fromAsker.get(up), ascending, descending, this);
            }
        }
        throw new IllegalArgumentException(asker + " and " + owner + " share no zone — "
                + describe(asker, fromAsker) + ", " + describe(owner, fromOwner)
                + " — so nothing can carry work between them");
    }

    /** A tenant and the zones above it, itself first when it is a zone. */
    private List<String> pathOf(String tenant) {
        TenantSpec spec = declared.get(tenant);
        if (spec == null) {
            throw new IllegalArgumentException(tenant + " is not declared here");
        }
        List<String> path = new ArrayList<>();
        if (spec.zoneRoot()) {
            path.add(tenant);
        }
        path.addAll(chainOf(tenant));
        if (path.isEmpty()) {
            throw new IllegalArgumentException(tenant + " belongs to no zone, and work crosses "
                    + "between tenants only through the jurisdiction they share");
        }
        return path;
    }

    private static List<String> without(List<String> zones, String tenant) {
        List<String> kept = new ArrayList<>(zones);
        kept.remove(tenant);
        return kept;
    }

    private static String describe(String tenant, List<String> path) {
        return tenant + " is under " + path.get(path.size() - 1);
    }

    /**
     * Up from the asker to the lowest zone both share, then down to the owner.
     *
     * @param lowest the zone whose rules govern the exchange
     * @param up     the zones between the asker and it, nearest the asker first
     * @param down   the zones between it and the owner, nearest it first
     */
    record Route(String lowest, List<String> up, List<String> down, ZoneTree tree) {

        Route {
            up = List.copyOf(up);
            down = List.copyOf(down);
        }

        /** Every zone the work passes, in the order it passes them. */
        List<String> zones() {
            List<String> all = new ArrayList<>(up);
            all.add(lowest);
            all.addAll(down);
            return all;
        }

        /** Whether every zone on the way permits that process. */
        boolean permits(String process) {
            return zones().stream().allMatch(zone -> tree.permitted(zone).contains(process));
        }
    }
}
