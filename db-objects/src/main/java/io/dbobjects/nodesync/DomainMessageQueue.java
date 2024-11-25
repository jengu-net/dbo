package io.dbobjects.nodesync;

public interface DomainMessageQueue {
    void addReceiver(SynchronizedDomainEventReceiver receiver);
}
