package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.rest.RequestAuthenticator;

import java.util.Optional;

/**
 * Guards a tenant's store surface with its OWN authority (§13.5): only
 * bearer JWTs verifying against this tenant's keys pass — a cross-tenant
 * token is indistinguishable from garbage (wrong issuer, wrong keys), which
 * is the strongest anti-enumeration property available. Scope checks use
 * the SMART system grammar.
 */
public final class AuthorityAuthenticator implements RequestAuthenticator {

    private final TenantAuthority authority;

    public AuthorityAuthenticator(TenantAuthority authority) {
        this.authority = authority;
    }

    @Override
    public Denial check(String authorizationHeader, boolean mutation, String resourceType) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return new Denial(401, "Bearer", "authentication required");
        }
        Optional<TenantAuthority.AuthContext> context =
                authority.validate(authorizationHeader.substring(7).trim());
        if (context.isEmpty()) {
            return new Denial(401, "Bearer error=\"invalid_token\"", "invalid token");
        }
        if (!Scopes.allows(context.get().scopes(), resourceType, mutation)) {
            return new Denial(403, null, "insufficient scope for "
                    + (mutation ? "writing " : "reading ") + resourceType);
        }
        // What the token says this access is for. Absent, the store's default
        // stands and the read omits identity — a caller that asked for nothing
        // gets nothing identifying, which is the point of the default.
        // Present, it selects the disclosing mode and is recorded; it is NOT
        // what permits the read, which the scopes above already decided.
        if (context.get().purposeOfUse() != null) {
            cloud.jengu.dbo.core.api.Disclosure.set(
                    cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE,
                    context.get().purposeOfUse());
        }
        if (context.get().audience() != null) {
            // A partner's credential is answered as the audience the managed
            // tenant declared for it: what it may see of each type is the
            // tenant's word, fixed rather than capped.
            cloud.jengu.dbo.core.api.Audience.serving(context.get().audience());
        }
        if (context.get().organisations() != null) {
            // The token's reach becomes the request's: the policy layer
            // constrains what comes back to the organisations the grants were
            // made at. Absent, nothing is bound — the shape every token had
            // before organisations became an axis.
            cloud.jengu.dbo.core.api.Reach.bind(
                    java.util.Set.copyOf(context.get().organisations()));
        }
        if (context.get().actClient() != null) {
            // §16.4: a process acting in the name of a human — record both
            cloud.jengu.dbo.core.api.Caller.setChain(
                    context.get().actClient(), context.get().fhirUser());
        } else {
            cloud.jengu.dbo.core.api.Caller.set(context.get().fhirUser() != null
                    ? context.get().fhirUser() : context.get().clientId());
        }
        return null;
    }
}
