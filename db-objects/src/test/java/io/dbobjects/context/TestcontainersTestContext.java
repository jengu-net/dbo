package io.dbobjects.context;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.dbo.eventing.SimpleEventConfiguration;
import io.dbo.eventing.kafka.KafkaEventingFactory;
import io.dbobjects.ApplicationState;
import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.DBOApplicationContext;
import io.dbobjects.DbObjects;
import io.dbobjects.JsonUtil;
import io.dbobjects.ObjectMapper;
import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.postgres.Mappers;
import io.dbobjects.db.postgres.QueryRunner;
import io.dbobjects.eventing.EventingFactory;
import io.dbobjects.nodesync.SyncedNodeState;
import io.dbobjects.nodesync.postgres.NodeSyncQueue;
import io.dbobjects.parallel.NodeState;
import io.dbobjects.storage.Storage;
import io.dbobjects.storage.StorageEvent;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.redpanda.RedpandaContainer;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.DEFAULT_NUMBER_GENERATOR;
import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.randomNanoId;

@Slf4j
public class TestcontainersTestContext implements AutoCloseable {

    private static final char[] alphabet = new char[]{
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
            't', 'u', 'v', 'x', 'y', 'z'};

    private static final char[] alphaNumbers = new char[]{
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
        't', 'u', 'v', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    private PostgreSQLContainer DB_CONTAINER;
    private RedpandaContainer EVENTING_CONTAINER;

    @Getter
    private EventingFactory eventingFactory = null;
    static String DB_USER = "test";
    static String DB_PASSWORD = "test";
    public String DB_SCHEMA = "test_schema";

    public String DB_HOST;

    public int DB_PORT;
    public String DB = "test_db";
    String DB_JDBC_URL;
    String REDPANDA_HOST;

    private final Pool SQL_POOL;

    private final NodeSyncQueue nodeSyncqueue;
    private final Collection<TestDbObjects> createdNodes = new LinkedList<>();

    private final DBOApplicationContext applicationContext;

    public static final ObjectMapper objectMapper = JsonUtil.getObjectMapper();

    private final QueryRunner qr = new QueryRunner();
    private final Mappers mappers = new Mappers(objectMapper);

    private StateUpdater testStateUpdater = new TestContextFakeStateUpdater();

    private static String randomAlpha(int length) {
        return randomNanoId(DEFAULT_NUMBER_GENERATOR, alphabet, length);
    }

    public static String randomName(String prefix) {
        return prefix + randomNanoId(DEFAULT_NUMBER_GENERATOR, alphaNumbers, 4);
    }

    private static TestNodeConfig withDefaultNodeConfig() {
        return new TestNodeConfig()
                .nodeId(randomAlpha(10))
                .appCode("app")
                .appVer(1);
    }

    public DbObjects createNode(java.util.function.Consumer<TestNodeConfig> cfgUpdater) {
        var cfg = withDefaultNodeConfig();
        cfgUpdater.accept(cfg);

        var dbObjects = new TestDbObjects(cfg, this.applicationContext,
                new DBOApplicationConfig()
                        .setDefaultSchemaName(DB_SCHEMA)
                        .setDefaultDatabaseName(DB)
                        .setApplicationVersion(cfg.appVer())
                        .setEventHandlerEnabled(false)
                        .setNodeId(cfg.nodeId())
                        .setApplicationCode(cfg.appCode())
                        .setDbHost(DB_HOST)
                        .setDbPort(DB_PORT)
                        .setDbUsername(DB_USER)
                        .setDbPassword(DB_PASSWORD), cfg.storages());
        dbObjects.initTestDatabase(cfg);
        this.createdNodes.add(dbObjects);
        return dbObjects;
    }

    public DbObjects createNode(String appCode, String nodeId, Collection<Storage<?>> storages) {
        return createNode(cfg -> cfg.appCode(appCode).nodeId(nodeId).storages(storages));
    }

    private DbObjects createNode(String appCode, int appVersion, String nodeId,
                                 Collection<Storage<?>> storages) {
        var dbObjects = new TestDbObjects(null, this.applicationContext,
                new DBOApplicationConfig()
                        .setDefaultSchemaName(DB_SCHEMA)
                        .setApplicationVersion(appVersion)
                        .setEventHandlerEnabled(false)
                        .setNodeId(nodeId)
                        .setApplicationCode(appCode), storages);
        this.createdNodes.add(dbObjects);
        return dbObjects;
    }

    public Optional<ApplicationState> getApplicationState(String appCode) {
        return qr.select1(SQL_POOL, mappers::mapApplicationState,
                f("select *, %1$s.dbo_version() as dbo_ver, %1$s.dbo_type() as dbo_type from %1$s.DBO_APPLICATION_STATE where app_code like $1::varchar"),
                appCode
        ).getResult();
    }

    public Optional<? extends NodeState> getNodeState(String nodeId) {
        return qr.select1(SQL_POOL, mappers::mapNodeState,
                f("select * from %1$s.DBO_NODE_STATE where node_id like $1::varchar"),
                nodeId
        ).getResult();
    }

    String f(String pattern) {
        return String.format(pattern, DB_SCHEMA);
    }

    public TestcontainersTestContext() {

        var network = Network.NetworkImpl.builder().createNetworkCmdModifier(
                        m -> m.withOptions(Map.of("com.docker.network.bridge.host_binding_ipv4", "127.0.0.1")))
                .build();

        var mnEnvironments = System.getenv("MICRONAUT_ENVIRONMENTS");
        boolean useTestContainers = mnEnvironments == null || !mnEnvironments.contains("realdbtest");
        if (useTestContainers) {
            EVENTING_CONTAINER = new RedpandaContainer("docker.redpanda.com/vectorized/redpanda:v22.2.1");
            EVENTING_CONTAINER.withNetwork(network);
            EVENTING_CONTAINER.waitingFor(Wait.forListeningPort());
            EVENTING_CONTAINER.start();
            log.info("redpanda servers: {}", EVENTING_CONTAINER.getBootstrapServers());
            REDPANDA_HOST = EVENTING_CONTAINER.getBootstrapServers();

            DB_CONTAINER = new PostgreSQLContainer("postgres:14.2-alpine").withDatabaseName(DB)
                .withUsername(DB_USER).withPassword(DB_PASSWORD);
            DB_CONTAINER.withNetwork(network);
            DB_CONTAINER.waitingFor(Wait.forListeningPort());
            DB_CONTAINER.start();
            DB_CONTAINER.getFirstMappedPort();
            DB_HOST = DB_CONTAINER.getHost();
            DB_JDBC_URL = DB_CONTAINER.getJdbcUrl();
            log.info("jdbc url: {}", DB_JDBC_URL);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            DB_PORT = DB_CONTAINER.getFirstMappedPort();
        } else {
            DB_PORT = Integer.parseInt(getRequiredSystemProperty("POSTGRES_PORT"));
            DB_HOST = getRequiredSystemProperty("POSTGRES_HOST");
            DB_USER = getRequiredSystemProperty("POSTGRES_USER");
            DB_PASSWORD = getRequiredSystemProperty("POSTGRES_PASSWORD");
            DB = getRequiredSystemProperty("POSTGRES_DB");
            DB_JDBC_URL = String.join("","jdbc:postgresql://",DB_HOST,":","" + DB_PORT, "/", DB, "?loggerLevel=OFF");
            REDPANDA_HOST = getRequiredSystemProperty("REDPANDA_HOSTS");
            // PLAINTEXT://localhost:53476
        }

        this.eventingFactory = new KafkaEventingFactory(new SimpleEventConfiguration()
            .setHosts(List.of(REDPANDA_HOST))
            .setGlobalErrorTopic("io.dbo-test.global-errors.updated"));

        PgConnectOptions connectOptions =
                new PgConnectOptions().setPort(DB_PORT)
                        .setHost(DB_HOST).setDatabase(DB)
                        .setUser(DB_USER).setPassword(DB_PASSWORD);
        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);
        Vertx vertx = Vertx.vertx();
//    SQL_POOL = PgPool.pool(vertx, connectOptions.setPipeliningLimit(1), poolOptions);
        SQL_POOL = PgPool.pool(vertx, connectOptions, poolOptions);
        try {
            SQL_POOL.getConnection()
                    .compose(connection ->
                                    connection
                                            .query("CREATE SCHEMA IF NOT EXISTS " + DB_SCHEMA)
                                            .execute()
                            //        .eventually(v -> connection.close())
                    )
                    .onSuccess(o -> log.info("schema <{}> created ...", DB_SCHEMA))
                    .toCompletionStage().toCompletableFuture().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        this.nodeSyncqueue = new NodeSyncQueue(connectOptions, objectMapper, Optional.of(vertx));

        this.applicationContext = new DBOApplicationContext(PostgresTestDatabase::new, this.eventingFactory)
                .setDomainMessageQueue(nodeSyncqueue)
                .setObjectMapper(objectMapper)
                .setDbConnectionPool(SQL_POOL)
                .setDomainMessenger(nodeSyncqueue);
        log.info("Ready for testing ;) ...");
    }

    private String getRequiredSystemProperty(String key) {
        var val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable - " + key);
        }
        return val;
    }


