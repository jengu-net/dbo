package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Work performed under a row the tenant has not authorised.
 *
 * <p><b>The default runs.</b> A halting default would turn an unanswered
 * register into an outage caused by nobody clicking, so a row that says nothing
 * is processed and NAMED — and that cost is accepted rather than argued away,
 * which is exactly why the incident has to carry the weight.
 *
 * <p>So it is not a notice that scrolls past. It names the tenant, the step and
 * the row; it stands until the row is authorised rather than clearing itself;
 * and it says <b>how long</b>, because an incident that reads the same on day
 * one and day ninety is one nobody acts on, and a default that nobody acts on
 * is not honest.
 *
 * <p><b>Since-when comes from the tenant's own records</b>, not from a clock
 * this class keeps. The earliest run of that step is when work first went
 * through a row nobody had approved — derivable, durable across restarts, and
 * the same answer for anyone who asks. A remembered timestamp would reset every
 * time the deployment did.
 */
public final class UnapprovedProcessing {

    /** One row that is being acted on without authorisation, and since when. */
    public record Incident(String tenant, String step, String slot, String type,
            Optional<Instant> since) {

        /** What a tenant is told, in one line it can act on. */
        public String says() {
            return "'" + step + "' has been opening " + type + " in slot '" + slot
                    + "' of " + tenant + "'s work" + since
                    .map(when -> " since " + when + " (" + days() + " days)")
                    .orElse(" and has not run yet")
                    + ", and " + tenant + " has not authorised that row.";
        }

        /** How long it has stood, which is what decides whether anybody acts. */
        public long days() {
            return since.map(when -> Duration.between(when, Instant.now()).toDays()).orElse(0L);
        }
    }

    private UnapprovedProcessing() {
    }

    /**
     * Every unapproved row this tenant's work is being put through.
     *
     * <p>Only the rows whose posture is <i>processed and named</i>. A row that
     * says <i>not until approved</i> had its work withheld, so nothing happened
     * under it — a refusal rather than an incident. A row that says
     * <i>applied</i> runs under the agreement the tenant signed by joining, so
     * there is nothing unauthorised about it at all.
     */
    public static List<Incident> of(String tenant, List<FleetRegister.Row> unapproved,
            ObjectStore engine) {
        List<Incident> incidents = new ArrayList<>();
        for (FleetRegister.Row row : unapproved) {
            // ONLY processed-and-named. A row whose posture is `applied` runs
            // under the agreement the tenant signed by joining, so there is
            // nothing unauthorised about it and saying otherwise would cry wolf
            // about every deployment that stated its terms up front. A row that
            // says `not until approved` had its work withheld, so nothing
            // happened under it and reporting processing would tell a tenant
            // about something that did not occur.
            if (row.posture() != TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED) {
                continue;
            }
            incidents.add(new Incident(tenant, row.step(), row.slot(), row.type(),
                    earliestRunOf(row.step(), engine)));
        }
        return List.copyOf(incidents);
    }

    /**
     * When work of this step first ran here.
     *
     * <p>Read from the runs themselves. A step that has been declared and never
     * asked for has no incident to stand yet — the row is unapproved and
     * nothing has happened under it, which is a different thing from work going
     * through unauthorised and has to read differently.
     */
    private static Optional<Instant> earliestRunOf(String step, ObjectStore engine) {
        Instant earliest = null;
        for (var stored : engine.select(Criteria.of(cloud.jengu.dbo.work.WorkModel.TYPE))) {
            String run = new String(stored.payload(), StandardCharsets.UTF_8);
            int dot = step.lastIndexOf('.');
            if (dot < 0 || !run.contains("\"process\":\"" + step.substring(0, dot) + "\"")
                    || !run.contains("\"step\":\"" + step.substring(dot + 1) + "\"")) {
                continue;
            }
            Instant at = stored.lastUpdated();
            if (at != null && (earliest == null || at.isBefore(earliest))) {
                earliest = at;
            }
        }
        return Optional.ofNullable(earliest);
    }
}
