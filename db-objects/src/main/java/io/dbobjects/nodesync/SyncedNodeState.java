package io.dbobjects.nodesync;

import io.dbobjects.parallel.NodeState;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
public class SyncedNodeState implements NodeState {

    public SyncedNodeState() {
        NodeState.StatusCode.verifySetup();
    }


    @Setter
    @Getter
    private int appVersion;
    @Setter
    @Getter
    private int dboVersion;
    @Setter
    @Getter
    private String appCode;
    @Setter
    @Getter
    private String nodeId;
    @Setter
    @Getter
    private String dbType;
    @Setter
    @Getter
    private Long nodeHeartbeat = System.currentTimeMillis();
    @Getter
    @Setter
    private NodeState.StatusCode nodeStatusCode = NodeState.StatusCode.STOPPED;
    @Setter
    @Getter
    @ToString.Exclude
    private String knownAppStateKey;
    @ToString.Exclude
    private NodeState.NodeStateAttributes attributes;
}
