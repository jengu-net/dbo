package cloud.jengu.dbo.work;

import java.util.Optional;

/**
 * What resolution decided, and why (ADR 0059).
 *
 * <p>The reason is part of the answer rather than a log line: "no automation
 * ran" and "automation was switched off in this zone" and "a narrower scope
 * tried to override a step that is not overridable" are three different facts,
 * and an operator holding the work needs to know which of them they are looking
 * at.
 */
public record Resolution(Executor executor, String reason, String refused) {

    static Resolution selected(Executor executor, String refused) {
        return new Resolution(executor, null, refused);
    }

    static Resolution fallThrough(String reason) {
        return new Resolution(null, reason, null);
    }

    /** Whether anything automated claimed this work. */
    public boolean automated() {
        return executor != null;
    }

    public Optional<Executor> selected() {
        return Optional.ofNullable(executor);
    }

    /**
     * An override a narrower scope attempted and did not get, if one did.
     *
     * <p>Present whether or not something else ran: a refused override is a
     * fact about somebody's rule, and it stops being visible the moment nobody
     * writes it down.
     */
    public Optional<String> refusedOverride() {
        return Optional.ofNullable(refused);
    }

    /** What a run should carry about this resolution. */
    public String note() {
        return reason != null ? reason : refused;
    }
}
