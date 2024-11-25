package io.dbobjects.nodesync;

import io.dbobjects.Domain;
import io.dbobjects.db.Database;
import io.dbobjects.parallel.NodeContext;
import io.dbobjects.parallel.NodeState;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class NodeStateSynchronizer implements AutoCloseable, DomainStateProvider,
    NodeStateProvider, SynchronizedDomainEventReceiver {

    private final NodeContext nodeContext;
    private final Optional<DomainMessenger> maybeDomainMessenger;
    private final Map<String, Domain> domainMap = new HashMap<>();
    private final Map<String, NodeSyncState> domainStateMap = new HashMap<>();
    private final Map<String, String> masterNodeMap = new HashMap<>();

    private final Database database;

    private static final String NOTE = "\uD83D\uDD36";
    private static final String ANSI_HIGHLIGHTED = "\u001B[39;1m";
    private static final String ANSI_RESET = "\u001B[0m";

    public NodeStateSynchronizer(NodeContext nodeContext,
                                 Optional<DomainMessenger> maybeDomainMessenger,
                                 Database database) {
        this.nodeContext = nodeContext;
        this.maybeDomainMessenger = maybeDomainMessenger;
        this.database = database;
        nodeContext.switchTo(NodeState.StatusCode.STARTING, "initializing node ...");

        if (this.database == null) {
            nodeContext.switchTo(NodeState.StatusCode.FAILURE, "database can not be null");
            throw new RuntimeException("database can not be null");
        }
        if (nodeContext.getApplicationConfig() == null) {
            nodeContext
                .switchTo(NodeState.StatusCode.FAILURE, "applicationConfig can not be null");
            throw new RuntimeException("nodeConfig can not be null");
        }
        if (nodeContext.getDbConnectionPool() == null) {
            nodeContext
                .switchTo(NodeState.StatusCode.FAILURE, "system datasource can not be null");
            throw new RuntimeException("system datasource can not be null");
        }
        this.database.initApplicationState();
        printNodeState();
        printApplicationState();
        if (isDataVersionAcceptable()) {
            nodeContext.switchTo(NodeState.StatusCode.ACTIVE);
        } else if (isDataVersionUpdatePossible()) {
            nodeContext.switchTo(NodeState.StatusCode.STARTING, "waiting for master node to promote ...");
        } else {
            nodeContext.switchTo(NodeState.StatusCode.PASSIVE, "switched to passive by having too old version of app ...");
        }
    }

    public boolean isDataVersionUpdatePossible() {
        var as = nodeContext.getApplicationState();
        var ns = nodeContext.getNodeState();
        return as.getDboVer() == ns.getDboVersion() && as.getAppVer() < ns.getAppVersion()
            && as.getDbType().equals(ns.getDbType());
    }

    public boolean isDataVersionAcceptable() {
        var as = nodeContext.getApplicationState();
        var ns = nodeContext.getNodeState();
        return as.getDboVer() == ns.getDboVersion() && as.getAppVer() == ns.getAppVersion()
            && as.getDbType().equals(ns.getDbType());
    }

    public void printNodeState() {
        var al = nodeContext.getAuditLogger();
        var ns = nodeContext.getNodeState();
        al.info("{}NODE:{}", ANSI_HIGHLIGHTED, ANSI_RESET);
        al.info("{} Node ID: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, ns.getNodeId(), ANSI_RESET);
        al.info("{} Connected to application: <{}{}{}> version: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, ns.getAppCode(), ANSI_RESET,
            ANSI_HIGHLIGHTED, ns.getAppVersion(), ANSI_RESET);
        al.info("{} Uses DBO type: <{}{}{}> version: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, ns.getDbType(), ANSI_RESET,
            ANSI_HIGHLIGHTED, ns.getDboVersion(), ANSI_RESET);
        al.info("{} Known app status key: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, ns.getKnownAppStateKey(), ANSI_RESET);
    }

    public void printApplicationState() {
        var al = nodeContext.getAuditLogger();
        var as = nodeContext.getApplicationState();
        al.info("{}APPLICATION:{}", ANSI_HIGHLIGHTED, ANSI_RESET);
        al.info("{} Application code: <{}{}{}> version: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, as.getAppCode(), ANSI_RESET,
            ANSI_HIGHLIGHTED, as.getAppVer(), ANSI_RESET);
        al.info("{} Supported DBO type: <{}{}{}> version: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, as.getDbType(), ANSI_RESET,
            ANSI_HIGHLIGHTED, as.getDboVer(), ANSI_RESET);
        al.info("{} Master node: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, as.getMasterNode(), ANSI_RESET);
        al.info("{} Status key: <{}{}{}>", NOTE,
            ANSI_HIGHLIGHTED, as.getAppStatusKey(), ANSI_RESET);
    }

    public void putState(NodeSyncState nodeSyncState) {
        if (nodeSyncState == null) {
            return;
        }
        var oldState = getDomainState(nodeSyncState.getDomain()).orElse(null);
        domainStateMap.put(nodeSyncState.getDomain(), nodeSyncState);
        masterNodeMap.put(nodeSyncState.getDomain(), nodeSyncState.getMasterNode());
        if (oldState != null && oldState.getMasterNode().equals(nodeContext.getNodeState().getNodeId())
            && !oldState.getUpdateKey().equals(nodeSyncState.getUpdateKey())) {
            sendSynchronizedDomainEvent(new SynchronizedDomainEvent()
                .setRecipient(SynchronizedDomainEvent.Recipient.SLAVE_NODES)
                .setId(nodeSyncState.getUpdateKey())
                .setDomain(nodeSyncState.getDomain())
                .setDomainState(nodeSyncState.getDomainState())
                .setEventType(SynchronizedDomainEvent.EventType.STATE_UPDATED));
        }
    }

    private void sendSynchronizedDomainEvent(SynchronizedDomainEvent event) {
        event.setSenderNode(nodeContext.getNodeState().getNodeId());
        event.setTimestamp(System.currentTimeMillis());
        getAuditLogger().debug(">>> to: <{}>; event: <{}:{}:{}>",
            nvl(event.getRecipient(), "nobody"),
            event.getDomain(),
            event.getEventType().toString(),
            event.getId());
        maybeDomainMessenger.ifPresent(messenger -> messenger.sendSynchronizedDomainEvent(event));
    }

    private Object nvl(Object s1, Object s2) {
        return s1 != null ? s1 : s2;
    }

    public Optional<NodeSyncState> getSyncStateForDomain(String domainName) {
        return Optional.ofNullable(domainStateMap.get(domainName));
    }

    public Optional<String> getMasterNodeForDomain(String domainName) {
        return Optional.ofNullable(masterNodeMap.get(domainName));
    }

    public boolean isMasterNodeForDomain(String domainName) {
        return nodeContext.getNodeState().getNodeId()
            .equals(getMasterNodeForDomain(domainName).orElse(null));
    }

    public void registerDomain(Domain domain, NodeSyncState nss) {
        this.domainMap.put(domain.getName(), domain);
        this.putState(nss);
    }

    @Override
    public void close() {
    }

    private Logger getAuditLogger() {
        return nodeContext.getAuditLogger();
    }

    @Override
    public Optional<NodeSyncState> getDomainState(String domainName) {
        return Optional.ofNullable(domainStateMap.get(domainName));
    }

    @Override
    public boolean receiveSynchronizedDomainEvent(SynchronizedDomainEvent event) {
        var latency = System.currentTimeMillis() - event.getTimestamp();
        getAuditLogger().debug("<<< from: <{}>; event: <{}:{}:{}>; latency: {}ms",
            event.getSenderNode(),
            event.getDomain(), event.getEventType().toString(), event.getId(), latency);
        switch (event.getEventType()) {
            case STATE_UPDATED -> sendSynchronizedDomainEvent(new SynchronizedDomainEvent()
                .setEventType(SynchronizedDomainEvent.EventType.ACK_STATE_UPDATED)
                .setDomain(event.getDomain())
                .setId(event.getId())
                .setRecipient(SynchronizedDomainEvent.Recipient.MASTER));
            case ACK_STATE_UPDATED -> getAuditLogger().info("TODO: deal with ACK");
            case HEARTBEAT -> getAuditLogger().info("TODO: deal with HEARTBEAT");
            default -> {
                // do nothing
            }
        }
        return true;
    }

    @Override
    public SynchronizedDomainEvent.Recipient geiRecipientTypeForDomain(String domainName) {
        return getSyncStateForDomain(domainName).map(state ->
                state.getMasterNode().equals(nodeContext.getNodeState().getNodeId())
                    ? SynchronizedDomainEvent.Recipient.MASTER : SynchronizedDomainEvent.Recipient.SLAVE_NODES)
            .orElse(SynchronizedDomainEvent.Recipient.INACTIVE_NODES);
    }

}


