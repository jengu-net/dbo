package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.work.FleetWork;
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
    /** The steps whose queues this consumer polls, fixed when it was built. */
    private final Set<String> serving;

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
        this.serving = Set.copyOf(steps);
        String[] listening = serving.stream().map(FleetWork::queueFor).toArray(String[]::new);
        this.dbos = new DBOS(DBOSConfig.defaults("dbo-fleet-consumer")
                .withDataSource(substrate)
                .withDatabaseSchema("dbos")
                // ITS OWN STEPS AND NOTHING ELSE. A process listens to every
                // queue registered in its system database unless it says
                // otherwise — and several steps share a substrate on purpose,
                // so a consumer deployed for one step would dequeue another
                // step's work, find no bean for it and have nowhere to put it.
                // That is the shape of a defect that loses work quietly: the
                // item would be taken, nothing would perform it, and the queue
                // would look drained.
                .withListenQueues(listening)
                // Migrate, because a consumer may reach a substrate before any
                // joiner has: an application performing a step it was deployed
                // for should not depend on somebody else having gone first.
                .withMigrate(true));
        for (String step : steps) {
            // PARTITIONED BY TENANT at the other end. A fleet step carries
            // every tenant's work, so one tenant's backlog would otherwise
            // decide how long every other tenant waits; a partition per tenant
            // gives each its own flow control and makes a busy tenant slow only
            // itself.
            dbos.registerQueue(new Queue(FleetWork.queueFor(step))
                    .withPartitioningEnabled(true));
        }
        // Registered as the interface and implemented by OUR class, which is
        // what makes the recorded class name the constant both ends know.
        dbos.registerProxy(FleetWork.Work.class, performer);
        dbos.launch();
    }

    /**
     * The bean that performs this step here.
     *
     * <p>Refused for a step this consumer was not built for, and that refusal
     * is the point. Registering a bean does not make the durable layer poll a
     * queue — the queues are registered before the executor launches — so a
     * bean handed over for a step this consumer does not serve would sit there
     * being correct and never being called. That is the shape of defect this
     * store is built to refuse: something constructed, plausible, and reachable
     * by nothing.
     */
    public void performing(FleetWork.Performer bean) {
        performing(bean.step(), bean);
    }

    /** The same, for a caller that already knows the pairing. */
    public void performing(String stepCode, FleetWork.Doing doing) {
        performing(stepCode, FleetWork.performing(stepCode, doing));
    }

    /** The same, for a caller naming the step itself. */
    public void performing(String stepCode, FleetWork.Performer bean) {
        if (!serving.contains(stepCode)) {
            throw new IllegalArgumentException("this consumer was built to serve " + serving
                    + " and does not poll a queue for '" + stepCode + "', so a bean given for "
                    + "it would never be called. Build the consumer with that step among its "
                    + "own, or give the bean to the consumer that serves it.");
        }
        performer.performing(stepCode, bean);
    }

    /**
     * That bean is gone.
     *
     * <p>The queue keeps being polled, because this consumer serves the other
     * steps on its substrate and they have not gone anywhere. An item for the
     * withdrawn step is refused the way an item for a step nothing ever
     * performed is — released with a reason, and taken again by whoever
     * performs it next.
     */
    public void stopPerforming(String stepCode) {
        performer.stopPerforming(stepCode);
    }

    @Override
    public void close() {
        dbos.shutdown();
    }
}
