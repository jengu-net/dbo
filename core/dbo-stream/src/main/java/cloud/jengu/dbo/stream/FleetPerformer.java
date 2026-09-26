package cloud.jengu.dbo.stream;

import dev.dbos.transact.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one workflow class, dispatching on the step.
 *
 * <p><b>Concrete, and this bundle's, because the durable layer records the
 * implementation's class name.</b> A consumer that registered an
 * application's own bean as the workflow would have the queue carry a class
 * name from somebody's application, which the joiner writing the item cannot
 * know. One class here and a lookup inside it is what lets the two ends agree
 * on a constant.
 *
 * <p><b>It keeps nothing between asks.</b> What it holds is the registry of
 * which bean performs which step — configuration, not state — and every call
 * is answered from the item it was handed. A performer that remembered
 * anything about a run between asks would be a consumer that cannot be
 * restarted, which is the one thing work that crosses a queue has to survive.
 */
public final class FleetPerformer implements FleetWork.Work {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.fleet");

    /** Which bean performs which step. Configuration, and the only thing held. */
    private final Map<String, FleetWork.Performer> beans = new ConcurrentHashMap<>();
    /** Where a report goes, resolved per item because a consumer serves every tenant. */
    private final FleetWork.Writeback writeback;

    public FleetPerformer(FleetWork.Writeback writeback) {
        this.writeback = writeback;
    }

    /** The bean that performs this step, from now on. */
    public void performing(String stepCode, FleetWork.Performer bean) {
        beans.put(stepCode, bean);
    }

    /**
     * One item.
     *
     * <p>An item for a step nothing here performs is not an error and must not
     * fail the workflow: a substrate may carry several steps' queues and a
     * process may be registered for one of them. It is left alone, which is
     * what an unregistered consumer looks like from the queue's side — an item
     * nobody has taken yet.
     */
    /**
     * One item, with the report's destination resolved here.
     *
     * <p>Four strings and nothing else, because that is what a queue can
     * hold: the handle a performer reports through is made on this side from
     * them, and never travels. A tenant gone while its work sat in the queue
     * has no lane to report through, which is not the bean's problem to
     * discover halfway through — the item is left for a pass when the tenant
     * is back, which is what an item nobody has taken yet already looks like.
     */
    @Override
    @Workflow(name = "perform")
    public void perform(String tenant, String step, String runId, String runKey) {
        FleetWork.Performer bean = beans.get(step);
        if (bean == null) {
            LOG.debug("no bean here performs {}: tenant={} run={}", step, tenant, runId);
            return;
        }
        java.util.Optional<FleetWork.Reporting> reporting =
                writeback.reporting(tenant, step, runKey);
        if (reporting.isEmpty()) {
            LOG.info("no lane into {} for {}, so its work waits: run={}", tenant, step, runId);
            return;
        }
        bean.perform(tenant, step, runId, runKey, reporting.get());
    }
}
