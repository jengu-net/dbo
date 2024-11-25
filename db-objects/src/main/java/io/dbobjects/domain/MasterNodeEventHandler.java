package io.dbobjects.domain;

import io.dbobjects.DomainConfiguration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class MasterNodeEventHandler implements Runnable, AutoCloseable {

    // FIXME: make master node event handling process idle times configurable
    Map<MasterNodeProcessor.NodeState, Integer> intervals = Map.of(
        MasterNodeProcessor.NodeState.MASTER_AND_HAS_MORE_EVENTS, 0,
        MasterNodeProcessor.NodeState.MASTER_AND_NO_MORE_EVENTS, 2000,
        MasterNodeProcessor.NodeState.SLAVE, 5000
    );

    private final MasterNodeProcessor masterNodeProcessor;
    private final DomainConfiguration domainConfiguration;
    private final AtomicBoolean shutdownAsked;

    @Getter
    private boolean running = false;

    public static Optional<MasterNodeEventHandler> startIfNeeded(
        MasterNodeEventHandler.MasterNodeProcessor masterNodeProcessor,
        DomainConfiguration domainConfiguration,
        AtomicBoolean shutdownAsked, long batchSize) {
        var maybeInstance = Optional.ofNullable(
            domainConfiguration.isEnableMasterNodeEventHandler() ? new MasterNodeEventHandler(masterNodeProcessor,
                domainConfiguration, shutdownAsked) : null);
        maybeInstance.ifPresent(MasterNodeEventHandler::execute);
        return maybeInstance;
    }

    public String getHandlerDisplayName() {
        return "MN-EventHandler-" + domainConfiguration.getName();
    }

    @Override
    public void run() {
        running = true;
        while (!shutdownAsked.get()) {
            var result = masterNodeProcessor.processEvents(domainConfiguration.getName());
            try {
                Thread.sleep(intervals.get(result));
            } catch (InterruptedException e) {
                // -
            }
        }
        running = false;

    }

    public void execute() {
        Executors.newSingleThreadExecutor(new NamedThreadFactory(getHandlerDisplayName()))
            .execute(this);
    }

    @Override
    public void close() {
        while (isRunning()) {
            log.info("waiting for {} shutdown ...", domainConfiguration.getName());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public interface MasterNodeProcessor {
        boolean processEvents(String domainName);

        enum NodeState {
            SLAVE, MASTER_AND_NO_MORE_EVENTS, MASTER_AND_HAS_MORE_EVENTS
        }
    }
}
