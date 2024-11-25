package io.dbobjects.db.postgres;

import io.dbobjects.ApplicationState;
import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.DboProperties;
import io.dbobjects.Domain;
import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.Database;
import io.dbobjects.db.DatabaseException;
import io.dbobjects.db.DomainState;
import io.dbobjects.db.postgres.tx.TxContext;
import io.dbobjects.domain.MasterNodeEventHandler.MasterNodeProcessor.NodeState;
import io.dbobjects.nodesync.DomainMessenger;
import io.dbobjects.nodesync.NodeSyncState;
import io.dbobjects.nodesync.SyncedNodeState;
import io.dbobjects.nodesync.SynchronizedDomainEvent;
import io.dbobjects.parallel.NodeContext;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.Storage;
import io.dbobjects.storage.StorageEvent;
import io.dbobjects.storage.StorageObject;
import io.dbobjects.storage.StorageObjectContext;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.randomNanoId;
import static io.dbobjects.db.postgres.Constants.DBO_TYPE;
import static io.dbobjects.db.postgres.Constants.DBO_VERSION;
import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_VERSION;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_APP_CODE;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_CREATED_AT;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_ID;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_OBJECT_ID;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_OBJECT_TYPE;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_PAYLOAD;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_PROCESSED_AT;
import static io.dbobjects.db.postgres.DomainDbScripts.EVENT_TYPE;
import static io.dbobjects.storage.PayloadInfo.of;

@Slf4j(topic = Database.LOGGER_NAME)
public class PostgresDatabase implements Database, AutoCloseable, DomainMessenger {
    private final QueryRunner qr = new QueryRunner();
    private final Map<String, DomainDbScripts> dbScriptsMap = new HashMap<>();
    private SystemDbScripts systemDbScripts;

    final AtomicBoolean shutdownAsked = new AtomicBoolean(false);

    final NodeContext nodeContext;
    final StateUpdater stateUpdater;

    final Mappers mappers;

    public PostgresDatabase(NodeContext nodeContext, StateUpdater stateUpdater) {
        this.nodeContext = nodeContext;
        this.stateUpdater = stateUpdater;
        this.mappers = new Mappers(nodeContext.getObjectMapper());
    }

    @Override
    public String getImplementationType() {
        return DBO_TYPE;
    }

    @Override
    public int getImplementationVersion() {
        return DBO_VERSION;
    }

    @Override
    public void initApplicationState() {
        fixNodeDboVersion();
        var appState = nodeContext.getApplicationState();
        log.info("initializing database");
        runLiquibase("/db-changelog.xml", "dbo-application");

        // todo: set versionNumbers
        appState.calculateAppStatusKey();
        var systemDbScripts = getSystemDbScripts(nodeContext.getApplicationConfig());
        var mapper = nodeContext.getObjectMapper();
        stateUpdater.setApplicationState(
            qr.select1(nodeContext.getDbConnectionPool(), mappers::mapApplicationState,
                    systemDbScripts.initApplicationState(),
                    appState.getAppCode(),
                    appState.getAppVer(),
                    appState.getMasterNode(),
                    appState.isBlocked(),
                    appState.getAppStatusKey(),
                    mapper.serialize(appState.getAttributes()))
                .getResult().orElseThrow(IllegalStateException::new));
        var nodeState = nodeContext.getNodeState();
        stateUpdater.setNodeState(qr.select1(nodeContext.getDbConnectionPool(), mappers::mapNodeState,
            systemDbScripts.updateNodeState(),
            nodeState.getAppCode(),
            nodeState.getAppVersion(),
            nodeState.getDboVersion(),
            nodeState.getDbType(),
            nodeState.getNodeId(),
            nodeState.getNodeHeartbeat(),
            nodeState.getNodeStatusCode().name(),
            nodeState.getKnownAppStateKey(),
            "{}").getResult().orElseThrow(IllegalStateException::new));
    }

    @Override
    public void initDomains() {
        log.info("initializing database domains: {}", nodeContext.getDomainNames());
        System.setProperty(DboProperties.PROP_DBO_APPLICATION_NAME, nodeContext.getApplicationConfig().getApplicationCode());
        System.setProperty(DboProperties.PROP_DBO_APPLICATION_VERSION, String.valueOf(nodeContext.getApplicationConfig().getApplicationVersion()));
        System.setProperty(DboProperties.PROP_DBO_DOMAINS, String.join(",", nodeContext.getDomainNames()));
        runLiquibase("/db-domain-changelog.xml", "dbo-domains");
    }

