package cloud.jengu.dbo.core.api;

/**
 * The store did not answer (§7.9). Not a decision about the caller.
 *
 * <p><b>Refused and unanswered need opposite recoveries, and they used to be
 * one exception.</b> A refusal is settled — this identity did not claim that
 * run, this step was not granted, this action the step never declared — and
 * the right response is to stop asking. A store that did not answer is
 * transient, and the right response is to ask again. Treating the second as
 * the first is the failure worth naming: a bench quietly stops taking work it
 * is entitled to, and nobody notices until a queue is not moving.
 *
 * <p>The cost is worse than a stalled bench. A participant that backs off
 * while holding a claim keeps holding it only until the deadline, and then
 * the tenant's own housekeeping releases the run and somebody else takes it —
 * so a brief outage misread as a refusal does not merely pause one bench, it
 * moves work that was never in trouble.
 *
 * <p><b>Retry is safe, by construction rather than by luck.</b> A caller
 * cannot always know whether a verb that went unanswered was applied — a
 * connection lost after the request left is genuinely ambiguous — and it does
 * not need to: the participation verbs are conditional or idempotent
 * precisely because delivery is at-least-once. A claim retried either wins or
 * loses the race it would have run anyway; a checkpoint retried writes what
 * it would have written.
 *
 * <p><b>Where the line falls, for a surface that speaks HTTP:</b> 4xx is
 * settled and 5xx is unanswered. That is not a convention invented here — it
 * is what those codes already mean — and it puts the ambiguous cases on the
 * right side without anybody guessing. A pool exhausted under load answers
 * 500 and is exactly the brief outage this exists to survive; a step that was
 * never granted answers 403 and will answer it again forever.
 */
public class StoreUnreachableException extends IllegalStateException {

    public StoreUnreachableException(String message) {
        super(message);
    }

    public StoreUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
