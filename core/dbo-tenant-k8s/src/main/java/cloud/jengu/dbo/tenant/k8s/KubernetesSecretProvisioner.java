package cloud.jengu.dbo.tenant.k8s;

import cloud.jengu.dbo.tenant.TenantDatabaseProvisioner;
import cloud.jengu.dbo.tenant.TenantSpec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dbo#17 provisioning seam, in-cluster edition (dbo#18): the OPERATOR
 * creates roles and databases; this provisioner only waits for the tenant's
 * Secret and builds a pool from it. Serving code stays credential-blind —
 * it receives a DataSource, and the credentials it rides are the tenant
 * role's own, never an admin's.
 *
 * <p>{@code deprovision} deletes the TenantRegistration CR; the operator's
 * finalizer executes the CR's deletion policy (Retain vs Delete). Policy
 * lives with the registration, not with the caller.
 */
public final class KubernetesSecretProvisioner implements TenantDatabaseProvisioner, AutoCloseable {

    private final KubernetesClient k8s;
    private final String namespace;
    private final long secretWaitMillis;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public KubernetesSecretProvisioner(KubernetesClient k8s, String namespace) {
        this(k8s, namespace, 60_000);
    }

    public KubernetesSecretProvisioner(KubernetesClient k8s, String namespace, long secretWaitMillis) {
        this.k8s = k8s;
        this.namespace = namespace;
        this.secretWaitMillis = secretWaitMillis;
    }

    @Override
    public TenantDatabase provision(TenantSpec spec) {
        Secret secret = awaitSecret(TenantK8sContract.secretName(spec.code()));
        HikariDataSource pool = pools.computeIfAbsent(spec.code(), code -> {
            HikariConfig config = new HikariConfig();
            // spike #1 landmine: in OSGi, DriverManager cannot see the driver
            // bundle's registration from this bundle's Hikari — name the class
            // so Hikari loads it through our wiring (org.postgresql imported)
            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl(decode(secret, "url"));
            config.setUsername(decode(secret, "user"));
            config.setPassword(decode(secret, "password"));
            config.setMaximumPoolSize(8);
            config.setPoolName("dbo-tenant-" + code);
            return new HikariDataSource(config);
        });
        return new TenantDatabase(pool);
    }

    @Override
    public void release(String tenantCode) {
        HikariDataSource pool = pools.remove(tenantCode);
        if (pool != null) {
            pool.close();
        }
    }

    @Override
    public void deprovision(String tenantCode) {
        release(tenantCode);
        k8s.genericKubernetesResources(TenantK8sContract.CRD_CONTEXT)
                .inNamespace(namespace).withName(tenantCode).delete();
    }

    private Secret awaitSecret(String name) {
        long deadline = System.currentTimeMillis() + secretWaitMillis;
        while (true) {
            Secret secret = k8s.secrets().inNamespace(namespace).withName(name).get();
            if (secret != null && secret.getData() != null
                    && secret.getData().keySet().containsAll(java.util.Set.of("url", "user", "password"))) {
                return secret;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("tenant secret " + name + " not present within "
                        + secretWaitMillis + "ms — is the operator running?");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting secret " + name, e);
            }
        }
    }

    private static String decode(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
