package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.wire.RecordWire;
import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.migrations.MigrationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Work authored in every tenant, offered to the step that performs it.
 *
 * <p>One observer for the deployment rather than one per tenant. What it
 * reads is each tenant's WORK feed as a named durable consumer — the
 * mechanism a tenant's own lane already uses — and what it writes is one item
 * per run into the queue of the step that run is of.
 *
 * <p><b>It claims nothing.</b> A joiner that claimed would hold claims across
 * every tenant and drop all of them when it died. Observing is not claiming:
 * the run stays exactly where it was, offered on nothing, and what replaces
 * that failure is a joiner that falls behind — which this store already reads
 * as a consumer whose cursor is not moving, rather than as work nobody wanted.
 *
 * <p><b>And it consumes nothing.</b> It holds a {@link DBOSClient} per
 * substrate, which writes to the durable layer without being an executor: no
 * registered workflow, no queue polling, and no permanently held listener
 * connection. That matters beyond tidiness — a joiner that launched a full
 * durable runtime per substrate would hold a listener and a pool for every
 * step in the deployment, which is the connection cost the substrate
 * placement decision exists to let a deployment control.
 *
 * <p><b>Idempotent on the run's own identity</b>, because no transaction
 * spans the read of a tenant's feed and the write to a step's substrate. The
 * durable layer keeps one row per workflow id, so re-offering a run after a
 * restart or a lost ack writes no second item.
 */
