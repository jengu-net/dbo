package io.dbobjects.nodesync;

import java.util.Optional;

public interface NodeStateProvider {
    Optional<NodeSyncState> getSyncStateForDomain(String domainName);

    boolean isMasterNodeForDomain(String domainName);

    Optional<String> getMasterNodeForDomain(String domainName);
}
