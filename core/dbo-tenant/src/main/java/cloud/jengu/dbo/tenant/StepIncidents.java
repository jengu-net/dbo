package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.process.Steps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of a tenant's mandatory steps are missing, and since when it has been
 * worth saying.
 *
 * <p><b>Not a gate.</b> This system is asynchronous by design: work buffers on
 * the queue when nothing serves a step, and a participant that arrives later
 * drains it — that is what pull-based participation buys. Refusing the tenant
 * for a missing step executor would convert that graceful degradation into a
 * self-inflicted outage. Contrast {@link FaceRequirements}, which genuinely
 * gates: a missing face capability breaks serving itself.
 *
 * <p>What the spec's {@code mandatorySteps} decides is <b>classification</b>:
 * whether an absent step executor is an incident. A mandatory step nothing has
 * contributed is one — named here and in the log, cleared by the scan after
 * the step arrives — while every undeclared step's absence is no incident at
 * all: free to appear with its participant and disappear with it.
 *
 * <p>Transitions log once — an incident opening is an ERROR, its clearing an
 * INFO — because a scan loop that restates the same absence every pass turns
 * the log into a heartbeat, and a heartbeat is what people filter out.
 */
final class StepIncidents {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.tenant");

    /** Per tenant, the mandatory steps currently missing. */
    private final Map<String, Set<String>> missing = new ConcurrentHashMap<>();

    /** Re-classifies one serving tenant against what is contributed now. */
    void observe(TenantSpec spec, Steps steps) {
        Set<String> now = new TreeSet<>();
        for (String stepId : spec.mandatorySteps()) {
            if (steps.byId(stepId).isEmpty()) {
                now.add(stepId);
            }
        }
        Set<String> before = missing.getOrDefault(spec.code(), Set.of());
        for (String stepId : now) {
            if (!before.contains(stepId)) {
                LOG.error("incident: tenant={} mandatory step '{}' has nothing contributing "
                        + "it — the tenant serves and its runs queue, but nothing will take "
                        + "them until the step arrives", spec.code(), stepId);
            }
        }
        for (String stepId : before) {
            if (!now.contains(stepId)) {
                LOG.info("resolved: tenant={} mandatory step '{}' is contributed again",
                        spec.code(), stepId);
            }
        }
        if (now.isEmpty()) {
            missing.remove(spec.code());
        } else {
            missing.put(spec.code(), Set.copyOf(now));
        }
    }

    /** Retracted tenants stop having incidents, silently — retraction is not a fix. */
    void retain(Set<String> codes) {
        missing.keySet().retainAll(codes);
    }

    /** Per tenant, the mandatory steps currently missing — an operator surface's read. */
    Map<String, Set<String>> byTenant() {
        return Map.copyOf(missing);
    }
}
