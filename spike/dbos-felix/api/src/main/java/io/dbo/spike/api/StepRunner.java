package io.dbo.spike.api;

import java.util.concurrent.Callable;

/**
 * DBO-owned step abstraction handed to workflow implementations. Wraps the
 * durable engine's checkpointed step execution without leaking its types
 * across the bundle boundary.
 */
public interface StepRunner {
    <T> T run(String stepName, Callable<T> body);
}