    private synchronized void runLiquibase(String changelogFile, String ctx) {
        try (Connection conn = createConnection();
             liquibase.database.Database database = DatabaseFactory.getInstance()
                 .findCorrectDatabaseImplementation(new JdbcConnection(conn));
             Liquibase liquibase = new Liquibase(changelogFile,
                 new ClassLoaderResourceAccessor(), database)) {
            liquibase.update(ctx);
        } catch (SQLException | LiquibaseException e) {
            throw new RuntimeException(e);
        }
    }

    private Connection createConnection() throws SQLException {
        var appConfig = nodeContext.getApplicationConfig();
        Properties props = new Properties();
        props.setProperty("user", appConfig.getDbUsername());
        props.setProperty("password", appConfig.getDbPassword());
        props.setProperty("currentSchema", appConfig.getDefaultSchemaName());
        props.setProperty("autoCommit", "false");
        var connectionUrl = "jdbc:postgresql://" + appConfig.getDbHost() + ":" + appConfig.getDbPort() + "/" + appConfig.getDefaultDatabaseName();
        log.info("... using jdbc url {}", connectionUrl);
        var conn = DriverManager.getConnection(connectionUrl, props);
        conn.setAutoCommit(false);
        return conn;
    }


    @Override
    public void syncApplicationState(Long maxAge, Optional<String> maybeNewMaster) {
        fixNodeDboVersion();
        var systemDbScripts = getSystemDbScripts(nodeContext.getApplicationConfig());
        var appState = nodeContext.getApplicationState();
        var nodeState = nodeContext.getNodeState();
        appState.calculateAppStatusKey();
        //var newAppStatusKey = randomNanoId(); // TODO: build only when really needed
        var maybeNewApplicationState =
            qr.select1(nodeContext.getDbConnectionPool(), mappers::mapApplicationState,
                systemDbScripts.syncApplicationState(),
                nodeState.getNodeId(),
                nodeState.getAppCode(),
                nodeState.getAppVersion(),
                maxAge,
                appState.isBlocked(),
                appState.getAppStatusKey(),
                nodeContext.getObjectMapper().serialize(appState.getAttributes()), // TODO serialize if really needed
                maybeNewMaster.orElse(nodeState.getNodeId())
            ).getResult();
        if (maybeNewApplicationState.isEmpty()) {
            maybeNewApplicationState = Optional.empty();
        }
        if (maybeNewApplicationState.isEmpty()) {
            log.warn("appState was not updated");
        }
        var newAppState = maybeNewApplicationState.orElse(appState);
        stateUpdater.setApplicationState(newAppState);
        if (!newAppState.getAppStatusKey().equals(nodeState.getKnownAppStateKey())) {
            nodeContext.getAuditLogger().info("detected status update for {}", newAppState);
        }
        doSyncNodeState(maxAge);
    }

    private void doSyncNodeState(Long maxAge) {
        var nodeState = nodeContext.getNodeState();
        var newNodeState =
            qr.select1(nodeContext.getDbConnectionPool(), mappers::mapNodeState,
                systemDbScripts.syncNodeState(),
                nodeState.getNodeId(),
                System.currentTimeMillis(),
                nodeState.getNodeStatusCode().name(),
                nodeContext.getApplicationState().getAppStatusKey()
            ).getResult();
        if (newNodeState.isEmpty()) {
            newNodeState = Optional.empty();
        }
        stateUpdater.setNodeState(newNodeState.orElse((SyncedNodeState) nodeState));
    }

    @Override
    public Collection<io.dbobjects.parallel.NodeState> getNodes() {
        return qr.select(nodeContext.getDbConnectionPool(),
            mappers::mapNodeState,
            systemDbScripts.selectNodeStatuses(),
            nodeContext.getApplicationState().getAppCode()
        );
    }

    @Override
    public Optional<ApplicationState> getApplicationState() {
        return qr.select1(nodeContext.getDbConnectionPool(),
            mappers::mapApplicationState, systemDbScripts.selectApplicationState(),
            nodeContext.getApplicationState().getAppCode()
        ).getResult();
    }

    private void fixNodeDboVersion() {
        var nodeState = (SyncedNodeState) nodeContext.getNodeState();
        nodeState.setDboVersion(getImplementationVersion());
        nodeState.setDbType(getImplementationType());
    }

