package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.auth.TenantAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.util.Optional;

/**
 * Bearer tokens on the application's own APIs, accepted exactly where the
 * store's own doors would accept them.
 *
 * <p>Asked of the tenant the request addresses, through that tenant's
 * authority, in-process. No discovery document and no key set is fetched,
 * so the application never calls itself; no list of issuers is held, so a
 * tenant that came up after the application started is accepted on its next
 * request and one that was retracted is refused on its next; and a partner's
 * token is judged by the relation the managed tenant declared, because that
 * is what the authority does with it.
 *
 * <p>Every refusal is the same {@code 401 invalid_token}: an unknown tenant,
 * one that was retracted, a token signed by other keys and one that expired
 * cannot be told apart, so the application's API reveals no more about
 * which tenants exist than the store's doors do.
 *
 * <p><b>Authentication only.</b> What the token may do is the application's
 * to decide, from the authorities and the context. And nothing is set on the
 * request's thread: the store's door sets what a token says for the store's
 * own code to read, and on the application's pooled threads it would still
 * be set for the next request.
 */
public final class DboBearerTokens implements AuthenticationManagerResolver<HttpServletRequest> {

    private final DboTenants tenants;
    private final DboRequestTenant tenantOf;

    public DboBearerTokens(DboTenants tenants, DboRequestTenant tenantOf) {
        this.tenants = tenants;
        this.tenantOf = tenantOf;
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        Optional<String> tenant = tenantOf.of(request);
        return presented -> {
            String token = ((BearerTokenAuthenticationToken) presented).getToken();
            Optional<TenantAuthority.AuthContext> accepted = tenant
                    .flatMap(tenants::authority)
                    .flatMap(authority -> authority.validate(token));
            if (accepted.isEmpty()) {
                throw new InvalidBearerTokenException("invalid token");
            }
            return new DboAuthentication(tenant.get(), token, accepted.get());
        };
    }
}
