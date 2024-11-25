package io.dbobjects;

import io.dbobjects.db.Database;
import io.dbobjects.db.DomainState;
import io.dbobjects.domain.MasterNodeEventHandler;
import io.dbobjects.nodesync.NodeSyncState;
import io.dbobjects.parallel.NodeContext;
import io.dbobjects.util.Ticker;
import io.vertx.sqlclient.Pool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@ToString(callSuper = true)
@Slf4j
@RequiredArgsConstructor
public class Domain extends DomainConfiguration
        implements AutoCloseable,
        MasterNodeEventHandler.MasterNodeProcessor {

    @Getter
    boolean dbIncompatible;
    @Getter
    boolean inService;

    @Setter
    private Ticker ticker;

    //@Setter
    @Getter
    @ToString.Exclude
    private Pool dbConnectionPool;

    @Setter(AccessLevel.PUBLIC)
    private Database database;

    private final NodeContext nodeContext;

    @ToString.Exclude
    final AtomicBoolean shutdownAsked = new AtomicBoolean(false);

    @ToString.Exclude
    Optional<MasterNodeEventHandler> maybeMasterNodeEventHandler = Optional.empty();

    private Logger auditLogger;
    private String auditLoggerName = "io.dbobjects.audit";

    public void start() {
        maybeMasterNodeEventHandler =
                MasterNodeEventHandler.startIfNeeded(this, this, this.shutdownAsked, nodeContext.getDomainObjectsProcessingBatchSize());
    }

    public static Domain of(DomainConfiguration conf, NodeContext nodeCtx) {
        Domain d = new Domain(nodeCtx);
        d.setName(conf.getName());
        d.setDataSourceName(conf.getDataSourceName());
        d.setDbJdbcUrl(conf.getDbJdbcUrl());
        d.setDbJdbcUser(conf.getDbJdbcUser());
        d.setDbJdbcPassword(conf.getDbJdbcPassword());
        d.setEnableLocalEventHandler(conf.isEnableLocalEventHandler());
        d.setEnableMasterNodeEventHandler(conf.isEnableMasterNodeEventHandler());
        d.setDbType(conf.getDbType());
        d.auditLoggerName =
                "io.dbobjects.audit.node-" + nodeCtx.getNodeState().getNodeId() + ".domain-" + d.getName();
        d.dbConnectionPool = nodeCtx.getDbConnectionPool();
        return d;
    }

    private boolean checkDbIncompatible(DomainState state) {
        if (!this.dbIncompatible) {
            this.dbIncompatible = state != null && state.getDomainVersion() > 0; //getMaxVersion();
        }
        return this.dbIncompatible;
    }

    public Optional<DomainState> processDomainState() {
        getLogger().debug("processing the state ...");
        if (nodeContext.getDomainState(getName()).isEmpty()) {
            getLogger().debug("domain is not registered. Skip processing.");
            return Optional.empty();
        }
        if (this.dbIncompatible) {
            getLogger().debug("Domain is not compatible with database. Skip processing.");
            return Optional.empty();
        }

        // TODO: following must be executed within transaction
        var state = loadState();
        if (!inService) {
            getLogger().debug("trying to get domain into service ...");

        }

        // end of to do
        return state.map(NodeSyncState::getDomainState);
    }

    private Optional<NodeSyncState> loadState() {
        return this.database.getDomainState(nodeContext, getName());
    }

    public Optional<NodeSyncState> getState() {
        return nodeContext.getDomainState(getName());
    }

    @Override
    public void close() {
        shutdownAsked.set(true);
        maybeMasterNodeEventHandler.ifPresent(MasterNodeEventHandler::close);
    }

    @Override
    public boolean processEvents(String domainName) {
        return Ticker.timed(ticker, "processEvents",
                () -> database.processEvents(this.getName(), this.nodeContext.getDomainObjectsProcessingBatchSize()));
    }

    protected final Logger getLogger() {
        if (this.auditLogger == null) {
            this.auditLogger =
                    LoggerFactory.getLogger(auditLoggerName);
        }
        return this.auditLogger;
    }
}
