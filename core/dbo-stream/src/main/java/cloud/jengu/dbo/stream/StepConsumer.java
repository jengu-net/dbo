package cloud.jengu.dbo.stream;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Queue;

import javax.sql.DataSource;
import java.util.Set;

/**
 * A step's queue, performed here.
 *
 * <p>The consumer side, and the shape of it is the claim: what an application
 * is offered is <b>its step's queue</b> rather than a lane per tenant. A bean
 * that performed work for twenty tenants over lanes would hold twenty lanes,
 * twenty cursors and twenty things to go wrong; over a queue it holds one
 * consumer and is handed items that happen to name different tenants.
 *
 * <p><b>It names no tenant.</b> The tenant arrives in the item, and a bean
 * that had to be told which tenants exist would need redeploying whenever one
 * joined — which is most of what this design exists to stop.
 *
 * <p>This is where the durable layer is a full executor rather than the
 * writer the joiner holds: it registers the queue, registers the one workflow
 * class, and launches. That costs a listener connection and a pool, which is
 * exactly the cost the substrate placement decision lets a deployment put
 * where it wants it.
 */
public final class StepConsumer implements AutoCloseable {

    private final DBOS dbos;
    private final FleetPerformer performer;

    /**
     * @param substrate where the steps' queues live — several steps sharing
     *                  one substrate share this consumer
     * @param steps     the step codes performed here, whose queues are polled
     * @param writeback where a performer's report goes — the tenant that
     *                  authored the run, resolved per item
     */
    public StepConsumer(DataSource substrate, Set<String> steps,
            FleetWork.Writeback writeback) {
        this.performer = new FleetPerformer(writeback);
        this.dbos = new DBOS(DBOSConfig.defaults("dbo-fleet-consumer")
                .withDataSource(substrate)
                .withDatabaseSchema("dbos")
                // Migrate, because a consumer may reach a substrate before any
                // joiner has: an application performing a step it was deployed
                // for should not depend on somebody else having gone first.
                .withMigrate(true));
        for (String step : steps) {
            dbos.registerQueue(new Queue(FleetWork.queueFor(step)));
        }
        // Registered as the interface and implemented by OUR class, which is
        // what makes the recorded class name the constant both ends know.
        dbos.registerProxy(FleetWork.Work.class, performer);
        dbos.launch();
    }

    /** The bean that performs this step here. */
    public void performing(String stepCode, FleetWork.Performer bean) {
        performer.performing(stepCode, bean);
    }

    @Override
    public void close() {
        dbos.shutdown();
    }
}
