package cloud.jengu.dbo.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 * Container entrypoint: in-cluster k8s config, environment-driven Postgres
 * admin access (the scoped {@code dbo_provisioner} role's credentials from
 * the pod's Secret mount), then the poll loop until the pod stops.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        String namespace = env("DBO_NAMESPACE", "dbo");
        String adminUrl = require("DBO_ADMIN_JDBC_URL");
        String adminUser = require("DBO_ADMIN_USER");
        String adminPassword = require("DBO_ADMIN_PASSWORD");
        String tenantUrlBase = env("DBO_TENANT_JDBC_URL_BASE",
                adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1));
        long intervalMillis = Long.parseLong(env("DBO_RECONCILE_INTERVAL_MS", "15000"));

        KubernetesClient client = new KubernetesClientBuilder().build(); // in-cluster config
        try (TenantOperator operator = new TenantOperator(client, namespace,
                adminUrl, adminUser, adminPassword, tenantUrlBase)) {
            String rpRedirects = System.getenv("DBO_RP_REDIRECT_URIS");
            String rpIssuerBase = System.getenv("DBO_RP_ISSUER_BASE");
            if (rpRedirects != null && !rpRedirects.isBlank()
                    && rpIssuerBase != null && !rpIssuerBase.isBlank()) {
                operator.rpConfig(java.util.List.of(rpRedirects.split(",")), rpIssuerBase);
                operator.rpClientId(System.getenv("DBO_RP_CLIENT_ID"));
            }
            operator.ensureCrd();
            operator.start(intervalMillis);
            System.out.println("dbo-operator reconciling TenantRegistrations in namespace "
                    + namespace + " every " + intervalMillis + "ms");
            Thread.currentThread().join(); // SIGTERM ends the JVM; k8s owns the lifecycle
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable " + name);
        }
        return value;
    }
}
