package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.StoredObject;

import java.util.Map;

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
     * The doing, without the declaring.
     *
     * <p>Split out because the two are answered by different things. What a
     * bean performs is a fact about the bean, and belongs on it. How the work
     * is done is a function, and a caller that already knows the step — a
     * test, or a consumer being told a pairing — has no reason to write a
     * class to say it twice.
     */
    @FunctionalInterface
    public interface Doing {
        void perform(String tenant, String step, String runId, String runKey,
                Map<String, java.util.List<StoredObject>> inputs, Reporting reporting);
    }

    /**
     * What a performer is handed of the run: the objects, and where to report.
     *
     * <p>Together because they come of one act. The claim is what entitles
     * this performer to both — to the data because it holds the run, and to
     * reporting because the hold is what says whose account of the work
     * counts — so resolving the inputs anywhere other than beside the claim
     * would be reading a tenant's records on nobody's authority.
     *
     * @param inputs    the run's slots, resolved: the objects themselves, not
     *                  the references the run named. A performer runs outside
     *                  the store and has no verb that takes a reference, so a
     *                  slot delivered as the reference it was authored with
     *                  would be a slot the performer can do nothing with
     * @param reporting where its account of the work goes
     */
    public record Taken(Map<String, java.util.List<StoredObject>> inputs,
            Reporting reporting) {
    }

    /**
     * The object in a slot that holds one.
     *
     * <p>Here rather than left to the caller for the reason {@code Work} has
     * the same: a step declaring one object should not have to write "the
     * first of them", because the declaration said there is one. Refused when
     * there are several, so a performer written for a single slot and handed a
     * repeating one is told rather than quietly processing a member of it.
     */
    public static StoredObject one(Map<String, java.util.List<StoredObject>> inputs,
            String slot) {
        java.util.List<StoredObject> held = inputs.get(slot);
        if (held == null || held.isEmpty()) {
            throw new IllegalStateException("this run filled no slot '" + slot
                    + "'; it filled: " + inputs.keySet());
        }
        if (held.size() != 1) {
            throw new IllegalStateException("slot '" + slot + "' holds " + held.size()
                    + " objects and was asked for one");
        }
        return held.get(0);
    }

    public interface Performer extends Doing {

        /**
         * Which step this bean performs.
         *
         * <p>Declared by the bean, the way a tenant-level {@code StepService}
         * declares its own — so an application writes a bean and nothing else,
         * and the container is what finds it. Without this a consumer has to
         * be told the pairing by whoever constructs it, which means an
         * application constructing a consumer, which is the thing an assembly
         * exists to prevent.
         */
        String step();
    }

    /** That function, as a bean performing that step. */
    public static Performer performing(String step, Doing doing) {
        return new Performer() {
            @Override
            public String step() {
                return step;
            }

            @Override
            public void perform(String tenant, String code, String runId, String runKey,
                    Map<String, java.util.List<StoredObject>> inputs,
                    Reporting reporting) {
                doing.perform(tenant, code, runId, runKey, inputs, reporting);
            }
        };
    }

    /**
     * What the durable layer actually calls, and why it is not
     * {@link Performer}.
     *
     * <p>A workflow's arguments are SERIALISED — they sit in the queue as
     * data and are read back by whichever process takes the item, possibly
     * after a restart. A live handle cannot travel that way. So what crosses
     * is four strings, and the handle a performer reports through is resolved
     * on the far side from them. Handing a performer the lane in the item
     * would compile and fail the moment a consumer picked up work it had not
     * itself enqueued.
     */
    public interface Work {
        void perform(String tenant, String step, String runId, String runKey);
    }

    /**
     * How a performer says what happened, and the only way it can.
     *
     * <p>Every verb here is the tenant's own lane's, so an outcome from a
     * fleet consumer passes exactly the rules an outcome from a participant on
     * a port passes: whether this step may be closed by a machine, whether
     * the report is in order, and who is recorded as having performed it. A
     * writeback that wrote to the store directly would be the one place in
     * this design where a tenant's rules did not reach a tenant's run.
     *
     * <p>Narrow on purpose. A performer is handed what it needs to report and
     * not a lane, because a lane can also poll, claim and declare — and a
     * consumer that could claim would be a consumer that could take work
     * nobody offered it.
     */
    public interface Reporting {

        /** Done, with what it counted. */
        void closed(java.util.Map<String, Long> tally);

        /** Got somewhere, and is still going. */
        void checkpoint(java.util.Map<String, Long> counts);

        /**
         * Did not finish, and says why. A release rather than a close, because
         * a run that read as done is the one outcome a record exists to
         * prevent.
         */
        void released(String reason);
    }

    /**
     * Where a performer's report goes: the tenant that authored the run.
     *
     * <p>Resolved per item rather than held, because a consumer performs work
     * for every tenant and holding a lane into each of them is the thing this
     * design replaced.
     */
    @FunctionalInterface
    public interface Writeback {
        java.util.Optional<Taken> take(String tenant, String step, String runKey);
    }

    /**
     * The queue a step's items wait in.
     *
     * <p>Named from the step code rather than from the substrate, because
     * several steps may share a substrate and each still owns its own
     * backlog — the substrate is where the queue lives and the queue is whose
     * work it holds.
     */
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
