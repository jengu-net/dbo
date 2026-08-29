package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.LaneHandler;

import java.util.List;
import java.util.Optional;

/**
 * What a token reaches on the tenant's lane surface (#154).
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
final class WorkGrants implements LaneHandler.Grants {

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
        if (!Scopes.admitsWork(granted)) {
            // No implicit unrestricted, and no implicit anything else: a
            // credential minted for the resource surface reaches no lane, and
            // is told so rather than being handed an empty one — an empty
            // lane and an unentitled one look identical from the far side.
            return new LaneHandler.Denied(403, null,
                    "this credential carries no participation scope");
        }
        return new LaneHandler.Grant(context.get().clientId(), entitlementOf(granted),
                Scopes.worksAsTheTenant(granted));
    }

    /**
     * The credential's half of reach (#77). The bare scope is a host saying it
     * is the tenant; a bounded one narrows to exactly the steps it names, and
     * the lane enforces the intersection with what each step admits.
     */
    private static Lane.Entitlement entitlementOf(List<String> granted) {
        if (Scopes.worksAsTheTenant(granted)) {
            return Lane.Entitlement.everything();
        }
        return Lane.Entitlement.ofSteps(Scopes.workSteps(granted).toArray(String[]::new));
    }
}
