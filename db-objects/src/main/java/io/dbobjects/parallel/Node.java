package io.dbobjects.parallel;

import io.dbobjects.ApplicationState;
import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.DBOApplicationContext;
import io.dbobjects.Domain;
import io.dbobjects.DomainConfiguration;
import io.dbobjects.ObjectMapper;
import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.Database;
import io.dbobjects.nodesync.DomainStateProvider;
import io.dbobjects.nodesync.NodeStateProvider;
import io.dbobjects.nodesync.NodeStateSynchronizer;
import io.dbobjects.nodesync.NodeSyncState;
import io.dbobjects.nodesync.SyncedNodeState;
import io.dbobjects.nodesync.SynchronizedDomainEventReceiver;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.Storage;
import io.dbobjects.storage.StorageConfig;
import io.dbobjects.storage.StorageEvent;
import io.dbobjects.util.RandomIdUtil;
import io.dbobjects.util.Ticker;
import io.vertx.sqlclient.Pool;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.aventrix.jnanoid.jnanoid.NanoIdUtils.randomNanoId;

/**
 * Deals with
 * clustering,
 * syncing registered node states between nodes,
 * handling of incoming CRUD events (storing them in db queue) as supernode,
 * handling CRUD events in queue as supernode,
 * upgrading the node version in database (as supernode)
 */
