package io.dbobjects.nodesync;

public interface SynchronizedDomainEventReceiver {
    boolean receiveSynchronizedDomainEvent(SynchronizedDomainEvent event);

    SynchronizedDomainEvent.Recipient geiRecipientTypeForDomain(String domainName);
}
