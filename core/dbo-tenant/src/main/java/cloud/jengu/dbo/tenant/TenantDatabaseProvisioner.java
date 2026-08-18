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

    /** Provision (or attach to) the tenant's storage. Idempotent. */
    TenantDatabase provision(TenantSpec spec);

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
