package io.dbobjects.nodesync.postgres;

import io.dbobjects.ObjectMapper;
import io.dbobjects.nodesync.DomainMessageQueue;
import io.dbobjects.nodesync.DomainMessenger;
import io.dbobjects.nodesync.SynchronizedDomainEvent;
import io.dbobjects.nodesync.SynchronizedDomainEventReceiver;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.pubsub.PgSubscriber;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeSyncQueue implements DomainMessenger, DomainMessageQueue, AutoCloseable {

    private static final String CHANNEL_NAME = "db_objects";
    private final PgSubscriber subscriber;
    private final ObjectMapper objectMapper;

    private final Collection<SynchronizedDomainEventReceiver> receivers = new ArrayList<>();

    public NodeSyncQueue(PgConnectOptions connectOptions, ObjectMapper objectMapper,
                         Optional<Vertx> maybeVertx) {
        this.objectMapper = objectMapper;
        this.subscriber = PgSubscriber.subscriber(maybeVertx.orElse(Vertx.vertx()), connectOptions);
        this.subscriber.reconnectPolicy(retries -> 500L);
        this.subscriber.channel(CHANNEL_NAME).handler(payload -> {
            var event = decodeEvent(payload);
            receivers.stream()
                    .filter(node -> node.geiRecipientTypeForDomain(event.getDomain())
                            .equals(event.getRecipient())) // send only to targeted recipients
                    .forEach(node -> node.receiveSynchronizedDomainEvent(event));
        });
        this.subscriber.connect();
    }

    @Override
    public void addReceiver(SynchronizedDomainEventReceiver receiver) {
        if (!receivers.contains(receiver)) {
            receivers.add(receiver);
        }
    }

    @Override
    public boolean sendSynchronizedDomainEvent(SynchronizedDomainEvent domainEvent) {
        var sql = "select pg_notify($1, $2)";
        var params = List.of(CHANNEL_NAME, encodeEvent(domainEvent));
        var success = new AtomicBoolean();
        try {
            subscriber.actualConnection().preparedQuery(sql).execute(
                            Tuple.from(params))
                    .onFailure(h -> {
                        log.warn("FAILED SQL: {} PARAMS: {}", sql, params);
                        log.warn("exception", h);
                        success.set(false);
                    });

        } catch (Exception e) {
            log.warn("exception", e);
            throw new RuntimeException(e);
        }
        return success.get();
    }

    private String encodeEvent(SynchronizedDomainEvent domainEvent) {
        return Base64.getEncoder().encodeToString(objectMapper.serialize(domainEvent).getBytes());
    }

    private SynchronizedDomainEvent decodeEvent(String encodedEvent) {
        return objectMapper.deserialize(SynchronizedDomainEvent.class, new String(Base64.getDecoder().decode(encodedEvent)));
    }

    @Override
    public void close() {
        if (subscriber != null) {
            subscriber.close().result();
        }
    }
}
