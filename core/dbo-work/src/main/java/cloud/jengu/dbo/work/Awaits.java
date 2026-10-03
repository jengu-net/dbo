package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;

import java.util.List;

/**
 * Who owes a run's next act — derived from its status, its claimant and who
 * may take it, and never stored (REQ-DBO-PROC-WHO-OWES-THE-NEXT-ACT-IS-DERIVED).
 *
 * <p>A claimed run waits for its owner. An unclaimed run open to automation
 * waits for a machine, and is nobody's card. An unclaimed run open to people
 * alone waits for a person — the list an operator opens. A run that is over
 * owes nothing. Stored as a field of its own, it would disagree with the facts
 * it is made of the moment one of them moved.
 */
public enum Awaits {

    /** Claimed: whoever holds it. */
    OWNER,

    /** Unclaimed and open to automation: a machine, now or after its not-before. */
    MACHINE,

    /** Unclaimed and open to people alone. */
    PERSON,

    /** Over: completed, failed or cancelled. */
    NOTHING;

    /** Lowercase: this crosses a wire and a console as a word. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The word, or null for {@code any} and for none. */
    public static Awaits ofWire(String word) {
        return word == null || "any".equalsIgnoreCase(word) ? null
                : valueOf(word.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * This, as a question the store answers from the envelope: the statuses
     * it is, and — where it matters — who may take it.
     */
    public Criteria narrowing(Criteria criteria) {
        switch (this) {
            case OWNER -> criteria.eq("status", EnvelopeValue.of(Status.IN_PROGRESS.wire()));
            case MACHINE -> criteria.anyOf("status", List.of(
                            EnvelopeValue.of(Status.READY.wire()),
                            EnvelopeValue.of(Status.ON_HOLD.wire())))
                    .eq("eligible", EnvelopeValue.of("automation"));
            case PERSON -> criteria.eq("status", EnvelopeValue.of(Status.READY.wire()))
                    .eq("eligible", EnvelopeValue.of("person"));
            case NOTHING -> criteria.anyOf("status", List.of(
                    EnvelopeValue.of(Status.COMPLETED.wire()),
                    EnvelopeValue.of(Status.FAILED.wire()),
                    EnvelopeValue.of(Status.CANCELLED.wire())));
        }
        return criteria;
    }

    /** The statuses a run still owed something can be in, for "is it open". */
    public static List<Status> open() {
        return List.of(Status.READY, Status.IN_PROGRESS, Status.ON_HOLD);
    }
}
