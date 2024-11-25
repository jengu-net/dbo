package io.dbobjects.eventing;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;


/**
 * The Eventing interface represents a system for sending and receiving events.
 * It provides methods to send events, subscribe to topics, and check if the eventing system is closed.
 * It extends the AutoCloseable interface to allow for graceful shutdown of connecting to the eventing system.
 */
public interface Eventing extends AutoCloseable {
    /**
     * Returns a boolean value indicating whether the connection to eventing system is closed or not.
     *
     * @return true if the connection to the eventing system is closed, false otherwise
     */
    boolean isClosed();

    /**
     * Returns the client identifier.
     *
     * @return the client identifier
     */
    String getClientId();

    /**
     * Returns global error topic name.
     * @return name of the general error topic
     */
    String getGlobalErrorTopic();

    /**
     * Sends an event to the specified topic.
     *
     * @param topic        the topic to which the event should be sent
     * @param eventId      the ID of the event. Event will replace the event with the same ID in the given topic if found.
     *                     Therefore, unique ID should be generated if that kind of replacement wanted to be avoided.
     *                     In case of <code>null</code> argument, unique ID will be generated using <code>nanoId</code>.
     * @param eventPayload the event payload to send. If the payload supposed to be textual then default encoding of
     *                     event would be UTF-8. <code>null</code> value of event payload will be treated as request
     *                     to remove the event with given id.
     * @return event id if message is sent successfully. Otherwise, <code>null</code>;
     */
    String sendEvent(String topic, String eventId, byte[] eventPayload);

    void flush();

    /**
     * Subscribes a new listener for the topic. It will not replace existing one - so multiple listeners might be
     * added to the one topic.
     *
     * @param topics     names of the topics
     * @param filter     filter for the function for making of first filtering decision based on given event id. If the
     *                   function returns true then the event will be passed to the consumer. Otherwise, the event will
     *                   be skipped as non-interested one.
     * @param consumer   Event consumer takes 2 parameters: event id as string and event payload as byte array. It
     *                   returns true if event is consumed successfully. Otherwise, false.
     *                   If false is returned, the message will be sent to the errorTopic.
     * @param errorTopic topic where all non-consumed events will be sent. An event is considered as non-consumed if
     *                   consumer returns <code>null</code> or <code>false</code> on consuming of the message.
     *                   If errorTopic is not specified then the message is put to the global error topic.
     * @return subscriber id
     */
    String subscribe(Collection<String> topics, Function<String, Boolean> filter, BiFunction<String, byte[], Boolean> consumer,
                     String errorTopic);

    @FunctionalInterface
    interface TopicConsumer {
        boolean consume(String topic, String eventId, byte[] payload);
    }
}
