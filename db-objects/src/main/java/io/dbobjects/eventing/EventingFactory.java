package io.dbobjects.eventing;

import java.util.Collection;
import java.util.function.BiFunction;

public interface EventingFactory extends AutoCloseable {
    Eventing instance(String clientCode, Collection<String> topics, BiFunction<String, String, Boolean> consumptionFilter,
                      Eventing.TopicConsumer topicConsumer);
}
