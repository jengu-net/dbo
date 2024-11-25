package io.dbobjects.nodesync;

import io.dbobjects.db.DomainState;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class SynchronizedDomainEvent {

    private String id;
    private String domain;
    private Recipient recipient;
    private String senderNode;
    private long timestamp;
    private EventType eventType;
    private DomainState domainState;

    public enum EventType {
        HEARTBEAT, STATE_UPDATED, ACK_STATE_UPDATED
    }

    public enum Recipient {
        MASTER, SLAVE_NODES, INACTIVE_NODES
    }
}
