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

    /**
     * The participation scope: admits the tenant's lane surface and nothing
     * else. Outside the SMART grammar for the same reason {@link #SCIM} is —
     * a participation credential is structurally blind to the store's
     * resource surface rather than filtered away from it, and a runner's
     * whole world is the lane.
     *
     * <p>Bare, it says the holder <b>is</b> the tenant and takes
     * responsibility for who it serves lanes on behalf of. Suffixed —
     * {@code work/<module>.<process>.<step>} — it bounds the holder to the
     * steps its credential covers, which is the entitlement's half of reach
     * (#77) written where credentials are written. There is no implicit
     * unrestricted: a credential carrying no work scope reaches no lane.
     */
    public static final String WORK = "work";

    private static final String WORK_STEP = WORK + "/";

    /** Validates a declared scope string (as stored on a ClientApplication). */
    public static boolean isValid(String scope) {
        return SCIM.equals(scope) || WORK.equals(scope) || isWorkStep(scope)
                || SCOPE.matcher(scope).matches();
    }

    /** Whether these grants reach the lane surface at all. */
    public static boolean admitsWork(List<String> granted) {
        return granted.contains(WORK) || granted.stream().anyMatch(Scopes::isWorkStep);
    }

    /**
     * Whether this credential is the tenant itself — the bare scope, which is
     * a deployment saying it speaks for the whole tenant and may therefore
     * serve a lane in a participant's name it has authenticated some other
     * way. A credential bounded to steps may only work as itself.
     */
    public static boolean worksAsTheTenant(List<String> granted) {
        return granted.contains(WORK);
    }

    /** The steps a bounded participation credential covers, in the order granted. */
    public static List<String> workSteps(List<String> granted) {
        return granted.stream().filter(Scopes::isWorkStep)
                .map(scope -> scope.substring(WORK_STEP.length())).toList();
    }

    private static boolean isWorkStep(String scope) {
        return scope.startsWith(WORK_STEP) && scope.length() > WORK_STEP.length();
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
