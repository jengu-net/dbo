package cloud.jengu.dbo.subscriptions;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.StepOptions;
import dev.dbos.transact.workflow.Workflow;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Durable subscription delivery over the change feed (dbo#8).
 *
 * <p>Matching is a search, not an evaluator: an event matches a subscription
 * iff {@code count(compiled criteria AND id = event.objectId) > 0} — the whole
 * tier-1 machinery is reused. Delivery runs as a DBOS workflow per
 * (subscription, event) with a DETERMINISTIC workflow id {@code subId:seq}:
 * the feed's at-least-once redelivery and DBOS's id-dedup compose into
 * exactly-once delivery. Retry/backoff lives in the step policy; exhaustion
 * writes a queryable dead-letter row — visible, never silent.
 *
 * <p>Tenant-plane per §7.4: the DBOS system schema lives in the tenant's own
 * database ({@code dbos} schema), so erasure-by-drop covers delivery state.
 */
public final class SubscriptionEngine implements AutoCloseable {

    public interface DeliveryWorkflows {
        void deliver(String subscriptionId, String endpoint, String notificationJson, long seq);
    }

    private static final String CONSUMER = "subscriptions.dispatch";
    private static final String QUEUE = "dbo.notify";

    private final DataSource ds;
    private final String domain;
    private final ObjectStore store;
    private final ChangeFeed feed;
    private final SubscriptionSource source;
    private final Function<String, Criteria> criteriaCompiler;
    private final NotificationTransport transport;
    private final DBOS dbos;
    private final DeliveryWorkflows deliveryProxy;
    private final List<BiConsumer<SubscriptionSpec, String>> localListeners = new CopyOnWriteArrayList<>();
    private volatile TopicSubscriptionSource topicSource;
    private volatile NotificationComposer notificationComposer;
    private volatile BiFunction<String, Map<String, String>, Criteria> filterCompiler;
    private volatile Thread dispatcherThread;
    private volatile boolean running;

    private static final java.util.regex.Pattern DOMAIN = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public SubscriptionEngine(DataSource dataSource, String domain, ObjectStore store, ChangeFeed feed,
            SubscriptionSource source, Function<String, Criteria> criteriaCompiler,
            NotificationTransport transport, String dbUrl, String dbUser, String dbPassword) {
        if (!DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("invalid domain: " + domain);
        }
        this.ds = dataSource;
        this.domain = domain;
        this.store = store;
        this.feed = feed;
        this.source = source;
        this.criteriaCompiler = criteriaCompiler;
        this.transport = transport;
        ensureDlqTable();

        DBOSConfig cfg = DBOSConfig.defaults("dbo-subscriptions-" + domain)
                .withDatabaseUrl(dbUrl)
                .withDbUser(dbUser)
                .withDbPassword(dbPassword)
                .withDatabaseSchema("dbos")
                .withMigrate(true);
        this.dbos = new DBOS(cfg);
        dbos.registerQueue(new Queue(QUEUE));
        this.deliveryProxy = dbos.registerProxy(DeliveryWorkflows.class, new DeliveryImpl());
        dbos.launch();
    }

    /** The delivery workflow: one retried step; exhaustion dead-letters and completes. */
    final class DeliveryImpl implements DeliveryWorkflows {
        @Override
        @Workflow(name = "deliverNotification")
        public void deliver(String subscriptionId, String endpoint, String notificationJson, long seq) {
            try {
                dbos.runStep(() -> {
                    try {
                        transport.deliver(endpoint, notificationJson);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, new StepOptions("post").withMaxAttempts(4)
                        .withRetryInterval(java.time.Duration.ofMillis(100))
                        .withBackoffRate(2.0));
            } catch (Exception exhausted) {
                dbos.runStep(() -> deadLetter(subscriptionId, endpoint, seq,
                        String.valueOf(exhausted.getMessage())), "deadLetter");
            }
        }
    }

    // ------------------------------------------------------------ dispatch

    /** One dispatch round: read, match, enqueue, ack. Public for deterministic tests. */
    public int dispatchOnce(int chunkSize) {
        FeedChunk<FeedItem> chunk = feed.readFor(CONSUMER, chunkSize);
        if (chunk.items().isEmpty()) {
            return 0;
        }
        List<SubscriptionSpec> active = source.active();
        for (FeedItem item : chunk.items()) {
            dispatchTopics(item); // topic path sees every kind incl. deletes
            if (item.kind() == ChangeKind.DELETED) {
                continue; // criteria-string rest-hooks notify on create/update
            }
            for (SubscriptionSpec sub : active) {
                if (!matches(sub, item)) {
                    continue;
                }
                String payload = new String(item.payload(), StandardCharsets.UTF_8);
                String workflowId = sub.id() + ":" + item.seq();
                dbos.startWorkflow(
                        () -> deliveryProxy.deliver(sub.id(), sub.endpoint(), payload, item.seq()),
                        new StartWorkflowOptions(workflowId).withQueue(QUEUE));
                for (BiConsumer<SubscriptionSpec, String> listener : localListeners) {
                    listener.accept(sub, payload);
                }
            }
        }
        feed.ack(CONSUMER, chunk.nextCursor());
        return chunk.items().size();
    }

    private boolean matches(SubscriptionSpec sub, FeedItem item) {
        Criteria criteria;
        try {
            criteria = criteriaCompiler.apply(sub.criteria());
        } catch (RuntimeException e) {
            return false; // an uncompilable subscription matches nothing (visible via its own status later)
        }
        if (!criteria.typeName().equals(item.typeName())) {
            return false;
        }
        return store.count(criteria.idEquals(item.objectId())) > 0;
    }

    /**
     * Enables the topic-based path (dbo#15): topics + subscriptions from the
     * personality source, notifications shaped by the composer, filters
     * compiled through the personality's strict search compiler.
     */
    public SubscriptionEngine withTopics(TopicSubscriptionSource source,
            NotificationComposer composer,
            BiFunction<String, Map<String, String>, Criteria> filterCompiler) {
        this.topicSource = source;
        this.notificationComposer = composer;
        this.filterCompiler = filterCompiler;
        ensureTopicCounterTable();
        return this;
    }

    private void dispatchTopics(FeedItem item) {
        TopicSubscriptionSource source = topicSource;
        if (source == null) {
            return;
        }
        Map<String, TopicSpec> topicsByUrl = new java.util.LinkedHashMap<>();
        for (TopicSpec topic : source.topics()) {
            topicsByUrl.put(topic.url(), topic);
        }
        for (TopicSubscription sub : source.activeTopicSubscriptions()) {
            TopicSpec topic = topicsByUrl.get(sub.topicUrl());
            if (topic == null
                    || !topic.resourceType().equals(item.typeName())
                    || !topic.interactions().contains(item.kind())) {
                continue;
            }
            // strictness at the source: filters must lie within canFilterBy
            if (!topic.allowedFilterParams().containsAll(sub.filters().keySet())) {
                continue; // nonconforming subscription delivers nothing
            }
            // DELETE events match on type+interaction only: the tombstoned
            // envelope cannot answer filters (queryCriteria.previous = follow-up)
            if (item.kind() != ChangeKind.DELETED && !sub.filters().isEmpty()) {
                Criteria criteria = filterCompiler.apply(topic.resourceType(), sub.filters());
                if (store.count(criteria.idEquals(item.objectId())) == 0) {
                    continue;
                }
            }
            long eventNumber = nextEventNumber(sub.id());
            String notification = notificationComposer.compose(sub, topic, item, eventNumber);
            String workflowId = sub.id() + ":" + item.seq();
            dbos.startWorkflow(
                    () -> deliveryProxy.deliver(sub.id(), sub.endpoint(), notification, item.seq()),
                    new StartWorkflowOptions(workflowId).withQueue(QUEUE));
        }
    }

    private long nextEventNumber(String subscriptionId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_topic_counter (subscription_id, events)
                     VALUES (?, 1)
                     ON CONFLICT (subscription_id) DO UPDATE SET events = %s_topic_counter.events + 1
                     RETURNING events""".formatted(domain, domain))) {
            ps.setString(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("event counter failed", e);
        }
    }

    private void ensureTopicCounterTable() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS state.%s_topic_counter (
                       subscription_id text PRIMARY KEY,
                       events bigint NOT NULL
                     )""".formatted(domain))) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("counter setup failed", e);
        }
    }

    /** In-process surface: same matching, direct callback (REQ-DBO-EVT-IN-PROCESS-SURFACE). */
    public void addLocalListener(BiConsumer<SubscriptionSpec, String> listener) {
        localListeners.add(listener);
    }

    /** Background dispatching on a virtual thread. */
    public synchronized void start(long pollMillis) {
        if (running) {
            return;
        }
        running = true;
        dispatcherThread = Thread.ofVirtual().name("dbo-subscriptions-" + domain).start(() -> {
            while (running) {
                try {
                    if (dispatchOnce(200) == 0) {
                        Thread.sleep(pollMillis);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    try {
                        Thread.sleep(pollMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        running = false;
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        dbos.shutdown();
    }

    // --------------------------------------------------------- dead letters

    public record DeadLetter(String subscriptionId, String endpoint, long seq, String reason) {}

    public List<DeadLetter> deadLetters() {
        List<DeadLetter> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT subscription_id, endpoint, seq, reason FROM state.%s_subscription_dlq ORDER BY seq"
                             .formatted(domain));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new DeadLetter(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("dlq read failed", e);
        }
        return out;
    }

    private void deadLetter(String subscriptionId, String endpoint, long seq, String reason) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO state.%s_subscription_dlq (subscription_id, endpoint, seq, reason)
                     VALUES (?, ?, ?, ?) ON CONFLICT (subscription_id, seq) DO NOTHING"""
                     .formatted(domain))) {
            ps.setString(1, subscriptionId);
            ps.setString(2, endpoint);
            ps.setLong(3, seq);
            ps.setString(4, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("dlq write failed", e);
        }
    }

    private void ensureDlqTable() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS state.%s_subscription_dlq (
                       subscription_id text NOT NULL,
                       endpoint text NOT NULL,
                       seq bigint NOT NULL,
                       reason text NOT NULL,
                       created_at timestamptz NOT NULL DEFAULT now(),
                       PRIMARY KEY (subscription_id, seq)
                     )""".formatted(domain))) {
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("dlq setup failed", e);
        }
    }
}
