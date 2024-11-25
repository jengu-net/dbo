package io.dbo.eventing.kafka;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import io.dbobjects.eventing.Eventing;
import io.dbobjects.eventing.EventingConfiguration;
import io.dbobjects.domain.NamedThreadFactory;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

@Slf4j
public class KafkaEventing implements Eventing {
    @Getter
    private final String clientId;
    private final String clientCode;
    @Getter
    private final String globalErrorTopic;
    private Optional<KafkaProducer<String, byte[]>> maybeKafkaProducer = Optional.empty();
    private Optional<KafkaEventHandler> maybeEventHandler = Optional.empty();
    private AtomicBoolean shutdownAsked = new AtomicBoolean(false);

    public KafkaEventing(@NonNull String clientCode, @NonNull String clientId, @NonNull EventingConfiguration config,
                         @NonNull Collection<String> topicsToBeConsumed, BiFunction<String, String, Boolean> consumptionFilter,
                         TopicConsumer topicConsumer) {
        this.clientId = clientId;
        this.clientCode = clientCode;
        this.globalErrorTopic = config.getGlobalErrorTopic();
        var ips = config.getHosts() != null ? String.join(",", config.getHosts()) : "127.0.0.1:9092";
        Properties props = new Properties();
        props.put(CLIENT_ID_CONFIG, this.clientId);
        props.put(GROUP_ID_CONFIG, "group-" + clientCode);
        props.put(BOOTSTRAP_SERVERS_CONFIG, ips);
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ACKS_CONFIG, "1");

        if (!shutdownAsked.get()) {
            this.maybeKafkaProducer = Optional.of(new KafkaProducer<>(props));
            this.maybeEventHandler = Optional.of(new KafkaEventHandler(props, topicsToBeConsumed,
                    consumptionFilter, topicConsumer, shutdownAsked));
        }
        this.maybeEventHandler.ifPresent(handler -> Executors.newSingleThreadExecutor(
                new NamedThreadFactory(clientCode + "-kafka-consumer")).execute(handler));
        //this.consumerLoop = new KafkaEventHandler(props, topicsToBeConsumed, consumptionFilter, topicConsumer);
        //consumerThread = new Thread(consumerLoop);
        //consumerThread.start();
    }

    @Override
    public boolean isClosed() {
        return maybeKafkaProducer == null;
    }


    @Override
    public String sendEvent(String topic, String eventId, byte[] eventPayload) {
        var id = eventId != null ? eventId : NanoIdUtils.randomNanoId();
        this.maybeKafkaProducer.ifPresent(producer -> producer.send(new ProducerRecord<>(topic, id, eventPayload)));
        return id;
    }

    @Override
    public void flush() {
        this.maybeKafkaProducer.ifPresent(KafkaProducer::flush);
    }

    @Override
    public String subscribe(Collection<String> topics, Function<String, Boolean> filter, BiFunction<String, byte[],
            Boolean> consumer, String errorTopic) {
        return null;
    }


    @Override
    public void close() {
        this.shutdownAsked.set(true); // sending shutdown signal to all threads
        log.info("shutting down eventing for {} ...", clientCode);
        maybeKafkaProducer.ifPresent(producer -> {
            producer.flush();
            producer.close();
            maybeKafkaProducer = Optional.empty();
        });
        maybeEventHandler.ifPresent(handler -> {
            while (handler.isRunning()) {
                log.info("waiting for {} eventing shutdown ...", clientCode);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        log.info("eventing for {} shut down successfully", clientCode);
    }

    private class KafkaEventHandler implements Runnable {
        private final BiFunction<String, String, Boolean> consumptionFilter;
        private final TopicConsumer topicConsumer;
        //private final KafkaConsumer<String, byte[]> kafkaConsumer;
        private final Properties props;
        private final Collection<String> topicsToBeConsumed;

        private final AtomicBoolean shutdownAsked;
        @Getter
        private boolean running = false;

        public KafkaEventHandler(Properties props, Collection<String> topicsToBeConsumed,
                                 BiFunction<String, String, Boolean> consumptionFilter, TopicConsumer topicConsumer,
                                 AtomicBoolean shutDownHook) {
            this.shutdownAsked = shutDownHook;
            this.props = props;
            this.topicsToBeConsumed = topicsToBeConsumed;
            this.consumptionFilter = consumptionFilter;
            this.topicConsumer = topicConsumer;
        }

        public void run() {
            try (var kafkaConsumer = new KafkaConsumer<String, byte[]>(props)) {
                running = true;
                kafkaConsumer.subscribe(topicsToBeConsumed);

                while (!shutdownAsked.get()) {
                    kafkaConsumer.poll(Duration.of(500, ChronoUnit.MILLIS)).forEach(record -> {
                        if (consumptionFilter == null || consumptionFilter.apply(record.topic(), record.key())) {
                            if (!topicConsumer.consume(record.topic(), record.key(), record.value())) {
                                sendEvent(getGlobalErrorTopic(), record.key(), record.value());
                            }
                        }
                    });
                    kafkaConsumer.commitAsync();
                }
                running = false;
            }
        }
    }

}
