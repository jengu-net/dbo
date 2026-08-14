package io.dbo.spike.api;

import java.nio.file.Path;

/**
 * Whiteboard contribution: a bundle registers this factory; the embedding
 * bundle creates one implementation per launched runtime, handing it the
 * runtime's StepRunner and the evidence directory for the harness.
 */
public interface SpikeWorkflowsFactory {
    SpikeWorkflows create(StepRunner steps, Path evidenceDir);
}
