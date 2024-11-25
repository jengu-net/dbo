package io.dbobjects;

import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.Database;
import io.dbobjects.parallel.Node;
import io.dbobjects.parallel.NodeContext;
import io.dbobjects.parallel.NodeState;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.Storage;
import io.dbobjects.storage.StorageEvent;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Collection;
import java.util.stream.Collectors;

public class DbObjects implements AutoCloseable {
    @Getter(AccessLevel.PROTECTED)
    private final Node node;
    @Getter(AccessLevel.PROTECTED)
    private final DBOApplicationConfig appConfig;
    private DBOApplicationContext appCtx;

    public DbObjects(DBOApplicationContext appCtx, DBOApplicationConfig appConfig,
                     Collection<? extends Storage<?>> storages) {
        this.appCtx = appCtx;
        var domainConfigurations = storages.stream()
                .map(Storage::getDomainName)
                .distinct()
                .map(domainName -> new DomainConfiguration()
                        .setName(domainName)
                        .setEnableLocalEventHandler(appConfig.isEventHandlerEnabled())
                        .setEnableMasterNodeEventHandler(appConfig.isEventHandlerEnabled()))
                .collect(Collectors.toList());
        this.appConfig = appConfig;
        var node = new Node(appCtx, appConfig);
        this.node = node;

        node.subscribeToDomains(domainConfigurations);

        storages.forEach(node::addStorage);

        appCtx.getDomainMessageQueue().ifPresent(q -> q.addReceiver(node.asNodeSyncEventReceiver()));
    }

    public NodeContext getNodeContext() {
        return this.node;
    }

    public StateUpdater getStateUpdater() {
        return this.node;
    }

    public DBOApplicationContext getApplicationContext() {
        return this.appCtx;
    }

    protected Database getDatabase() {
        return getNode().getDatabase();
    }

    @Override
    public void close() {
        node.close();
    }

    public boolean isMaster() {
        return getNode().isMaster();
    }

    public DbObjects synchronize() {
        getNode().synchronize();
        return this;
    }

    public NodeState.StatusCode getNodeStatusCode() {
        return getNode().getNodeState().getNodeStatusCode();
    }

    public int getDataApplicationVersion() {
        return getNode().getApplicationState().getAppVer();
    }

    public int getDataDBImplementationVersion() {
        return getNode().getApplicationState().getDboVer();
    }

    public String getNodeId() {
        return getNode().getNodeState().getNodeId();
    }

    public StorageEvent acceptEvent(String domain, StorageEvent.EventType eventType, PayloadInfo payloadInfo) {
        return node.acceptEvent(domain, eventType, payloadInfo);
    }
}
