package io.dbobjects.db;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Collection;
import java.util.Optional;

/**
 * Specifies the domain state in shared storage
 */
@Getter
@Setter
@ToString
public class DomainState {
    private String domainName;
    /**
     * numeric version of the domain. Newer version is considered as not backward compatible to
     * the previous version
     */
    private Integer domainVersion;
    private Long masterNodeHeartBeat;
    private String masterNode;
    private String updateKey;
    /**
     * Defines active synchronized domain task for specified domain.
     */
    private SynchronizedDomainTask synchronizedDomainTask;
    private Collection<NodeState> startedNodes;

    /**
     * If the domain is blocked or not
     */
    @JsonIgnore
    public boolean isDomainBlocked() {
        // TODO: check for too old tasks
        return getSynchronizedDomainTask() != null && getSynchronizedDomainTask().isDomainBlocked();
    }

    public Optional<NodeState> getStateForNode(String nodeId) {
        if (getStartedNodes() == null || nodeId == null) {
            return Optional.empty();
        }
        return getStartedNodes().stream().filter(sn -> nodeId.equals(sn.getNodeId())).findFirst();
    }

    /**
     * Verifies if master node is alive or not.
     * @param maximumHeartBeatIntervalInS maximum heartbeat interval in seconds
     * @return true if the heartBeat is not too old
     */
    @JsonIgnore
    private boolean isMasterNodeAlive(long maximumHeartBeatIntervalInS) {
        return getMasterNodeHeartBeat() > System.currentTimeMillis() - (maximumHeartBeatIntervalInS * 1000);
    }

    @JsonIgnore
    public boolean isNodeAlive(String nodeId) {
        return getStateForNode(nodeId).isPresent();
    }

    /**
     * checks if given node id is a master node id
     * Note: this method does not check if master node is alive.
     *
     * @param masterNodeId master node id to be checked...
     * @return true if there is active master node with given id.
     * @see DomainState#isMasterNodeAlive(long)
     */
    @JsonIgnore
    public boolean isMasterNode(String masterNodeId) {
        return masterNodeId != null && masterNodeId.equals(getMasterNode());
    }

    /**
     * Specifies the synchronized domain task within DomainState
     */
    @Getter
    @Setter
    @ToString
    public static class SynchronizedDomainTask {
        private String id;
        private long startedAt;
        private boolean domainBlocked;
    }

    /**
     * Specifies the Node
     */
    @Getter
    @Setter
    @ToString
    public static class NodeState {
        private String nodeId;
        private long startedAt;
        private long domainVersion;
        private boolean active;
        private long heartBeat;
    }
}
