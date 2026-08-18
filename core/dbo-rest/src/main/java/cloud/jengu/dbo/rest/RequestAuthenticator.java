package cloud.jengu.dbo.rest;

/**
 * The serving surface's guard seam (§13.5). When wired, every
 * request except {@code /metadata} passes through it before dispatch.
 * The implementation lives with the tenant authority; dbo-rest only knows
 * the contract.
 */
public interface RequestAuthenticator {

    /**
     * @param authorizationHeader the raw {@code Authorization} header, or null
     * @param mutation            whether the interaction writes
     * @param resourceType        the addressed type, or null for whole-system paths
     * @return null when authorized, otherwise the denial to answer with
     */
    Denial check(String authorizationHeader, boolean mutation, String resourceType);

    /**
     * @param wwwAuthenticate value for the {@code WWW-Authenticate} header, or null
     */
    record Denial(int status, String wwwAuthenticate, String diagnostics) {}
}
