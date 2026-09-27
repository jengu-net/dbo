package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The register is what the code said; the trail is what it did.
 *
 * <p>A disagreement between them is an <b>incident</b>, and it has to be,
 * because it cannot be a refusal: an enrolled processor holds the key to what
 * was sealed to it, and no cryptography stops a party that can decrypt from
 * decrypting. What the store can do is notice, in the tenant's own account,
 * and say exactly which slot of which step opened what.
 *
 * <p><b>Computed from the tenant's own records, never stored.</b> The evidence
 * is already there — the access entry the store writes on every document a
 * participant opens, naming the run as its occasion — so the incident is a
 * reading of the trail rather than a second record beside it. A stored incident
 * would be a second place to ask, and the first time it disagreed with the
 * trail the tenant would have no way to know which was true.
 *
 * <p><b>Read in memory today.</b> It selects the trail and filters here, which
 * is honest for a comparison run on demand and wrong for one run continuously
 * over a large tenant. What it would need is the trail queried by code and
 * occasion, which the store can express; doing that before anything asks for
 * the comparison often would be tuning a path nobody walks.
 */
public final class RegisterVersusTrail {

    /**
     * One opening the register did not declare.
     *
     * @param step     the step whose run the opening happened under
     * @param slot     which of the run's slots held the document
     * @param type     what was opened
     * @param id       which one
     * @param by       who opened it, as the store recorded them
     * @param runKey   the occasion, so a reader can see the rest of it
     */
    public record Incident(String step, String slot, String type, String id, String by,
            String runKey) {

        /** What a tenant is told, in one line it can act on. */
        public String says() {
            return "'" + by + "' opened " + type + "/" + id + " in slot '" + slot
                    + "' of step '" + step + "', and the register does not say that step opens "
                    + "that slot. Run " + runKey + ".";
        }
    }

    private RegisterVersusTrail() {
    }

    /**
     * Every opening under a fleet step's run that the register did not declare.
     *
     * <p>Openings under a tenant's OWN steps are not compared, and that is not
     * an omission: the register describes what the deployment does, a tenant's
     * own participants are the tenant's own business, and comparing them here
     * would have the deployment auditing its tenants rather than the reverse.
     */
    public static List<Incident> of(List<FleetRegister.Row> register, Set<String> fleetCodes,
            ObjectStore engine) {
        Set<String> declared = new LinkedHashSet<>();
        register.forEach(row -> declared.add(row.step() + "\u0000" + row.slot()));
        Runs runs = new Runs(engine);
        List<Incident> incidents = new ArrayList<>();
        for (var stored : engine.select(Criteria.of("AuditEntry"))) {
            String entry = new String(stored.payload(), StandardCharsets.UTF_8);
            if (!entry.contains("\"code\":\"access\"")) {
                continue;
            }
            String runKey = field(entry, "run");
            String type = field(entry, "targetType");
            String id = field(entry, "targetId");
            String by = field(entry, "by");
            if (runKey == null || type == null || id == null) {
                continue;
            }
            Optional<Run> run = runs.byKey(runKey);
            if (run.isEmpty()) {
                continue;
            }
            String step = run.get().process() + "." + run.get().step();
            if (!fleetCodes.contains(step)) {
                continue;
            }
            String slot = slotHolding(run.get(), type, id);
            if (slot == null || declared.contains(step + "\u0000" + slot)) {
                continue;
            }
            incidents.add(new Incident(step, slot, type, id, by, runKey));
        }
        return List.copyOf(incidents);
    }

    /** Which slot of this run the document fills, if any. */
    private static String slotHolding(Run run, String type, String id) {
        String reference = type + "/" + id;
        for (var slot : run.inputs().entrySet()) {
            if (reference.equals(slot.getValue())) {
                return slot.getKey();
            }
        }
        return null;
    }

    /** A flat string member of an audit entry. Enough for this, and no parser to own. */
    private static String field(String json, String name) {
        String needle = "\"" + name + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int from = at + needle.length();
        int end = json.indexOf('"', from);
        return end < 0 ? null : json.substring(from, end);
    }
}
