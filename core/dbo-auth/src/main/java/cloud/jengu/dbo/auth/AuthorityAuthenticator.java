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
        cloud.jengu.dbo.core.api.Caller.set(context.get().clientId());
        return null;
    }
}
