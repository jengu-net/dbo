package io.dbo.eventing.kafka;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import io.dbobjects.eventing.Eventing;
import io.dbobjects.eventing.EventingConfiguration;
import io.dbobjects.eventing.EventingFactory;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@RequiredArgsConstructor
public class KafkaEventingFactory implements EventingFactory {
    private final EventingConfiguration config;
    private final Map<String, KafkaEventing> clientMap = new HashMap<>();

    @Override
    public Eventing instance(String clientCode, Collection<String> topics, BiFunction<String, String, Boolean> consumptionFilter,
                             KafkaEventing.TopicConsumer topicConsumer) {
        var clientId = NanoIdUtils.randomNanoId();
        var instance = new KafkaEventing(clientCode, clientId, config, topics, consumptionFilter, topicConsumer);
        clientMap.put(clientId, instance);
        return instance;
    }

    @Override
    public void close() {
        clientMap.values().stream()
                .filter(Predicate.not(KafkaEventing::isClosed))
                .forEach(KafkaEventing::close);
        clientMap.clear();
    }
}
