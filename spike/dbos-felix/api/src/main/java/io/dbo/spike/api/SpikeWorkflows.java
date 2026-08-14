package io.dbo.spike.api;

/**
 * The workflow interface contributed over the whiteboard. Implementations
 * carry the engine's workflow annotations; callers see only this interface.
 */
public interface SpikeWorkflows {
    /** Two checkpointed steps, returns a greeting. */
    String greet(String name);

    /** Steps: s1 -> gate (blocks until the gate file exists) -> s3. */
    String gatedGreet(String name);
}
