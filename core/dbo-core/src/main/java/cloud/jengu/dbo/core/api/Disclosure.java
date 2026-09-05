package cloud.jengu.dbo.core.api;

/**
 * What this read is allowed to reveal about a person, and why (§15.1).
 *
 * <p>A sibling of {@link Caller} and deliberately the same tiny shape: set by
 * the serving surface once the request is understood, read by the layer that
 * holds identifying data, cleared when the request ends. The engine's APIs stay
 * context-free; disclosure is a cross-cutting concern and this is its seam.
 *
 * <p><b>The default is {@link Mode#OMIT}</b>, which is the point. A caller that
 * says nothing gets a resource without identity rather than one with it, so a
 * surface that has not thought about disclosure cannot leak by inaction. Every
 * other mode is something a caller asked for.
 *
 * <p>Stating a purpose is not authorisation. Any client can claim one; what
 * permits the access is the caller's scope and role, decided before this is
 * consulted. What the purpose does is record what they said they needed it
 * for, in an audit entry that outlives the request.
 */
public final class Disclosure {

    /** What a read of a person type returns. */
    public enum Mode {
        /**
         * The resource without its identifying elements; generalised elements
         * in their coarse form. Enough to work with a record, not enough to
         * know whose it is.
         */
        OMIT,
        /**
         * The resource whole. Requires a stated purpose, and the disclosure is
         * recorded against it.
         */
        INCLUDE,
        /**
         * Identifying elements as ciphertext, decrypted by nobody here.
         *
         * <p>What the store did unconditionally before this existed, now one
         * mode among three. It is the carrier form: what an offline appliance
         * holds, and what crosses a boundary without being disclosed to
         * whatever carries it.
         */
        ENCRYPTED
    }

    private static final ThreadLocal<String> MATCHED = new ThreadLocal<>();
    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();
    private static final ThreadLocal<String> PURPOSE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SEALING = new ThreadLocal<>();

    private Disclosure() {
    }

    /**
     * @param purpose an HL7 PurposeOfUse code — {@code TREAT}, {@code ETREAT},
     *                {@code HRESCH}, {@code PATRQT}, {@code BTG}. Required for
     *                {@link Mode#INCLUDE} and refused without one.
     */
    public static void set(Mode mode, String purpose) {
        MODE.set(mode);
        if (purpose == null || purpose.isBlank()) {
            PURPOSE.remove();
        } else {
            PURPOSE.set(purpose);
        }
    }

    public static void set(Mode mode) {
        set(mode, null);
    }

    /**
     * This read is the machinery's own, to seal what it reads for a
     * participant that will open it elsewhere. The carrier form, and a
     * statement about what the read is for: a read whose result is sealed
     * before anybody present can look at it discloses nothing here, and the
     * trail records the opening where the key is used rather than this.
     */
    public static void toSeal() {
        set(Mode.ENCRYPTED, null);
        SEALING.set(Boolean.TRUE);
    }

    /** Whether the current read is one that seals its result unread. */
    public static boolean sealing() {
        return Boolean.TRUE.equals(SEALING.get());
    }

    /**
     * The mode this read is fixed at because of who it is for, if anybody.
     *
     * <p>Set beside {@link Audience} by the layer that reads the tenant's
     * declaration. It <b>replaces</b> what the caller asked for rather than
     * capping it: what a recipient receives is derivable from the tenant's
     * declaration, and a recipient that could negotiate upwards would make the
     * declaration advice.
     */
    private static final ThreadLocal<Mode> FOR_AUDIENCE = new ThreadLocal<>();

    /** Fixes this read's mode because of the audience it is being answered for. */
    public static void forAudience(Mode mode) {
        if (mode == null) {
            FOR_AUDIENCE.remove();
        } else {
            FOR_AUDIENCE.set(mode);
        }
    }

    /**
     * What this read may reveal; {@link Mode#OMIT} when nobody said.
     *
     * <p>An audience's declared mode wins over the caller's request. Asking is
     * how a tenant's own surface says what it needs; a declaration is how the
     * tenant says what somebody else gets, and only one of those two can be
     * the answer.
     */
    public static Mode mode() {
        Mode declared = FOR_AUDIENCE.get();
        if (declared != null) {
            return declared;
        }
        Mode mode = MODE.get();
        return mode == null ? Mode.OMIT : mode;
    }

    /**
     * Whether a purpose is one this store will carry.
     *
     * <p>A PurposeOfUse is a code, and this accepts the shape of one rather
     * than a list of them: which purposes a caller may claim is the
     * authority's judgement, and a store that shipped HL7's list would refuse
     * the jurisdiction-local code the next deployment needs.
     *
     * <p>What it does refuse is anything that is not a code. The purpose is
     * written verbatim into a signed token's claims and into an audit entry,
     * both of which are assembled as JSON text — so a quote or a brace in it
     * is not a strange purpose, it is a corrupted token and a trail entry that
     * says whatever the caller wanted it to say. The one field whose whole job
     * is to still be true a year later is the last one that may be attacker-shaped.
     */
    public static boolean statable(String purpose) {
        return purpose != null && !purpose.isBlank()
                && purpose.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    /** What the caller said they needed it for, or null. */
    public static String purpose() {
        return PURPOSE.get();
    }

    /**
     * Record that this request matched a person by an identifying term, as the
     * fingerprint of that term and never as the term.
     *
     * <p>The trail is append-only against everyone, so a plaintext address in
     * it would force a choice between an immutable audit and an erasure right.
     * A fingerprint forces neither: an auditor asking <i>did anybody look this
     * person up</i> computes it and looks, which is exactly their position —
     * they already hold the address.
     *
     * <p>Set where the match happens, which is innermost, and read where the
     * entry is written, which is outermost. That is the whole reason this is a
     * seam rather than a parameter.
     */
    public static void matched(String fingerprint) {
        MATCHED.set(fingerprint);
    }

    /**
     * The fingerprint this request matched on, taken rather than read.
     *
     * <p>Cleared as it is taken so it lands on the entry for the search that
     * produced it and on no other. A request that looks somebody up and then
     * reads three of their records should not have the address attached to all
     * four entries, which is what a plain getter would have done.
     */
    public static String takeMatched() {
        String fingerprint = MATCHED.get();
        MATCHED.remove();
        return fingerprint;
    }

    public static void clear() {
        MODE.remove();
        PURPOSE.remove();
        MATCHED.remove();
        FOR_AUDIENCE.remove();
        SEALING.remove();
    }
}
