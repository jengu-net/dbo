package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.sync.http.LanesHandler;

import java.util.List;
import java.util.Optional;

/**
 * Who may drive this tenant's replication.
 *
 * <p>Beside {@link WorkGrants} rather than inside it: they guard two surfaces
 * with two different rules, and one class answering both would have to be read
 * carefully to see which rule applied where.
 *
 * <p><b>The bare participation scope and nothing less.</b> A batch carries
 * whatever the travelling work names, across every process the caller lists —
 * there is no version of replication bounded to one step. So a credential
 * narrowed to steps is refused outright rather than served a smaller batch
 * than it asked for, which is the failure that would read as a lane having
 * caught up.
 */
final class ReplicationGrants implements LanesHandler.Grants {

    private final TenantAuthority authority;

    ReplicationGrants(TenantAuthority authority) {
        this.authority = authority;
    }

    @Override
    public LanesHandler.Denied of(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return new LanesHandler.Denied(401, "Bearer", "authentication required");
        }
        Optional<TenantAuthority.AuthContext> context =
                authority.validate(authorizationHeader.substring(7).trim());
        if (context.isEmpty()) {
            return new LanesHandler.Denied(401, "Bearer error=\"invalid_token\"",
                    "invalid token");
        }
        List<String> granted = context.get().scopes();
        if (!Scopes.worksAsTheTenant(granted)) {
            return new LanesHandler.Denied(403, null, Scopes.admitsWork(granted)
                    ? "replication is the tenant's own act, and this credential is bounded "
                            + "to steps"
                    : "this credential carries no participation scope");
        }
        return null;
    }
}
