package cloud.jengu.dbo.promise;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * One testable business promise — proven, not claimed.
 *
 * <p>Declared exactly once, as a constant of a {@link Catalogue}-annotated
 * enum; the constant's name is the code and the constructor carries the one
 * promise text. Tests cite the constant (each product's own {@code @Proving}
 * annotation is typed to its own enum, so a wrong citation is a compile
 * error), and production code cites the same constant where it enforces the
 * promise — a refusal names what it refuses for.
 *
 * <p>A promise nobody has stated yet is a {@link #gap(String)}: it compiles,
 * registers through the classification that declares it, carries a stable
 * synthetic code, and counts against coverage until promoted to a named
 * constant (REQ-DBO-PRM-GAP-IS-FIRST-CLASS). Unknown ground is named, never
 * silent.
 */
public interface Promise extends Coded {

    /** The promise, as one business-readable sentence. */
    String text();

    /** True for a {@link #gap(String)} — ground nobody has stated yet. */
    default boolean gap() {
        return false;
    }

    /**
     * A review-based assurance note, or null. Declared on the CONSTANT, not
     * at a proof site: a promise assured by review rather than by an
     * executable test carries that fact as its own property — there is no
     * test to hang it on, and status stays derived
     * (REQ-DBO-PRM-STATUS-IS-DERIVED).
     */
    default String assurance() {
        return null;
    }

    /**
     * A promise nobody has stated yet, named by the text of what is missing.
     *
     * <p>Declared inside a classification's promise list — where the hole was
     * noticed — not in the promise enum. The code is synthetic and stable
     * ({@code GAP-} + eight hex digits of the text's hash), so reports diff
     * cleanly and a promotion to a named constant shows as exactly that; the
     * composed model qualifies it with the declaring catalogue's namespace.
     */
    static Promise gap(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a gap needs the text of what is missing — "
                    + "an unnamed unknown is the silence this type exists to end");
        }
        return new Gap(text);
    }
}

/**
 * The unstated promise. Package-private: callers meet it only as a
 * {@link Promise} that answers {@code gap() == true}.
 */
record Gap(String text) implements Promise {

    @Override
    public String code() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("GAP-");
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16))
                        .append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean gap() {
        return true;
    }
}
