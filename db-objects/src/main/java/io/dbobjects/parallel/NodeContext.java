package io.dbobjects.parallel;

import io.dbobjects.ApplicationState;
import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.ObjectMapper;
import io.dbobjects.nodesync.DomainStateProvider;
import io.dbobjects.nodesync.NodeStateProvider;
import io.dbobjects.storage.Storage;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Optional;

public interface NodeContext extends DomainStateProvider {
    NodeStateProvider getNodeStateProvider();

    Pool getDbConnectionPool();

    NodeState getNodeState();

    ApplicationState getApplicationState();

    DBOApplicationConfig getApplicationConfig();

    Logger getAuditLogger();

    NodeState switchTo(NodeState.StatusCode newState, String msg);

    void switchTo(NodeState.StatusCode newState);

    ObjectMapper getObjectMapper();

    long getDomainObjectsProcessingBatchSize();

    Collection<String> getDomainNames();

    Optional<Storage<?>> getStorage(String storageKey);
}
