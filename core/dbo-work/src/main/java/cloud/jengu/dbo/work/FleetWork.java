package cloud.jengu.dbo.work;


/**
 * What a joined item is, said once for the side that writes it and the side
 * that performs it.
 *
 * <p>An item enqueued for a step the deployment performs is a durable-layer
 * workflow, and a workflow is addressed by three names its two ends have to
 * agree on: the queue it waits in, the class that performs it and the method
 * on that class. Agreeing by coincidence is how a joiner and a consumer end up
 * writing to a queue nobody reads — which looks exactly like a step nobody
 * asked to run.
 *
 * <p><b>One class, dispatching on the step.</b> A class per step would make
 * the joiner generate names it cannot check and the consumer register a type
 * per declaration; one class means the step travels as an argument, and what
 * decides which bean performs an item is a lookup rather than a class name.
 * The cost is that the class name carries no information — which is why the
 * QUEUE is per step and the partition is real.
 *
 * <p><b>The queue is the step's own</b>, so a hot step's backlog is its own
 * backlog and a deployment can give it a substrate of its own without
 * anything else moving. That is the same decision the substrate placement is,
 * one level down.
 */
public final class FleetWork {

    /**
     * The class both ends name.
     *
     * <p>It is a CONCRETE class of this bundle's, and that is forced rather
     * than chosen: the durable layer records the implementation's class name,
     * not the interface's, so a consumer registering an application's own bean
     * as the workflow would record a name the joiner cannot know — a bean
     * class in somebody's application. One class here, dispatching to whatever
     * bean was registered for the step, is what makes the name a constant.
     */
    public static final String PERFORMER = "cloud.jengu.dbo.stream.FleetPerformer";

    /** The method on it. One, because the step is an argument and not a name. */
    public static final String METHOD = "perform";

    private FleetWork() {
    }

    /**
     * What the durable layer calls, and what an application's bean is reached
     * through.
     *
     * <p>What a performer is handed is the item and nothing else: which tenant
     * authored the run, which step it is of, and the run by identity. It is
     * not handed the record — a queue that carried the record would be a
     * second place the record lives, and the payload is sealed to whoever
     * opens it rather than to whoever carries it.
     */
    /**
     * The queue a step's items wait in.
     *
     * <p>Named from the step code rather than from the substrate, because
     * several steps may share a substrate and each still owns its own
     * backlog — the substrate is where the queue lives and the queue is whose
     * work it holds.
     */
    /**
     * What the queue calls, and the whole of what it can carry.
     *
     * <p>Four strings, because a queue item is serialised: the handle a
     * performer needs is made on the far side from them and never travels.
     * This is the durable layer's calling convention and deliberately not an
     * API anybody implements — a step is written as a {@code StepService},
     * whichever level declared it, and the class that implements this is this
     * bundle's own.
     */
    public interface Performs {
        void perform(String tenant, String step, String runId, String runKey);
    }

    public static String queueFor(String stepCode) {
        return "fleet:" + stepCode;
    }

    /**
     * What one item carries: which tenant authored the run, which step it is
     * of, and the run itself by identity.
     *
     * <p>The run is named and not copied. A joined item is a copy bounded by
     * the work that caused it, and the smallest honest copy is the one that
     * says where the record is — the tenant holds the run, the payload is
     * sealed to whoever opens it, and a queue that carried the record would
     * be a second place the record lives.
     */
    public static Object[] itemFor(String tenant, String stepCode, String runId, String runKey) {
        return new Object[] {tenant, stepCode, runId, runKey};
    }

    /**
     * The workflow id, which is what makes joining an item idempotent.
     *
     * <p>No transaction spans reading a tenant's feed and writing to a step's
     * substrate, so the joiner WILL re-offer items it has already written —
     * after a restart, or when an ack does not land. The durable layer keeps
     * one row per workflow id, so deriving the id from the run's own identity
     * is what turns a repeated offer into no second item. A run changes many
     * times and appears in the feed once per version; the id names the RUN and
     * not the version, because what is joined is the run.
     */
    public static String idFor(String tenant, String runId) {
        return "fleet:" + tenant + ":" + runId;
    }
}
