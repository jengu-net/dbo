package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Run;

import java.util.Map;

/**
 * One run's work, arrived whole (REQ-DBO-PROC-WORK-ARRIVES-WHOLE): the run,
 * its inputs, and the way to say "still moving".
 *
 * @param run    the claimed run — the global truth this work answers to
 * @param inputs the run's declared inputs, resolved and slot-keyed. A
 *               service never fetches, and cannot: the step's declaration
 *               (#71) is the API it joined, the run fills those slots
 *               (#149), and a runner has no verb that takes a reference —
 *               which is what closes the door on asking for data the step
 *               was never entitled to.
 */
public record Work(Run run, Map<String, StoredObject> inputs, Progress progress) {

    public Work {
        inputs = Map.copyOf(inputs);
    }

    /**
     * Progress extends the claim — by evidence, never by tick. A checkpoint
     * carries counts because what a deadline protects against is a process
     * that is alive and getting nowhere, and counts are how "getting
     * somewhere" is said on the record.
     */
    @FunctionalInterface
    public interface Progress {

        void checkpoint(Map<String, Long> counts);
    }
}
