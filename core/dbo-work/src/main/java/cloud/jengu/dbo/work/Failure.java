package cloud.jengu.dbo.work;

/**
 * Which kind of failure this is, and therefore where the work goes next
 * (REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS).
 *
 * <p>A record that is wrong ends the work: trying again would be refused in
 * the same words. A system that did not answer, and a claim nobody extended,
 * may pass — but only the step knows whether its do, so whether automation is
 * given the work again is the step's declared retry, never this class's
 * guess. Anything else is a failure nobody said would pass, and it goes to a
 * person: an unknown fault that recurs is what somebody should see, not what
 * a machine should keep trying without end.
 *
 * <p>Classified from the <b>exception class</b> rather than from a message,
 * because the line is already drawn in the type system and a parallel taxonomy
 * would drift from it.
 */
public enum Failure {

    /** The thing being processed is wrong, and re-running will not change that. */
    RECORD,

    /**
     * Something the work depends on did not answer — the store, or a system
     * reached over I/O — and may next time.
     */
    UNREACHABLE,

    /** Whoever held the work stopped extending its claim, and said nothing about why. */
    LAPSED,

    /** Not known to be either, so not known to pass. */
    UNKNOWN;

    /**
     * The class of a thrown failure.
     *
     * <p>An {@link IllegalArgumentException} — which is what a refusal to accept
     * a payload arrives as here — is the record's, and so is every refusal the
     * engine states in its own words: an unknown type, a handling rule, a policy,
     * an identity that belongs to something else. None of them changes by being
     * tried again.
     *
     * <p>The store saying it could not be reached, and any I/O failure, is
     * unreachable. Anything else is unknown. It used to be called transient,
     * as the safe direction for a person's queue — and it was retried silently
     * and without end, which is not safe for anybody waiting on the work.
     */
    public static Failure of(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException
                    || t instanceof cloud.jengu.dbo.core.api.UnknownTypeException
                    || t instanceof cloud.jengu.dbo.core.api.HandlingRefusedException
                    || t instanceof cloud.jengu.dbo.core.api.PolicyViolationException
                    || t instanceof cloud.jengu.dbo.core.api.IdentityConflictException) {
                return RECORD;
            }
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof cloud.jengu.dbo.core.api.StoreUnreachableException
                    || t instanceof java.io.IOException
                    || t instanceof java.io.UncheckedIOException) {
                return UNREACHABLE;
            }
        }
        return UNKNOWN;
    }

    /**
     * Who an item of this class waits for: a sweep's outcome that may pass is
     * held for the next pass, and every other is a person's card.
     */
    public Holder holder() {
        return this == UNREACHABLE ? Holder.RETRY : Holder.PERSON;
    }

    /** The word, as an item records it and as a step's retry names a fault. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The failure a word names, or null for none. */
    public static Failure ofWire(String wire) {
        return wire == null ? null : valueOf(wire.toUpperCase(java.util.Locale.ROOT));
    }
}