public final class StepJoiner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.fleet");

    /**
     * The cursor's name in every tenant's feed. One name, because there is one
     * joiner: two would each believe they had read what the other read.
     */
    public static final String CONSUMER = "fleet-joiner";

    /** Which substrate each declared step's queue lives on. */
    private final Map<String, DataSource> substrateOf;
    /** One writer per substrate, not per step: several steps may share one. */
    private final Map<DataSource, DBOSClient> clients = new ConcurrentHashMap<>();
    /** The tenants being read, by code. */
    private final Map<String, ChangeFeed> following = new ConcurrentHashMap<>();
    /**
     * What this tenant's work must not be offered to, asked once per pass.
     *
     * <p><b>Asked rather than held</b>, and the difference is a class of bug
     * rather than a preference. A tenant declines a step, or authorises a row
     * that was being withheld, by changing its own declaration — and that
     * change applies while it serves, precisely so withdrawing or granting
     * authorisation costs no outage. A set captured when the tenant was first
     * followed would go stale at exactly the moment it mattered: the tenant
     * would authorise a row and its work would stay withheld until something
     * restarted.
     */
    private final java.util.function.Function<String, Set<String>> notOffered;

    /**
     * @param substrateOf step code to the substrate its queue lives on — the
     *                    declared steps and nothing else, so a run of a step
     *                    the deployment does not perform is left where it
     *                    belongs by having nowhere to go
     */
    public StepJoiner(Map<String, DataSource> substrateOf,
            java.util.function.Function<String, Set<String>> notOffered) {
        this.substrateOf = Map.copyOf(substrateOf);
        this.notOffered = notOffered;
        // The durable bootstrap, here rather than where the database was
        // made: the module that provisions tenants has no durable layer and
        // buying it one to migrate a schema it never reads would be a
        // dependency bought for a side effect. Once per substrate, and
        // several steps sharing one are migrated once.
        for (DataSource substrate : Set.copyOf(this.substrateOf.values())) {
            MigrationManager.runMigrations(DBOSConfig.defaults("dbo-fleet-joiner")
                    .withDataSource(substrate)
                    .withDatabaseSchema("dbos"));
        }
    }

    /** Read this tenant's work from now on. */
    public void follow(String tenant, ChangeFeed workFeed) {
        following.put(tenant, workFeed);
    }

    /** Stop reading it — a tenant taken down, its cursor left where it is. */
    public void unfollow(String tenant) {
        following.remove(tenant);
    }

    /**
     * One pass over every followed tenant. Returns how many items were
     * offered.
     *
     * <p>Deterministic and callable, rather than only reachable from a loop,
     * for the reason the configuration sweep is: a claim about what one pass
     * did is a claim nobody can make by waiting.
     */
    public int joinOnce(int chunkSize) {
        int offered = 0;
        for (Map.Entry<String, ChangeFeed> tenant : following.entrySet()) {
            offered += joinOnce(tenant.getKey(), tenant.getValue(), chunkSize);
        }
        return offered;
    }

    private int joinOnce(String tenant, ChangeFeed feed, int chunkSize) {
        FeedChunk<FeedItem> chunk = feed.readFor(CONSUMER, chunkSize);
        if (chunk.items().isEmpty()) {
            return 0;
        }
        // Once per pass, not per item: a declaration does not change inside a
        // pass, and asking per item would read a tenant's spec for every run.
        Set<String> withheld = notOffered.apply(tenant);
        int offered = 0;
        for (FeedItem item : chunk.items()) {
            if (!cloud.jengu.dbo.work.WorkModel.TYPE.equals(item.typeName()) || item.deleted()) {
                continue;
            }
            Map<String, Object> run = asMap(item.payload());
            String code = codeOf(run);
            if (code != null && withheld.contains(code)) {
                // THE TENANT SAID NOT TO. Its run stays where it is, offered to
                // nothing, exactly as a run of a step the deployment does not
                // perform does — because from the tenant's side those are the
                // same fact: this is not being done to my data. Declining a
                // step the deployment REQUIRES never reaches here; it is
                // refused where the declaration is read.
                continue;
            }
            DataSource substrate = code == null ? null : substrateOf.get(code);
            if (substrate == null) {
                // A run of a step this deployment does not perform. Left where
                // it belongs rather than skipped with a shrug: a tenant's own
                // step is consumed over its own door, and the joiner not
                // lifting it IS the filter the two levels are made of.
                continue;
            }
            // The FEED ITEM's object id, which is the run's own identity —
            // the payload does not carry one, because a stored object's id is
            // the store's and not something the record repeats. Getting this
            // from the payload yields null, and a null in the workflow id
            // makes every run of a tenant one item.
            offer(tenant, code, substrate, item.objectId(),
                    String.valueOf(run.get("key")));
            offered++;
        }
        // Acked after the offers, so a crash between them re-offers rather
        // than skips — which the workflow id makes harmless, and which is the
        // direction to fail in when only one is available.
        feed.ack(CONSUMER, chunk.nextCursor());
        return offered;
    }

    private void offer(String tenant, String code, DataSource substrate,
            String runId, String runKey) {
        DBOSClient writer = clients.computeIfAbsent(substrate, DBOSClient::new);
        try {
            writer.enqueueWorkflow(
                    // (workflowName, queueName), in that order — reversed,
                    // this compiles and files every item under a queue called
                    // 'perform' with the step code as its name, which is a
                    // backlog no consumer of that step will ever look in.
                    new DBOSClient.EnqueueOptions(FleetWork.METHOD, FleetWork.queueFor(code))
                            .withClassName(FleetWork.PERFORMER)
                            .withWorkflowId(FleetWork.idFor(tenant, runId)),
                    FleetWork.itemFor(tenant, code, runId, runKey));
        } catch (RuntimeException alreadyThere) {
            // One row per workflow id is the durable layer's own rule, and a
            // repeat is the ordinary case rather than a fault: this pass is
            // re-reading what a previous one offered before its ack landed.
            LOG.debug("already offered: tenant={} step={} run={}", tenant, code, runId);
        }
    }

    /** {@code process.step}, which is how a step is addressed. */
    private static String codeOf(Map<String, Object> run) {
        Object process = run.get("process");
        Object step = run.get("step");
        return process == null || step == null ? null : process + "." + step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(byte[] payload) {
        Object parsed = RecordWire.read(new String(payload, StandardCharsets.UTF_8));
        return parsed instanceof Map<?, ?> map
                ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @Override
    public void close() {
        clients.values().forEach(DBOSClient::close);
        clients.clear();
        following.clear();
    }
}
