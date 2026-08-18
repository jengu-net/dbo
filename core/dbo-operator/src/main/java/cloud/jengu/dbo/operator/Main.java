package cloud.jengu.dbo.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container entrypoint: in-cluster k8s config, environment-driven Postgres
 * admin access (the scoped {@code dbo_provisioner} role's credentials from
 * the pod's Secret mount), then the poll loop until the pod stops.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.operator");

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
            // Startup says WHAT it is and WHERE it will act. A pod that logs
            // only "started" leaves an operator guessing which namespace it
            // took, which is the thing that is actually ever wrong.
            LOG.info("started: component=dbo-operator version={} jdk={} namespace={} "
                    + "reconcileMs={} rpProvisioning={}",
                    version(), Runtime.version(), namespace, intervalMillis,
                    rpRedirects != null && !rpRedirects.isBlank());
            // SIGTERM ends the JVM; k8s owns the lifecycle. The hook is what
            // turns a kill into a sentence instead of a silence.
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> LOG.info("shutdown requested: component=dbo-operator"),
                    "dbo-shutdown"));
            Thread.currentThread().join();
        }
    }

    /** The build's version, or a marker when running from a plain classpath. */
    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
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
