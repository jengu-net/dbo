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

    private static final Pattern SCOPE = Pattern.compile("system/(\\*|[A-Za-z][A-Za-z0-9]*)\\.(read|write)");

    private Scopes() {
    }

    /** Validates a declared scope string (as stored on a ClientApplication). */
    public static boolean isValid(String scope) {
        return SCOPE.matcher(scope).matches();
    }

    public static boolean allows(List<String> granted, String resourceType, boolean mutation) {
        String action = mutation ? "write" : "read";
        return granted.contains("system/*." + action)
                || (resourceType != null && granted.contains("system/" + resourceType + "." + action));
    }
}
