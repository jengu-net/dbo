package io.dbobjects.db;

import io.dbobjects.ApplicationState;
import io.dbobjects.Domain;
import io.dbobjects.nodesync.NodeSyncState;
import io.dbobjects.parallel.NodeContext;
import io.dbobjects.parallel.NodeState;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.StorageEvent;
import io.dbobjects.storage.StorageObject;

import java.util.Collection;
import java.util.Optional;

/**
 * Sample plantuml diagram
 * [plantuml, target=erd, format=png]
 * ----
 * activate node
 * <p>alt node startup
 * node -> database: initApplicationState
 * activate database
 * database -> database: createDboStructures
 * database -> database: initializeApplicationState
 * database -> database: initializeNodeState
 * node <-- database: NodeContext(appState, nodeState)
 * deactivate database
 * end
 * <p>loop node running
 * node -> database: checkApplicationState
 * activate database
 * database -> database: switch to master node if applicable
 * database -> database: switch node to passive if version is not applicable
 * node <-- database: NodeContext(appState, nodeState)
 * deactivate database
 * end
 * <p>alt node shutdown
 * node -> database: shutDownNode
 * activate database
 * database -> database: remove master node flag in applicationState if master node
 * database -> database: remove node status record
 * node <-- database: NodeContext(appState, nodeState)
 * deactivate database
 * end
 * <p>deactivate node
 * ----
 * <p>* [x] test1
 * * [ ] test1
 * <p>
 * Sample comments that include `source code`.
 */
public interface Database {

    /**
     * <pre>
     *   Levels:
     *   INFO - system status changes
     *   DEBUG - queries, data mappings, result set meta, interval based statistics
     *   TRACE - not limited
     * </pre>
     */
    String LOGGER_NAME = "io.dbobjects.database";

    String getImplementationType();

    int getImplementationVersion();

    void initDomains();

    /**
     * This function initializes DBO at database if necessary and retrieves the initial state
     * of the application and the node.
     * Because the initialization might be expensive operation it should be run once when the
     * application node starts.
     * For regular state update please see the checkNodeState operation
     */
    void initApplicationState();

    void syncApplicationState(Long maxAge, Optional<String> maybeNewMaster);

    Collection<NodeState> getNodes();

    Optional<ApplicationState> getApplicationState();

    StorageEvent acceptEvent(String domain,
                             StorageEvent.EventType eventType,
                             PayloadInfo payloadInfo) throws DatabaseException;

    // TODO: vvv deprecated vvv

    Collection<StorageEvent> selectEvents(String nodeId, String domain) throws DatabaseException;

    boolean processEvents(String domain, long batchSize) throws DatabaseException;

    Optional<StorageObject> get(String nodeId, Domain domain, SearchCriteria criteria)
            throws DatabaseException;

    Collection<StorageObject> select(String nodeId, Domain domain, SearchCriteria criteria)
            throws DatabaseException;

    SearchCriteria createCriteria(Domain domain, String objectType, int objectVersion)
            throws DatabaseException;

    Optional<NodeSyncState> getDomainState(NodeContext nodeContext, String domainName)
            throws DatabaseException;

}
