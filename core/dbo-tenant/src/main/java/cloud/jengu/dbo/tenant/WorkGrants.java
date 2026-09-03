package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.LaneHandler;

import java.util.List;
import java.util.Optional;

/**
 * What a token reaches on the tenant's lane surface.
 *
 * <p>The entitlement is derived here — from the credential, by the party that
 * understands credentials — and never asked for by the caller. That is the
 * whole of why the surface can be offered at all: the reach of a remote
 * participant is what its credential covers, decided in one place, and a bug
 * in the handler can refuse a participant but cannot widen one.
 *
 * <p>Guarded by the tenant's OWN authority, like every other surface it
 * serves (§13.5): a token from another tenant is indistinguishable from
 * garbage — wrong issuer, wrong keys — rather than being a valid token
 * refused, which is the strongest anti-enumeration property available.
 */
final class WorkGrants implements LaneHandler.Grants, LaneHandler.SignedGrants {

    private final TenantAuthority authority;

    WorkGrants(TenantAuthority authority) {
        this.authority = authority;
    }

    @Override
    public LaneHandler.Access of(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return new LaneHandler.Denied(401, "Bearer", "authentication required");
        }
        Optional<TenantAuthority.AuthContext> context =
                authority.validate(authorizationHeader.substring(7).trim());
        if (context.isEmpty()) {
            return new LaneHandler.Denied(401, "Bearer error=\"invalid_token\"", "invalid token");
        }
        List<String> granted = context.get().scopes();
        if (!reachesTheLane(granted)) {
            // No implicit unrestricted, and no implicit anything else: a
            // credential minted for the resource surface reaches no lane, and
            // is told so rather than being handed an empty one — an empty
            // lane and an unentitled one look identical from the far side.
            return new LaneHandler.Denied(403, null,
                    "this credential carries no participation scope and no supervisory scope");
        }
        return new LaneHandler.Grant(context.get().clientId(), entitlementOf(granted),
                Scopes.worksAsTheTenant(granted));
    }

    /**
     * The credential's half of reach. The bare scope is a host saying it
     * is the tenant; a bounded one narrows to exactly the steps it names, and
     * the lane enforces the intersection with what each step admits.
     */
    /**
     * The same decision from a signature: the participant named must have
     * enrolled with a signing key, the signature must be its, and its reach
     * is what its client record grants — exactly what a token would carry,
     * without a token ever lying on the plane the ask crossed.
     */
    @Override
    public LaneHandler.Access of(String participant, byte[] signed, String signature) {
        Optional<cloud.jengu.dbo.core.api.seal.SigningKey> key =
                participant == null ? Optional.empty() : authority.signingKey(participant);
        if (key.isEmpty() || !key.get().verifies(signed, signature)) {
            return new LaneHandler.Denied(401, null,
                    "an ask on the stream is signed by the participant's enrolment key");
        }
        List<String> granted = authority.clientScopes(participant).orElse(List.of());
        if (!reachesTheLane(granted)) {
            return new LaneHandler.Denied(403, null,
                    "this credential carries no participation scope and no supervisory scope");
        }
        return new LaneHandler.Grant(participant, entitlementOf(granted),
                Scopes.worksAsTheTenant(granted));
    }

    /**
     * Whether this credential reaches the lane at all — to work, to supervise,
     * or both. A supervisor is admitted with no participation scope on
     * purpose: overturning a closure is not performing a step, and requiring
     * the working scope to reach the supervisory verb would hand every
     * supervisor the right to take work as the price of correcting it.
     */
    private static boolean reachesTheLane(List<String> granted) {
        return Scopes.admitsWork(granted) || Scopes.admitsSupervision(granted);
    }

    private static Lane.Entitlement entitlementOf(List<String> granted) {
        Lane.Entitlement working = Scopes.worksAsTheTenant(granted)
                ? Lane.Entitlement.everything()
                : Lane.Entitlement.ofSteps(Scopes.workSteps(granted).toArray(String[]::new));
        // Supervision is added, never implied: a credential that speaks for
        // the whole tenant supervises nothing until the word is written down.
        if (Scopes.supervisesEverything(granted)) {
            return working.supervisingEverything();
        }
        List<String> supervised = Scopes.supervisedSteps(granted);
        return supervised.isEmpty() ? working
                : working.supervising(supervised.toArray(String[]::new));
    }
}
