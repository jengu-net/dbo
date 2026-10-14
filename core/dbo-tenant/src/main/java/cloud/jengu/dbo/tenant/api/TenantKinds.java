package cloud.jengu.dbo.tenant.api;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What kind of tenant a tenant is, as a label something may select it by.
 *
 * <p><b>A label, never a switch.</b> The runtime asks what it needs to know as
 * the facts a tenant publishes — where its records live, whether it has an
 * authority — and never as a kind, because a fact keeps answering for a tenant
 * nobody anticipated and a kind does not. A kind is for what selects tenants
 * coarsely from outside: billing, a hook bound to a sort of tenant, a policy
 * applied per kind. A kind no version of the store knows decides nothing in
 * it, which is why one may be any name at all.
 *
 * <p>{@code dbo.} is the store's own: it names the three ways a tenant stores
 * and serves, and a {@code dbo.} kind this version does not know is refused
 * by name. This is the one place they are spelled.
 */
public final class TenantKinds {

    /** An ordinary tenant: what every tenant is that says nothing else. */
    public static final String TENANT = "dbo.tenant";

    /** A face root: holds a version's definitions as records. */
    public static final String FACE = "dbo.face";

    /** A zone served on another face, made by the store rather than declared. */
    public static final String PROJECTION = "dbo.projection";

    /** Every kind the store knows. */
    public static final Set<String> KNOWN = Set.of(TENANT, FACE, PROJECTION);

    private static final String RESERVED = "dbo.";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private TenantKinds() {
    }

    /**
     * The kind a tenant is: the one it declares, or the one its declaration
     * makes it.
     *
     * @param code     the tenant, for every refusal
     * @param declared what it says it is, or null
     * @param faceRoot whether it holds a face's definitions
     * @throws IllegalArgumentException when the kind is malformed, a
     *                                  {@code dbo.} kind this store does not
     *                                  know, or not what the declaration makes
     *                                  the tenant
     */
    public static String resolve(String code, String declared, boolean faceRoot) {
        if (declared == null) {
            return faceRoot ? FACE : TENANT;
        }
        if (!NAME.matcher(declared).matches()) {
            throw new IllegalArgumentException(code + ": kind '" + declared + "' is not a "
                    + "name: letters, digits, '.', '_' and '-', starting with a letter or digit");
        }
        if (declared.startsWith(RESERVED) && !KNOWN.contains(declared)) {
            throw new IllegalArgumentException(code + ": kind '" + declared + "' is not one this "
                    + "store knows; the dbo. kinds are " + new java.util.TreeSet<>(KNOWN)
                    + ", and any name outside dbo. is free");
        }
        if (faceRoot != FACE.equals(declared)) {
            throw new IllegalArgumentException(code + ": kind '" + declared + "' says "
                    + (faceRoot ? "it is not a face root, and the declaration makes it one"
                            : "it is a face root, and the declaration does not say \"faceRoot\"")
                    + ": a face root is " + FACE + " and nothing else is");
        }
        return declared;
    }
}