    @Override
    public void close() {
        log.info("Shutting down ...");
        qr.close();
        createdNodes.forEach(DbObjects::close);
        nodeSyncqueue.close();
        SQL_POOL.close();
        sleepFor(500);
        if (EVENTING_CONTAINER != null) {
            EVENTING_CONTAINER.close();
        }
        if (DB_CONTAINER != null) {
            DB_CONTAINER.close();
        }
        log.info("fin");
    }

    public void sleepFor(long milliseconds) {
        try {
            log.debug("sleeping for {}ms", milliseconds);
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void makeOutDated(DbObjects dbo) {
        log.info(">>> making node <{}> outdated ...", dbo.getNodeId());
        qr.select1(SQL_POOL, mappers::mapNodeState,
                f("update %1$s.DBO_NODE_STATE set node_heartbeat = 0 where node_id like $1::varchar returning *"),
                dbo.getNodeId()
        ).getResult();
    }

    public void synchronizeMasterSwitch(DbObjects oldMaster, DbObjects newMaster) {
        newMaster.synchronize(); // maybe declares its newer version
        oldMaster.synchronize(); // maybe accepts the node with newer state and promotes it
        newMaster.synchronize(); // maybe accepts its newer master state. ad applies new master changes
        oldMaster.synchronize(); // maybe accepts status update made by new master update
    }

    public Collection<StorageEvent> getUnprocessedDomainEvents(DbObjects dbo, String domainName) {
        var node = ((TestDbObjects) dbo).getNode();
        node.verifyDomainName(domainName);
        return new PostgresTestDatabase(node, this.testStateUpdater).selectEvents(
                dbo.getNodeId(), domainName);
    }

    public static void print(Collection<StorageEvent> storageEvents) {
        var size = storageEvents != null ? storageEvents.size() : 0;
        var sb = new StringBuffer(String.format("domain events (%s):\n", size));
        if (storageEvents != null) {
            storageEvents.forEach(de -> sb
                    .append("\n  event id: <")
                    .append(de.getEventId())
                    .append(">; type: <")
                    .append(de.getEventType())
                    .append(">; id/version/type: <")
                    .append(
                            String.format("%s/%s/%s", de.getPayloadInfo().getId(), de.getPayloadInfo().getVersion(),
                                    de.getPayloadInfo().getType()))
                    .append(">; processedAt: ")
                    .append(Optional.ofNullable(de.getProcessedAt()).map(Object::toString).orElse("-"))
                    .append("\n    ")
                    .append(objectMapper.deserialize(Map.class, de.getPayloadInfo().getPayload()))
            );
        }
        log.info("{}", sb);
    }

    private static class TestContextFakeStateUpdater implements StateUpdater {

        @Override
        public void setNodeState(SyncedNodeState nodeState) {
            log.warn(
                    "node state in db is manipulated by the test context without informing the DBO system...");
        }

        @Override
        public void setApplicationState(ApplicationState applicationState) {
            log.warn(
                    "application state in db is manipulated by the test context without informing the DBO system...");
        }
    }
}
