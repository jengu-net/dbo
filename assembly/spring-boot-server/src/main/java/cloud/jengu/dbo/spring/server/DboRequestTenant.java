package cloud.jengu.dbo.spring.server;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Which tenant a request to the application's own API addresses.
 *
 * <p>The application's to say, because only it knows its own paths. A bearer
 * token is checked by the tenant the request addresses, the way the store's
 * own doors check it, and never by the tenant the token names: a token from
 * one tenant accepted where another is addressed is a way into the second
 * that the store would have refused.
 *
 * <p>Empty for a request that addresses no tenant, and that request carries
 * no tenant's token.
 */
@FunctionalInterface
public interface DboRequestTenant {

    Optional<String> of(HttpServletRequest request);
}
