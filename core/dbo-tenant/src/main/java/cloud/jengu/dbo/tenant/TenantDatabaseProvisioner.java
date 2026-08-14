package cloud.jengu.dbo.tenant;

import javax.sql.DataSource;

/**
 * The mandatory provisioning seam (dbo#17, Alan's design): implementations
 * decide WHERE a tenant's storage lives — a database on a shared dev
 * instance (default), an operator-provisioned dedicated instance (Slice B),
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
    record TenantDatabase(DataSource dataSource, String bootstrapClientSecret) {
        public TenantDatabase(DataSource dataSource) {
            this(dataSource, null);
        }
    }
}
