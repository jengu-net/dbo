package cloud.jengu.dbo.runner;

import java.util.Map;

/**
 * What a worker says about itself in each heartbeat, beside the runner's own
 * counts.
 *
 * <p>Registered like a step: in a container, as a service the runner's
 * whiteboard takes up; under the Spring worker, as a bean. The runner asks
 * each contributor once per heartbeat and puts what it answers under its
 * namespace, so the state of a worker and of everything it routes reaches the
 * node in one document.
 *
 * <p><b>Nothing about a person.</b> A heartbeat travels authenticated but
 * outside any sealed work, and the store cannot check what it says, so this
 * is the contributor's rule to keep: anything about a person travels only as
 * work.
 */
public interface HeartbeatStatistics {

    /**
     * The top-level key this contributor's statistics go under. Any name but
     * one starting {@code dbo.}, which is the runner's; a contributor naming
     * one is refused when it is registered.
     */
    String namespace();

    /**
     * The statistics now: a JSON-shaped tree of maps, lists, strings, numbers
     * and booleans, nested as deep as it needs to be. Asked on the runner's
     * thread, once per heartbeat, so it answers from what it already holds.
     */
    Map<String, Object> statistics();
}
