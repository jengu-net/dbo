package cloud.jengu.dbo.runner.transport;

import cloud.jengu.dbo.runner.Lane;

/**
 * Who is asking, as the store decided it: a grant, or the denial to answer
 * with.
 *
 * <p>Decided by the store, never by the transport. A transport hands over
 * what the caller presented — a token, or a signature over the ask's bytes —
 * and the store's {@link Grants} or {@link SignedGrants} turn it into this.
 */
public sealed interface Access permits Access.Grant, Access.Denied {

    /**
     * A credential's reach, decided where credentials are understood.
     *
     * @param clientId    who presented it — the only executor name a bounded
     *                    credential may claim as
     * @param entitlement what it covers, stated rather than defaulted
     * @param isTheTenant whether this credential is the tenant itself, and so
     *                    may serve a lane in another participant's name
     */
    record Grant(String clientId, Lane.Entitlement entitlement, boolean isTheTenant)
            implements Access {
    }

    /** Why not, in the terms HTTP answers in, whatever carried the ask. */
    record Denied(int status, String wwwAuthenticate, String reason) implements Access {
    }
}
