package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.auth.TenantAuthority;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A bearer token a tenant's authority accepted, and what it decided.
 *
 * <p>The scopes are the authorities, as {@code SCOPE_<scope>}, which is the
 * spelling Spring Security's own resource server gives them. Everything else
 * the authority read off the token is on the {@link #context() context}, so
 * application code reads what the store decided rather than parsing the
 * token a second way.
 *
 * <p>It names the tenant that accepted it. A token is good for that tenant
 * and no other, and an application acting on another tenant's records has to
 * be able to see that it is about to.
 */
public final class DboAuthentication extends AbstractAuthenticationToken {

    private final String tenant;
    private final String token;
    private final TenantAuthority.AuthContext context;

    DboAuthentication(String tenant, String token, TenantAuthority.AuthContext context) {
        super(context.scopes().stream().map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList());
        this.tenant = tenant;
        this.token = token;
        this.context = context;
        setAuthenticated(true);
    }

    /** The tenant whose authority accepted the token. */
    public String tenant() {
        return tenant;
    }

    /** What the authority read off the token. */
    public TenantAuthority.AuthContext context() {
        return context;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    /** The person the token names where it names one, the client otherwise. */
    @Override
    public Object getPrincipal() {
        return context.fhirUser() != null ? context.fhirUser() : context.clientId();
    }
}