@Slf4j
public class Node
        implements AutoCloseable, StorageConfig, NodeContext, DomainStateProvider,
        StateUpdater {
    @Getter
    private SyncedNodeState nodeState;
    @Getter
    private ApplicationState applicationState;

    private final Database database;
    private final Pool systemDbConnectionPool;
    DBOApplicationConfig appConfig;

    private final Map<String, Domain> domainMap = new HashMap<>();
    private final Map<String, Storage<?>> storageMap = new HashMap<>();

    @Setter
    @Getter
    private ObjectMapper objectMapper;

    @Setter
    private Ticker ticker;

    private final NodeStateSynchronizer nodeSynchronizer;

    private static final Long MAX_INTERVAL = 5000L;

    private Logger auditLogger;

    private boolean syncInProgress = false;
    private boolean shutDownCalled = false;

    public Node(DBOApplicationContext appCtx, DBOApplicationConfig appConfig) {
        if (appConfig.getNodeId() == null) {
            appConfig.setNodeId(RandomIdUtil.randomAlpha(10));
        }
        this.appConfig = appConfig;
        this.objectMapper = appCtx.getObjectMapper();
        //!= null ? appCtx.getObjectMapper() : JsonUtil.getObjectMapper();
        this.database = appCtx.buildDatabase(this, this);
        this.nodeState = NodeState.initialState(appConfig);
        this.applicationState = ApplicationState.initialState(appConfig);
        this.systemDbConnectionPool = appCtx.getDbConnectionPool();
        this.nodeSynchronizer =
                new NodeStateSynchronizer(this, appCtx.getDomainMessenger(), this.database);
    }

    public boolean isMaster() {
        return nodeState.getNodeId().equals(getApplicationState().getMasterNode());
    }

    public void subscribeToDomains(Collection<DomainConfiguration> domainConfigurations) {

        domainConfigurations.forEach(domainConfig -> {
            var domain = Domain.of(domainConfig, this);
            domain.setDatabase(database);

            if (domain.getSchemaName() == null || domain.getSchemaName().isEmpty()) {
                domain.setSchemaName(this.appConfig.getDefaultSchemaName());
            }
            domain.setTicker(this.ticker);

            domainMap.put(domain.getName(), domain);
        });
        database.initDomains();
        domainMap.values().forEach(Domain::start);
    }

    public Collection<String> getDomainNames() {
        return domainMap.keySet();
    }

    @Override
    public Optional<Storage<?>> getStorage(String storageKey) {
        return Optional.ofNullable(storageMap.get(storageKey));
    }

    public void addStorage(Storage<?> storage) {
        if (storage == null) {
            log.warn("ignoring non existing <null> storage");
            return;
        }
        storage.setStorageConfig(this);
        storage.setObjectMapper(getObjectMapper());
        log.debug("registering storage {}", storage.getStorageKey());
        storageMap.put(storage.getStorageKey(), storage);
        getApplicationState().getAttributes().getDomains().add(storage.getDomainName());
    }

    public StorageEvent acceptEvent(String domain, StorageEvent.EventType eventType, PayloadInfo payloadInfo) {
        verifyDomainName(domain);
        return database.acceptEvent(domain, eventType, payloadInfo);
    }

    public void verifyDomainName(String domainName) {
        if (!this.getApplicationState().getAttributes().getDomains().contains(domainName)) {
            throw new IllegalArgumentException("invalid domain name: " + domainName);
        }
    }

    @Override
    public void close() {
        if (!NodeState.StatusCode.STOPPED.equals(getNodeState().getNodeStatusCode())) {
            switchTo(NodeState.StatusCode.STOPPING, "Shutdown called ...");
            shutDownCalled = true;
            nodeSynchronizer.close();
            domainMap.values().forEach(Domain::close);
            if (ticker != null) {
                ticker.close();
            }
            switchTo(NodeState.StatusCode.STOPPED);
            switchToSlave();
            database.syncApplicationState(System.currentTimeMillis() - MAX_INTERVAL, Optional.of(""));
        }
    }

    private void switchToSlave() {
        if (isMaster()) {
            getApplicationState().setMasterNode(null);
            getApplicationState().setAppStatusKey(randomNanoId());
        }
    }

    @Override
    public Domain getDomain(String domainName) {
        return domainMap.get(domainName);
    }

    public Optional<NodeSyncState> getDomainState(String domainName) {
        verifyDomainName(domainName);
        return nodeSynchronizer.getDomainState(domainName);
    }

    public Collection<StorageEvent> selectEvents(String domainName) {
        verifyDomainName(domainName);
        return Ticker.timed(ticker, "selectEvents",
                () -> database.selectEvents(getNodeState().getNodeId(), domainName));
    }

    @Override
    public Database getDatabase() {
        return this.database;
    }

    @Override
    public Pool getDbConnectionPool() {
        return systemDbConnectionPool;
    }

    @Override
    public void setNodeState(SyncedNodeState nodeState) {
        this.nodeState = nodeState;
    }

    @Override
    public void setApplicationState(ApplicationState applicationState) {
        this.applicationState = applicationState;
    }

    @Override
    public DBOApplicationConfig getApplicationConfig() {
        return this.appConfig;
    }

    public NodeStateProvider getNodeStateProvider() {
        return nodeSynchronizer;
    }

    public void synchronize() {
        if (shutDownCalled) {
            return;
        }
        syncInProgress = true;
        getNodeState().setNodeHeartbeat(System.currentTimeMillis());
        //database.syncApplicationState(System.currentTimeMillis() - MAX_INTERVAL, Optional.empty());
        int currentDataAppVer = getApplicationState().getAppVer();
        var currentNodeId = getNodeState().getNodeId();
        var promotedNode = new AtomicReference<String>();
        if (isMaster() && isActive()) {
            log.debug("Running master node tasks ...");
            database.getNodes().stream()
                    .filter(ns -> !ns.getNodeId().equals(currentNodeId)) // not current node
                    .filter(ns -> NodeState.StatusCode.STARTING.equals(ns.getNodeStatusCode())) // starting
                    .filter(ns -> ns.getAppVersion() > currentDataAppVer) // node provides newer app
                    .findFirst()
                    .ifPresent(ns -> {
                        handleFoundNodeWithNewerAppVersion(ns);
                        promotedNode.set(ns.getNodeId());
                    });
            // TODO: select all nodes and inspect if some is starting and has newer app version
            domainMap.values().forEach(d -> d.processEvents(d.getName()));
        }
        database.syncApplicationState(System.currentTimeMillis() - MAX_INTERVAL,
                Optional.ofNullable(promotedNode.get()));

        if (isStarting() && isMaster()) { // promoted to master
            switchTo(NodeState.StatusCode.ACTIVE, "promoted to master ...");
            upgradeDataAppVersion();
            database.syncApplicationState(System.currentTimeMillis() - MAX_INTERVAL,
                    Optional.empty());
        }
        syncInProgress = false;
    }

    private void handleFoundNodeWithNewerAppVersion(NodeState ns) {
        getApplicationState().setMasterNode(ns.getNodeId());
        getApplicationState().calculateAppStatusKey();
        getApplicationState().setBlocked(true);
        switchTo(NodeState.StatusCode.PASSIVE, String.format(
                "Node <%s> has newer app version <%s> than current <%s>. Promoting it to master..",
                ns.getNodeId(), ns.getAppVersion(), getApplicationState().getAppVer()));
        log.info("applicationState: {}", getApplicationState());

    }

    private void upgradeDataAppVersion() {
        int currentDataAppVer = getApplicationState().getAppVer();
        getAuditLogger().info("TODO: upgrade db app version from to <{}>",
                getNodeState().getAppVersion());
        getApplicationState().setBlocked(false);
        getApplicationState().setAppVer(getNodeState().getAppVersion());
    }

    public boolean isActive() {
        return NodeState.StatusCode.ACTIVE.equals(nodeState.getNodeStatusCode());
    }

    public boolean isPassive() {
        return NodeState.StatusCode.PASSIVE.equals(nodeState.getNodeStatusCode());
    }

    public boolean isStarting() {
        return NodeState.StatusCode.STARTING.equals(nodeState.getNodeStatusCode());
    }

    public SynchronizedDomainEventReceiver asNodeSyncEventReceiver() {
        return this.nodeSynchronizer;
    }

    public Logger getAuditLogger() {
        return auditLogger != null ? auditLogger : rebuildLogger();
    }

    private Logger rebuildLogger() {
        this.auditLogger = LoggerFactory.getLogger(
                "dbo.audit.node-" + nodeState.getNodeId() + "-" + nodeState.getNodeStatusCode().name());
        return this.auditLogger;
    }

    public NodeState switchTo(NodeState.StatusCode newState, String msg) {
        if (nodeState.getNodeStatusCode().isTransitionAllowed(newState)) {
            var oldState = nodeState.getNodeStatusCode();
            this.nodeState.setNodeStatusCode(newState);
            msg = msg != null ? ": " + msg : "";
            rebuildLogger();
            if (nodeState.getNodeStatusCode().equals(NodeState.StatusCode.FAILURE)) {
                this.auditLogger.warn(
                        "changing node state: {} -> {} <{}:v.{} on {}:v.{}>{}",
                        oldState.name(), newState.name(), applicationState.getAppCode(),
                        applicationState.getAppVer(), applicationState.getDbType(),
                        applicationState.getDboVer(), msg);
            } else {
                this.auditLogger.info(
                        "changing node state: {} -> {} <{}:v.{} on {}:v.{}>{}",
                        oldState.name(), newState.name(), applicationState.getAppCode(),
                        applicationState.getAppVer(), applicationState.getDbType(),
                        applicationState.getDboVer(), msg);
            }
        } else {
            throw new IllegalStateException(
                    String.format("Can not switch node state form %s to %s. Allowed new states are: <%s>",
                            nodeState.getNodeStatusCode().name(),
                            newState.name(),
                            nodeState.getNodeStatusCode().getAllowedTransitions()));
        }
        return this.nodeState;
    }

    public void makeOutDated() {
        nodeState.setNodeHeartbeat(System.currentTimeMillis() - MAX_INTERVAL - 1);
        database.initApplicationState();
    }

    public void switchTo(NodeState.StatusCode newState) {
        switchTo(newState, null);
    }

    @Override
    public long getDomainObjectsProcessingBatchSize() {
        return appConfig.getDomainObjectProcessingBatchSize();
    }


}
