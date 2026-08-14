package io.dbo.spike.contrib;

import dev.dbos.transact.workflow.Workflow;
import io.dbo.spike.api.SpikeWorkflows;
import io.dbo.spike.api.StepRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Workflow implementation living in the contrib bundle's classloader.
 * Steps append evidence lines to a file so the harness can count executions
 * (proving checkpoints were respected across crash/relaunch).
 */
public class SpikeWorkflowsImpl implements SpikeWorkflows {

    private final StepRunner steps;
    private final Path evidenceDir;

    public SpikeWorkflowsImpl(StepRunner steps, Path evidenceDir) {
        this.steps = steps;
        this.evidenceDir = evidenceDir;
    }

    @Workflow(name = "greet")
    @Override
    public String greet(String name) {
        String s1 = steps.run("s1", () -> {
            log("greet.s1:" + name);
            return "Hello";
        });
        return steps.run("s2", () -> {
            log("greet.s2:" + name);
            return s1 + ", " + name + "!";
        });
    }

    @Workflow(name = "gatedGreet")
    @Override
    public String gatedGreet(String name) {
        String s1 = steps.run("s1", () -> {
            log("gated.s1:" + name);
            return "Hi";
        });
        steps.run("gate", () -> {
            while (!Files.exists(evidenceDir.resolve("gate-open"))) {
                Thread.sleep(100);
            }
            log("gated.gate:" + name);
            return "open";
        });
        return steps.run("s3", () -> {
            log("gated.s3:" + name);
            return s1 + " " + name + " (recovered)";
        });
    }

    private void log(String line) {
        try {
            Files.writeString(evidenceDir.resolve("evidence.log"), line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
