package cloud.jengu.dbo.auth;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The SMART system-scope grammar (REQ-DBO-AUTH-SMART-SHAPED-SCOPES):
 * {@code system/*.read}, {@code system/*.write}, {@code system/<Type>.read},
 * {@code system/<Type>.write}. Reads require a read grant for the type,
 * mutations a write grant. Deliberately only the system plane — patient/user
 * contexts arrive with their own slice, on the same grammar.
 */
public final class Scopes {

    private static final Pattern SCOPE = Pattern.compile("(system|user)/(\\*|[A-Za-z][A-Za-z0-9]*)\\.(read|write)");

    private Scopes() {
    }

    /**
     * The staff-provisioning scope: admits the SCIM surface and nothing
     * else. Deliberately OUTSIDE the SMART grammar — {@link #allows} never
     * sees it, so a directory credential is structurally blind to the
     * store's resource surface rather than filtered away from it.
     */
    public static final String SCIM = "scim";

    /** Validates a declared scope string (as stored on a ClientApplication). */
    public static boolean isValid(String scope) {
        return SCIM.equals(scope) || SCOPE.matcher(scope).matches();
    }

    public static boolean allows(List<String> granted, String resourceType, boolean mutation) {
        String action = mutation ? "write" : "read";
        for (String plane : new String[] {"system", "user"}) {
            if (granted.contains(plane + "/*." + action)
                    || (resourceType != null && granted.contains(plane + "/" + resourceType + "." + action))) {
                return true;
            }
        }
        return false;
    }
}
