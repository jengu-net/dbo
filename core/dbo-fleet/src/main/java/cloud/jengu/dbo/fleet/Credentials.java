package cloud.jengu.dbo.fleet;

import java.util.Map;
import java.util.Optional;

/**
 * The per-tenant credentials the reader holds — one client per tenant,
 * minted by whoever operates the fleet and carrying the fleet scope.
 *
 * <p>Per tenant, deliberately. A single credential that opened every tenant
 * would be the cross-tenant surface the control plane was decided never to
 * be handed; holding one secret per tenant means the reader can reach
 * exactly the tenants somebody gave it, and a tenant it was given nothing
 * for is reported as such rather than reached anyway.
 */
@FunctionalInterface
public interface Credentials {

    /** A client id and its secret, for one tenant's authority. */
    record Credential(String clientId, String secret) {}

    /** The credential for this tenant, or empty when the reader holds none. */
    Optional<Credential> forTenant(String tenantCode);

    /** A fixed map, for a test or a small deployment. */
    static Credentials of(Map<String, Credential> byTenant) {
        Map<String, Credential> fixed = Map.copyOf(byTenant);
        return code -> Optional.ofNullable(fixed.get(code));
    }

    /** Holding nothing: what a reader deployed to look and not touch has. */
    static Credentials none() {
        return code -> Optional.empty();
    }
}
