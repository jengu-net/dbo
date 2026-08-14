package io.dbo.spike.api;

/**
 * The service the embedding bundle exposes per launched runtime.
 * One registration per runtime, distinguished by the {@code dbo.runtime}
 * service property.
 */
public interface DurableService {
    String runtimeId();

    /** Scenario A: run the greet workflow synchronously, return its result. */
    String greetAndWait(String workflowId, String name);

    /** Scenario B: start the gated workflow asynchronously under the given id. */
    void startGated(String workflowId, String name);

    /** Current engine status of a workflow ("PENDING", "SUCCESS", ...). */
    String statusOf(String workflowId);

    /** Scenario B: abrupt engine shutdown (crash approximation). */
    void crash();

    /** Scenario B: fresh engine instance over the same database; re-registers workflows. */
    void relaunch();

    /** Scenario B: resume a workflow by id on the current instance and return its result. */
    String resume(String workflowId);
}
