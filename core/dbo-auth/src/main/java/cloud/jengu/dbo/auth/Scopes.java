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
     * written where credentials are written. There is no implicit
     * unrestricted: a credential carrying no work scope reaches no lane.
     */
    public static final String WORK = "work";

    /**
     * The erasure scope: admits the door a person's erasure is asked for
     * through, and nothing else. Outside the SMART grammar like the two above,
     * and for a sharper reason — the most consequential act this store performs
     * should not be reachable by any credential that happens to hold a broad
     * write grant. Somebody who may write every resource type still may not
     * destroy a person's key unless they were given this.
     */
    public static final String ERASURE = "erasure";

    /**
     * The identification scope: admits the door a subject is resolved,
     * adjudicated and bound through, and nothing else. Outside the SMART
     * grammar like the three above, and for the reason binding is the act no
     * read control touches — an anonymous subject has no identity to read, so
     * a credential bounded to reading resources protects nothing here.
     *
     * <p>Separate from {@link #ERASURE} rather than folded into it. Both act
     * on a person and they are opposite acts: one attaches an identity, the
     * other destroys the key that made one legible. A deployment that lets a
     * desk identify people has not thereby said that desk may erase them.
     */
    public static final String IDENTITY = "identity";

    /**
     * The fleet scope: admits the door a deployment reads routed state
     * through, and nothing else. Outside the SMART grammar like the others.
     *
     * <p>Separate from {@link #WORK} on purpose, and it is the distinction the
     * surface exists for. Participation is what a bench may <em>do</em>; this
     * is what a deployment may <em>ask about the fleet</em>, and a bench that
     * could ask would be reading about benches it has no business knowing.
     * Neither implies the other: a runner needs no view of the tree, and
     * whoever watches the tree performs no work.
     */
    public static final String FLEET = "fleet";

    /**
     * The supervisory scope: admits undoing a judgment already made about
     * work — reopening a run somebody closed — and nothing else. Outside the
     * SMART grammar like the others.
     *
     * <p><b>Separate from {@link #WORK}, and the split is the point.</b> What
     * a bench may <em>do</em> is take work and report on it; this is what a
     * supervisor may <em>undo</em>, and neither implies the other. A runner
     * that validates lab results has no business reopening runs somebody
     * judged finished, and whoever decides a close was wrong performs no work.
     * Named explicitly like {@link #ERASURE}, never implied by a broad grant:
     * a credential that speaks for the whole tenant still may not overturn a
     * closure unless somebody wrote the word down.
     *
     * <p>Bare, it supervises every step. Suffixed —
     * {@code supervise/<module>.<process>.<step>} — it bounds the holder to
     * the steps its credential names, which is the same shape {@link #WORK}
     * uses and meets the step's declared actions as the other half of reach.
     */
    public static final String SUPERVISE = "supervise";

    private static final String WORK_STEP = WORK + "/";
    private static final String SUPERVISE_STEP = SUPERVISE + "/";

    /** Validates a declared scope string (as stored on a ClientApplication). */
    public static boolean isValid(String scope) {
        return SCIM.equals(scope) || WORK.equals(scope) || ERASURE.equals(scope)
                || IDENTITY.equals(scope) || FLEET.equals(scope)
                || SUPERVISE.equals(scope)
                || isWorkStep(scope) || isSupervisedStep(scope)
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

    /** Whether these grants may undo a judgment — reach the supervisory verbs. */
    public static boolean admitsSupervision(List<String> granted) {
        return granted.contains(SUPERVISE) || granted.stream().anyMatch(Scopes::isSupervisedStep);
    }

    /**
     * Whether this credential supervises every step — the bare scope. A
     * credential naming steps supervises exactly those.
     */
    public static boolean supervisesEverything(List<String> granted) {
        return granted.contains(SUPERVISE);
    }

    /** The steps a bounded supervisory credential covers, in the order granted. */
    public static List<String> supervisedSteps(List<String> granted) {
        return granted.stream().filter(Scopes::isSupervisedStep)
                .map(scope -> scope.substring(SUPERVISE_STEP.length())).toList();
    }

    private static boolean isSupervisedStep(String scope) {
        return scope.startsWith(SUPERVISE_STEP) && scope.length() > SUPERVISE_STEP.length();
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
