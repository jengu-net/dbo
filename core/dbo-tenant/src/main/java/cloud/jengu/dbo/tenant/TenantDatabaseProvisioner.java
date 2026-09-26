package cloud.jengu.dbo.tenant;

import javax.sql.DataSource;

/**
 * The mandatory provisioning seam: implementations
 * decide WHERE a tenant's storage lives — a database on a shared dev
 * instance (default), an operator-provisioned dedicated instance,
 * a schema on a shared database (shared tier, later).
 *
 * <p>The load-bearing property: {@link #provision} returns a
 * {@link DataSource}, never credentials — REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS
 * is enforced by the interface's shape, not by discipline. Registered as an
 * OSGi service; the runtime manager requires exactly one.
 */
public interface TenantDatabaseProvisioner {

    /**
     * The tenant's storage is not ready yet, and this is not a failure.
     *
     * <p>An implementation whose storage is prepared elsewhere — an operator
     * creating the role, the database and the Secret — says this instead of
     * waiting for it. Waiting here would be waiting on the thread that brings
     * every other tenant up, so one tenant whose storage is a minute behind
     * would be a minute nobody else's tenant moves. The scan comes round
     * again, and the tenant is COMING_UP with a reason until it does.
     */
    class NotProvisionedYet extends IllegalStateException {
        public NotProvisionedYet(String message) {
            super(message);
        }
    }

    /** Provision (or attach to) the tenant's storage. Idempotent. */
    TenantDatabase provision(TenantSpec spec);

    /**
     * Prepare (or attach to) the substrate a fleet step's queue lives on.
     * Idempotent.
     *
     * <p><b>Not the tenant path, deliberately.</b> What this makes is a
     * database the runtime owns, carrying a durable-layer bootstrap and
     * nothing else — no face, no zone, no personal-data isolation, no store
     * schema, no authority, no bootstrap credential. Going through
     * {@link #provision} would give it every one of those and make a thing
     * that is not a tenant look exactly like one to everything downstream,
     * starting with erasure.
     *
     * <p>It is the same admin connection, because a deployment that can make
     * a tenant's database can make this one and a second credential to
     * administer would be a second thing to rotate.
     *
     * <p>The default says the deployment's storage is somebody else's to
     * prepare, which is the honest answer wherever databases are operator-made
     * rather than created on demand — the same answer this interface already
     * gives for a tenant whose storage has not arrived.
     */
    default DataSource stepSubstrate(String substrate) {
        throw new NotProvisionedYet("the substrate '" + substrate + "' a fleet step's queue "
                + "lives on is not made by this provisioner, so an operator provisions it and "
                + "points the deployment at it");
    }

    /**
     * Erasure-by-drop (REQ-DBO-TEN-ERASURE-BY-DROP). Distinct from service
     * retraction: removing a tenant's spec only retracts serving; only this
     * call destroys data.
     */
    void deprovision(String tenantCode);

    /**
     * Spec retraction: stop serving, close pools — data untouched. Distinct
     * from {@link #deprovision}. Default: nothing to release.
     */
    default void release(String tenantCode) {
    }

    /**
     * @param bootstrapClientSecret secret for the tenant's bootstrap
     *                              ClientApplication (§13; the provisioner's
     *                              custody is authoritative), or null when
     *                              the deployment runs without an authority
     */
    record TenantDatabase(DataSource dataSource, String bootstrapClientSecret,
            String rpClientId, String rpClientSecret, java.util.List<String> rpRedirectUris) {
        /**
         * The client id used when a deployment provisions a relying party but
         * names none. The store does not care what the application in front of
         * it calls itself; it only has to agree with whatever wrote the custody.
         */
        public static final String DEFAULT_RP_CLIENT_ID = "dbo-rp";

        public TenantDatabase(DataSource dataSource) {
            this(dataSource, null, null, null, java.util.List.of());
        }

        public TenantDatabase(DataSource dataSource, String bootstrapClientSecret) {
            this(dataSource, bootstrapClientSecret, null, null, java.util.List.of());
        }

        /** The configured id, or the default when custody named none. */
        public String rpClientIdOrDefault() {
            return rpClientId == null || rpClientId.isBlank()
                    ? DEFAULT_RP_CLIENT_ID : rpClientId;
        }
    }
}
