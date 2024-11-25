package io.dbo.eventing;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import io.dbo.eventing.kafka.KafkaEventingFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class EventingTest {


    @Test
    @Disabled
    public void testSendingEvents() {

        var config = new SimpleEventConfiguration()
                .setHosts(List.of("127.0.0.1:19092"))
                .setGlobalErrorTopic("io.dbo-test.global-errors.updated");

        try (var eventingFactory = new KafkaEventingFactory(config)) {
            var domain = "test-domain";
            var domainTopic = "io.dbo-test.updated." + domain;
            var eventing = eventingFactory.instance(domain, List.of(domainTopic), this::filter, this::consume);
            var timestamp = System.currentTimeMillis();
            var numOfRecords = 10000000;
            this.consumedEvents.set(0);
            for (int i = 0; i < numOfRecords; i++) {
                eventing.sendEvent(domainTopic, NanoIdUtils.randomNanoId(), createPayload(i));
                if (i % 100000 == 0) {
                    log.info("{} events sent in {}ms.", i, System.currentTimeMillis() - timestamp);
                }
            }
            eventing.flush();
            log.info("{} events sent in {}ms.", numOfRecords, System.currentTimeMillis() - timestamp);
            log.info("waiting for consumer ...");
            waitFor(() -> consumedEvents.get() >= numOfRecords, 10000, () -> String.format("waiting for consumption of %s events. Number of received events: %s", numOfRecords, consumedEvents.get()));
            log.info("{} events consumed in {}ms.", numOfRecords, System.currentTimeMillis() - timestamp);
            log.info("the end!");
        }
    }

    private AtomicInteger consumedEvents = new AtomicInteger();
    private boolean consume(String topic, String eventId, byte[] bytes) {
        //log.info("consuming event {} in topic {}", eventId, topic);
        consumedEvents.incrementAndGet();
        return true;
    }

    private boolean filter(String topic, String eventId) {
        //log.info("filtering event {} in topic {}", eventId, topic);
        //return consumedEvents.size() < 5;
        return true;
    }

    private void waitFor(Supplier<Boolean> condition, long upToMillis, Supplier<String> actionDescription) {
        long timestamp = System.currentTimeMillis();
        while (!condition.get() && System.currentTimeMillis() - timestamp < upToMillis) {
            log.info(actionDescription.get());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private byte[] createPayload(int i) {
        return ("{" + "\"content\" : \"bla bla " + i + "\"" + "}").getBytes(UTF_8);
    }

}
