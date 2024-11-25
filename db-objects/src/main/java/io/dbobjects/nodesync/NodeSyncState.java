package io.dbobjects.nodesync;

import io.dbobjects.db.DomainState;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Getter
@Setter
@Slf4j
public class NodeSyncState {
    private String domain;
    private String masterNode;
    private String updateKey;
    private Collection<SyncedNode> registeredNodes;
    private DomainState domainState;
    private DomainState previousDomainState;

    public boolean isNodeRegistered(String... nodeIds) {
        return registeredNodes != null && nodeIds != null && nodeIds.length > 0
            && registeredNodes.stream().map(SyncedNode::getNode)
            .collect(Collectors.toCollection(HashSet::new))
            .containsAll(List.of(nodeIds));
    }

    public Optional<Long> getDomainVersionForNode(String nodeId) {
        if (registeredNodes == null || nodeId == null) {
            return Optional.empty();
        }
        return registeredNodes.stream().filter(n -> nodeId.equals(n.getNode()))
            .map(SyncedNode::getDomainVersion).findFirst();
    }

    public Optional<SyncedNode> getNodeWithHighestDomainVersion() {
        if (registeredNodes == null) {
            return Optional.empty();
        }
        return registeredNodes.stream().max(Comparator.comparingLong(SyncedNode::getDomainVersion));
    }

    @Getter
    @Setter
    @ToString
    @EqualsAndHashCode
    @NoArgsConstructor
    public static class SyncedNode {
        private String node;
        private long heartbeat;
        private long domainVersion;
    }
}
