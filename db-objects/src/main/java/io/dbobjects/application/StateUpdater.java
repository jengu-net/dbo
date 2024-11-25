package io.dbobjects.application;

import io.dbobjects.ApplicationState;
import io.dbobjects.nodesync.SyncedNodeState;

public interface StateUpdater {
    void setNodeState(SyncedNodeState nodeState);

    void setApplicationState(ApplicationState applicationState);
}
