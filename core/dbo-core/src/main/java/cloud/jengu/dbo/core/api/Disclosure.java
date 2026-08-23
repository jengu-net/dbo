package cloud.jengu.dbo.core.api;

/**
 * What this read is allowed to reveal about a person, and why (§15.1, #114).
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

    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();
    private static final ThreadLocal<String> PURPOSE = new ThreadLocal<>();

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

    /** What this read may reveal; {@link Mode#OMIT} when nobody said. */
    public static Mode mode() {
        Mode mode = MODE.get();
        return mode == null ? Mode.OMIT : mode;
    }

    /** What the caller said they needed it for, or null. */
    public static String purpose() {
        return PURPOSE.get();
    }

    public static void clear() {
        MODE.remove();
        PURPOSE.remove();
    }
}
