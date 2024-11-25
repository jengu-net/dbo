package io.dbobjects.nodesync;

public interface DomainMessenger {
    boolean sendSynchronizedDomainEvent(SynchronizedDomainEvent domainEvent);
}