    @Override
    public StorageEvent acceptEvent(String domain, StorageEvent.EventType eventType,
                                    PayloadInfo payloadInfo) throws DatabaseException {
        var dbScripts = getDomainDbScripts(domain);
        var objectId = payloadInfo.getId();
        if (objectId == null) {
            objectId = randomNanoId();
        }

        var objectType = payloadInfo.getType();
        var objectVersion = payloadInfo.getVersion();
        var payload = payloadInfo.getPayload();
        return qr.select1(nodeContext.getDbConnectionPool(), mappers::mapDomainEvent,
            dbScripts.getCreateEventScript(), eventType.name(), objectId, objectType, objectVersion,
            nodeContext.getApplicationConfig().getApplicationCode(),
            payload).getResult().get();
    }

    @Override
    public Collection<StorageEvent> selectEvents(String nodeId, String domain)
        throws DatabaseException {
        var dbScripts = getDomainDbScripts(domain);
        return qr.select(nodeContext.getDbConnectionPool(),
            this::mapToDomainEvent, dbScripts.getSelectUnprocessedEventsScript());

    }

    private static final Collection<StorageEvent.EventType> UPDATE_EVENT_TYPES = List.of(
        StorageEvent.EventType.U);

    private static final AtomicInteger totalProcessedEvents = new AtomicInteger();

