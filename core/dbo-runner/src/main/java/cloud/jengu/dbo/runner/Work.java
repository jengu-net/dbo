package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.work.Run;

import java.util.List;
import java.util.Map;

/**
 * One run's work, arrived whole (REQ-DBO-PROC-WORK-ARRIVES-WHOLE): the run,
 * the objects it references, and the way to say "still moving".
 *
 * @param run     the claimed run — the global truth this work answers to
 * @param related the objects the run's item names, fetched by the runner
 *                before {@code perform} is called; a service never fetches
 */
public record Work(Run run, List<StoredObject> related, Progress progress) {

    public Work {
        related = List.copyOf(related);
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
