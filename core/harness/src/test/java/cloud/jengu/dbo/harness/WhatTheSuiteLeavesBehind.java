package cloud.jengu.dbo.harness;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What is still resident after each test class, so the suite's floor has an
 * owner.
 *
 * <p>A tenant costs 226 MB for the first on a face and 11 for the next on it,
 * and the suite's floor before any of that was measured at 1.3 GB of a
 * two-gigabyte heap. That floor is what exhausts it, and nothing attributed a
 * megabyte of it: it is whatever fifty-odd classes have left behind, and none
 * of it is a tenant still being served.
 *
 * <p>This says which class the floor rose after. A class that adds ten
 * megabytes and never gives them back is invisible on its own and is the whole
 * problem fifty times over.
 *
 * <p><b>Off unless asked for</b>, because it forces a collection after every
 * class and that is most of a minute across a suite. It is also the reason
 * this measures after a class rather than during one: what a class needs while
 * it runs is its business, and what it still holds when it has finished is
 * everybody's.
 *
 * <pre>
 *   ./gradlew :core:harness:test -Ddbo.heap.attribute=true
 *   cat core/harness/build/heap-after-each-class.txt
 *
 *   # and, narrowed to what that accused, what the floor is made of:
 *   ./gradlew :core:harness:test --tests "*MilestonesOnTheCheckpointIT" \
 *       -Ddbo.heap.attribute=true -Ddbo.heap.histogram=true
 *   head -30 core/harness/build/heap-histogram.txt
 * </pre>
 */
public final class WhatTheSuiteLeavesBehind implements TestExecutionListener {

    private static final boolean ASKED_FOR =
            Boolean.getBoolean("dbo.heap.attribute");

    /**
     * What the floor is MADE of, once it is known whose it is.
     *
     * <p>The per-class reading says which class the floor rose after. It
     * cannot say what rose: a class that brings up a face's first tenant and
     * one that leaks a pool look identical from outside. A histogram of what
     * is live at the end names the types, which separates a definition cache
     * held on purpose from a thread nobody stopped.
     *
     * <p>Separate from the per-class flag because it is only worth taking on a
     * run narrowed to the classes under suspicion, and because it shells out.
     */
    private static final boolean HISTOGRAM =
            Boolean.getBoolean("dbo.heap.histogram");

    /** Class, and what was still in the heap when it finished. */
    private record Left(String className, long mb, long deltaMb) {}

    private final List<Left> floor = new ArrayList<>();
    private long previous = -1;

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!ASKED_FOR || !identifier.isContainer()) {
            return;
        }
        // Only a class. The engine and the whole plan are containers too, and
        // a reading taken at those says nothing about who left what.
        String name = identifier.getSource()
                .filter(source -> source instanceof org.junit.platform.engine.support
                        .descriptor.ClassSource)
                .map(source -> ((org.junit.platform.engine.support.descriptor.ClassSource) source)
                        .getClassName())
                .orElse(null);
        if (name == null) {
            return;
        }
        long inUse = heapInUse();
        long mb = inUse / (1024 * 1024);
        floor.add(new Left(name, mb, previous < 0 ? 0 : mb - previous / (1024 * 1024)));
        previous = inUse;
        // Written after every class rather than at the end of the plan. The
        // suite this measures is the one that dies of heap exhaustion, and a
        // measurement that only survives a clean finish would be absent from
        // exactly the run worth reading.
        write();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        write();
        histogram();
    }

    /**
     * What is live at the end, by type, from the JVM's own tooling.
     *
     * <p>{@code GC.class_histogram} collects first and reports shallow sizes:
     * it is what each type's own instances weigh, not what they keep alive. A
     * definition cache shows up as the arrays it is made of rather than as the
     * cache, so this names the material and the reader still has to say whose
     * it is. That is enough to tell a validator's tables from a thread pool,
     * which is the question the per-class reading cannot answer.
     */
    private void histogram() {
        if (!ASKED_FOR || !HISTOGRAM) {
            return;
        }
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
        Path where = Path.of("build", "heap-histogram.txt");
        try {
            Files.createDirectories(where.getParent());
            Process asked = new ProcessBuilder(jcmd.toString(),
                    String.valueOf(ProcessHandle.current().pid()),
                    "GC.class_histogram")
                    .redirectErrorStream(true)
                    .redirectOutput(where.toFile())
                    .start();
            if (!asked.waitFor(2, TimeUnit.MINUTES)) {
                asked.destroyForcibly();
                throw new IllegalStateException("the histogram did not finish in two minutes");
            }
        } catch (IOException | InterruptedException cannotAsk) {
            if (cannotAsk instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Loud, not swallowed: a measurement that silently did not happen
            // is the failure this whole listener exists to stop being possible.
            throw new IllegalStateException("could not take the heap histogram with "
                    + jcmd + " — is this a JDK?", cannotAsk);
        }
    }

    private void write() {
        if (!ASKED_FOR || floor.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder("""
                # What was still in the heap after each class finished, in
                # megabytes, after a forced collection.
                #
                # The FLOOR is the number that matters: it only goes down when
                # somebody lets something go. A class with a large delta added
                # something and kept it; a class with a large floor and a small
                # delta is merely standing on what came before.
                #
                # Taken after a class rather than during one: what a class
                # needs while it runs is its business, and what it still holds
                # when it has finished is everybody's.
                #
                #   floor  delta  class
                """);
        for (Left left : floor) {
            out.append(String.format("%7d %6d  %s%n", left.mb(), left.deltaMb(),
                    left.className()));
        }
        try {
            Path where = Path.of("build", "heap-after-each-class.txt");
            Files.createDirectories(where.getParent());
            Files.writeString(where, out.toString());
        } catch (IOException cannotWrite) {
            throw new IllegalStateException("could not write the heap attribution", cannotWrite);
        }
    }

    /** The same reading the memory baseline takes, so the two are comparable. */
    private static long heapInUse() {
        for (int settling = 0; settling < 3; settling++) {
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
