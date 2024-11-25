package io.dbobjects.nodesync;

import java.util.Optional;

public interface DomainStateProvider {
    Optional<NodeSyncState> getDomainState(String domainName);
}