    @Override
    public boolean processEvents(String domain, long batchSize)
        throws DatabaseException {
        var dbScripts = getDomainDbScripts(domain);
        var processTimestamp = System.currentTimeMillis();
        var processedEvents = new AtomicInteger();
        qr.createTransaction(this.nodeContext.getDbConnectionPool())
            .selectForApply((ctx, row) -> {
                    var event = this.mapToDomainEvent(row);
                    log.debug("processing event: {}", event);
                    var deleteEventType = StorageEvent.EventType.D.equals(event.getEventType());
                    var timestamp = event.getTimestamp() == null ? null :
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.getTimestamp()), ZoneId.systemDefault());

                    var objectId = event.getPayloadInfo().getId();
                    if (deleteEventType) {
                        runQuery(ctx.getConnection(), dbScripts.getProcessDeleteEventScript(),
                            objectId, event.getPayloadInfo().getType());
                        resetIdentifiers(ctx, domain, event, null);
                        resetReferences(ctx, domain, event, null);
                    } else {
                        // 1) upsert record
                        var storageType = event.getPayloadInfo().getType();
                        var objectMapper = nodeContext.getObjectMapper();
                        var objectContext = nodeContext.getStorage(Storage.buildStorageKey(domain, storageType))
                            .map(s -> s.buildContextFor(event.getPayloadInfo()))
                            .orElse(null);
                        // TODO: serialize object to map
                        var vertxObjectContext = objectContext == null ? null : new JsonObject(objectMapper.serialize(objectContext));
                        runQuery(ctx.getConnection(), dbScripts.getProcessUpdateEventScript(),
                            event.getEventId(),
                            event.getAppCode(),
                            storageType,
                            objectId,
                            event.getPayloadInfo().getVersion(),
                            event.getPayloadInfo().getPayload(),
                            timestamp,
                            vertxObjectContext);

                        resetIdentifiers(ctx, domain, event, objectContext);
                        resetReferences(ctx, domain, event, objectContext);
                    }

                    runQuery(ctx.getConnection(), dbScripts.getSetEventProcessedScript(), event.getEventId());
                    processedEvents.incrementAndGet();
                    return event;
                },
                dbScripts.getSelectUnprocessedEventsScript())
            .forEach(r -> log.info("r: {}", r));
        totalProcessedEvents.addAndGet(processedEvents.get());
        log.info(">>> {}: {} completed in {}ms. Number of processed events: {} (total: {})", domain, processTimestamp,
            System.currentTimeMillis() - processTimestamp, processedEvents.get(), totalProcessedEvents.get());
        return true;
    }

    private void resetReferences(TxContext.TransactionContext ctx, String domain, StorageEvent event, StorageObjectContext objectContext) {
        var dbScripts = getDomainDbScripts(domain);
        var objectId = event.getPayloadInfo().getId();
        var storageType = event.getPayloadInfo().getType();
        runQuery(ctx.getConnection(), String.join(" ", "delete from", dbScripts.getReferencesTable(), "where owner_object_id = $1"),
            objectId);

        if (objectContext != null && objectContext.getReferences() != null
            && !objectContext.getReferences().isEmpty()) {
            var insert = new PostgresInsertBuilder(dbScripts.getReferencesTable(),
                "app_code", "owner_object_type", "owner_object_id", "ref_type", "ref_object_type", "ref_object_id");
            objectContext.getReferences().forEach(reference -> {
                insert.withNewRow(
                    event.getAppCode(),
                    storageType,
                    objectId,
                    reference.getReferenceType(),
                    reference.getTargetType(),
                    reference.getTargetId());
            });
            if (insert.prepare()) {
                runQuery(ctx.getConnection(), insert.getPreparedQueryString(), insert.getPreparedParameters());
            }
        }
    }

    private void resetIdentifiers(TxContext.TransactionContext ctx,
                                  String domain, StorageEvent event, StorageObjectContext objectContext) {

        // TODO: switch to PostgresInsertBuilder
        var dbScripts = getDomainDbScripts(domain);
        var objectId = event.getPayloadInfo().getId();
        var storageType = event.getPayloadInfo().getType();
        runQuery(ctx.getConnection(), String.join(" ", "delete from", dbScripts.getIdentifiersTable(), "where object_id = $1"),
            objectId);
        if (objectContext != null && objectContext.getIdentifiers() != null
            && !objectContext.getIdentifiers().isEmpty()) {
            var insertIdentifiersPrefix = String.join(" ",
                "insert into",
                dbScripts.getIdentifiersTable(),
                "(app_code, object_type, object_id, system, value) values");
            var insertIdentifiersSuffix = ";";
            var queryJoiner = new StringJoiner(" ");
            var values = new ArrayList<Object>(5 * objectContext.getIdentifiers().size());
            final var maybeComma = new AtomicBoolean(false);
            final var parNum = new AtomicInteger(0);
            objectContext.getIdentifiers().forEach(identifier -> {
                if (!maybeComma.get()) {
                    maybeComma.set(true); // only first row
                } else {
                    queryJoiner.add(","); // all other rows
                }
                queryJoiner.add("(")
                    .add(nextPar(parNum)).add(",")
                    .add(nextPar(parNum)).add(",")
                    .add(nextPar(parNum)).add(",")
                    .add(nextPar(parNum)).add(",")
                    .add(nextPar(parNum));
                queryJoiner.add(")");
                values.add(event.getAppCode());
                values.add(storageType);
                values.add(objectId);
                values.add(identifier.getSystem());
                values.add(identifier.getValue());
            });
            var query = String.join(" ", insertIdentifiersPrefix, queryJoiner.toString(), insertIdentifiersSuffix);
            runQuery(ctx.getConnection(), query, values.toArray());
        }
    }

    private String nextPar(AtomicInteger parNum) {
        return "$" + parNum.incrementAndGet();
    }

    private void runQuery(SqlConnection conn, String sql, Object... params) {
        conn.preparedQuery(sql)
            .execute(Tuple.from(params))
            .onFailure(err -> {
                log.error("ERROR: {}\n>>> SQL: {}\n>>> WITH PARAMS: {}", err.getMessage(), sql,
                    nodeContext.getObjectMapper().serialize(params));
                err.printStackTrace();
                // Handle failure
                //ctx.getProcessPromise().fail(err);
            });
    }

    @Override
    public Optional<StorageObject> get(String nodeId, Domain domain, SearchCriteria criteria)
        throws DatabaseException {
        var pgCriteria = (PostgresQuerySearchCriteria) criteria;
        pgCriteria.prepare();
        return qr.select1(this.nodeContext.getDbConnectionPool(),
            mappers::mapToDomainObject,
            pgCriteria.getPreparedQueryString(),
            pgCriteria.getPreparedParameters()).getResult();
    }

    @Override
    public Collection<StorageObject> select(String nodeId, Domain domain, SearchCriteria criteria)
        throws DatabaseException {
        var pgCriteria = (PostgresQuerySearchCriteria) criteria;
        pgCriteria.prepare();
        var result = qr.select(this.nodeContext.getDbConnectionPool(),
            mappers::mapToDomainObject,
            pgCriteria.getPreparedQueryString(),
            pgCriteria.getPreparedParameters());
        if (pgCriteria.isWithReferences()) {
            var resultMap = result.stream().collect(Collectors.toMap(o -> o.getPayloadInfo().getId(), o -> o));

            var refCriteria = createReferencedObjectsCriteria(domain);
            resultMap.keySet().forEach(id -> refCriteria.withFieldValueIn("owner_object_id", id));
            refCriteria.prepare();
            var refResult = qr.select(this.nodeContext.getDbConnectionPool(),
                mappers::mapReferencedStorageObject,
                refCriteria.getPreparedQueryString(),
                refCriteria.getPreparedParameters());
            refResult.forEach(r -> resultMap.get(r.getReference().getOwnerId()).addReferencedObject(r));
        }
        return result;
    }

    @Override
    public SearchCriteria createCriteria(Domain domain, String objectType, int objectVersion) {
        var dbScripts = getDomainDbScripts(domain.getName());
        return new PostgresQuerySearchCriteria(dbScripts.getSelectObjectsScript(), domain.getName(),
            objectType, objectVersion, nodeContext.getObjectMapper());
    }

    private PostgresQuerySearchCriteria createReferencedObjectsCriteria(Domain domain) {
        var dbScripts = getDomainDbScripts(domain.getName());
        return new PostgresQuerySearchCriteria(dbScripts.getSelectReferencedObjectsScript(), domain.getName(),
            null, -1, nodeContext.getObjectMapper());
    }

    @Override
    public Optional<NodeSyncState> getDomainState(NodeContext nodeContext, String domainName)
        throws DatabaseException {
        var systemScripts = getSystemDbScripts(nodeContext.getApplicationConfig());
        return qr.select1(nodeContext.getDbConnectionPool(),
            row -> mapNodeSyncState(nodeContext, row),
            systemScripts.selectDomainStateScript(), domainName).getResult();
    }

    private NodeSyncState mapNodeSyncState(NodeContext nodeContext, Row row) {
        var mapper = nodeContext.getObjectMapper();
        return new NodeSyncState()
            .setDomain(row.getString("domain_name"))
            .setMasterNode(row.getString("master_node_id"))
            .setRegisteredNodes(
                nodeContext.getObjectMapper().fromJsonArrayString(row.getJsonArray("active_nodes").encode(),
                    NodeSyncState.SyncedNode.class))
            .setUpdateKey(row.getString("update_key"))
            .setDomainState(
                mapper.deserialize(DomainState.class, row.getValue("domain_state").toString()))
            .setPreviousDomainState(
                mapper.deserialize(DomainState.class, row.getString("prev_domain_state"))
            );
    }

    private StorageEvent mapToDomainEvent(Row rs) {
        return new StorageEvent().setPayloadInfo(
                of(rs.getString(EVENT_OBJECT_ID), rs.getString(EVENT_OBJECT_TYPE),
                    rs.getInteger(DATA_OBJECT_VERSION),
                    Mappers.parseBuffer(rs, EVENT_PAYLOAD))

            ).setEventId(rs.getBigDecimal(EVENT_ID))
            .setEventType(StorageEvent.EventType.valueOf(rs.getString(EVENT_TYPE)))
            .setTimestamp(toTimestamp(rs.getOffsetDateTime(EVENT_CREATED_AT)))
            .setProcessedAt(toTimestamp(rs.getOffsetDateTime(EVENT_PROCESSED_AT)))
            .setAppCode(rs.getString(EVENT_APP_CODE))
            ;
    }

    private Long toTimestamp(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toInstant().toEpochMilli();
    }

    private NodeState mapToNodeState(String currentNodeId,
                                     String masterNodeId,
                                     boolean hasMoreRecords) {
        log.trace("cur: {}, master: {}, hasMore: {}", currentNodeId, masterNodeId, hasMoreRecords);
        return currentNodeId.equals(masterNodeId) ? hasMoreRecords ? NodeState.MASTER_AND_HAS_MORE_EVENTS :
            NodeState.MASTER_AND_NO_MORE_EVENTS : NodeState.SLAVE;
    }

    @Override
    public void close() throws Exception {
        log.info("shutting down database ...");
        shutdownAsked.set(true);
        qr.close();
    }

    private DomainDbScripts getDomainDbScripts(String domain) {
        var result = dbScriptsMap.get(domain);
        if (result == null) {
            result = DomainDbScripts.of(this.nodeContext.getApplicationConfig(), domain);
            dbScriptsMap.put(domain, result);
        }
        return result;
    }

    private SystemDbScripts getSystemDbScripts(DBOApplicationConfig cfg) {
        if (this.systemDbScripts == null) {
            this.systemDbScripts = SystemDbScripts.of(cfg);
        }
        return systemDbScripts;
    }

    @Override
    public boolean sendSynchronizedDomainEvent(SynchronizedDomainEvent domainEvent) {
        return false;
    }
}
