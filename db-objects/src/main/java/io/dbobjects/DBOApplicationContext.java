package io.dbobjects;

import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.Database;
import io.dbobjects.nodesync.DomainMessageQueue;
import io.dbobjects.nodesync.DomainMessenger;
import io.dbobjects.parallel.NodeContext;
import io.vertx.sqlclient.Pool;
import lombok.Getter;
import lombok.Setter;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The context where the application is running
 */
@Setter
public class DBOApplicationContext {
    @Getter
    private BiFunction<NodeContext, StateUpdater, Database> databaseBuilder;
    //private final EventingFactory eventingFactory;
    private DomainMessenger domainMessenger;
    private DomainMessageQueue domainMessageQueue;

    @Getter
    private ObjectMapper objectMapper;
    @Getter
    private Pool dbConnectionPool;

    public DBOApplicationContext(BiFunction<NodeContext, StateUpdater, Database> databaseBuilder) {
        this.databaseBuilder = databaseBuilder;
    }

    public Optional<DomainMessenger> getDomainMessenger() {
        return Optional.ofNullable(domainMessenger);
    }

    public Optional<DomainMessageQueue> getDomainMessageQueue() {
        return Optional.ofNullable(domainMessageQueue);
    }

    public Database buildDatabase(NodeContext nodeContext, StateUpdater stateUpdater) {
        return databaseBuilder.apply(nodeContext, stateUpdater);
    }
}
