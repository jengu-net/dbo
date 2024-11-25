package io.dbobjects.db.postgres;

import io.dbobjects.DomainConfiguration;
import io.dbobjects.db.DomainState;
import io.dbobjects.domain.NamedThreadFactory;
import io.dbobjects.parallel.NodeContext;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class BlockingDomainTaskHandler implements Runnable, AutoCloseable {

    private final DomainConfiguration domainConfiguration;
    private final NodeContext nodeContext;

    private final BiFunction<NodeContext, String, Optional<DomainState>> domainStateSupplier;
    private final AtomicBoolean shutdownAsked;


    private final BlockingQueue<TaskExecutor> taskExecutorQueue = new LinkedBlockingDeque<>();
    @Getter
    private boolean running = false;

    public static Optional<BlockingDomainTaskHandler> startIfNeeded(
        DomainConfiguration domainConfiguration,
        NodeContext nodeContext,
        BiFunction<NodeContext, String, Optional<DomainState>> domainStateSupplier,
        AtomicBoolean shutdownAsked) {
        return Optional.ofNullable(
            domainConfiguration.isEnableBlockingDomainTaskHandler() ? new BlockingDomainTaskHandler(
                domainConfiguration, nodeContext, domainStateSupplier, shutdownAsked) : null);
    }


    public String getHandlerDisplayName() {
        return "L-EventHandler-" + domainConfiguration.getName();
    }

    public int getQueueSize() {
        return taskExecutorQueue.size();
    }

    public void execute() {
        Executors.newSingleThreadExecutor(new NamedThreadFactory(getHandlerDisplayName()))
            .execute(this);
    }

    public static boolean executeTask(NodeContext nodeContext,
                                      BiFunction<NodeContext, String, Optional<DomainState>> domainStateSupplier,
                                      String domainName, TaskExecutor task
    ) {
        return task.executeTask(nodeContext, domainStateSupplier.apply(nodeContext, domainName));
    }

    @Override
    public void run() {
        running = true;
        while (!shutdownAsked.get() || !taskExecutorQueue.isEmpty()) {
            try {
                var task = taskExecutorQueue.peek();
                if (task != null) {
                    var domainName = domainConfiguration.getName();
                    boolean succeeded =
                        executeTask(nodeContext, domainStateSupplier, domainName, task);
                    if (succeeded) {
                        taskExecutorQueue.remove(task);
                    }

                }
            } catch (Exception e) {
                log.warn("exception in polling queue for domain " + domainConfiguration.getName(), e);
                log.info("queue size: {}", taskExecutorQueue.size());
            }
        }
        running = false;
    }

    private Collection<String> taskTypesInQueue = new ArrayList<>();

    public void addTask(TaskExecutor task) {
        if (shutdownAsked.get()) {
            throw new IllegalStateException("Can not add events. System is shutting down ...");
        }
        var taskType = task.getClass().getName();
        if (taskTypesInQueue.contains(taskType)) {
            log.info("task {} is already scheduled. Ignoring the duplicate ...", taskType);
            return;
        }
        taskExecutorQueue.add(task);
    }

    @Override
    public void close() {
        while (isRunning()) {
            log.info("waiting for {} tasks queue (tasks left: {}) ...", domainConfiguration.getName(),
                getQueueSize());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public interface TaskExecutor {
        boolean executeTask(NodeContext nodeContext, Optional<DomainState> maybeDomainState);
    }

}
