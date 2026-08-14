package cloud.jengu.dbo.operator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import cloud.jengu.dbo.tenant.k8s.TenantK8sContract;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The provisioning operator (dbo#18, Slice B): reconciles
 * {@code TenantRegistration} CRs against the Hetzner pattern — a login role
 * and a database per tenant on the host-native Postgres instance, the
 * credentials as a k8s Secret, the tenant's spec JSON as a key in the
 * {@code dbo-tenants} ConfigMap (which serving pods mount as the dbo#17
 * spec directory).
 *
 * <p>Runs with a SCOPED provisioner role (CREATEDB CREATEROLE), never
 * superuser. Poll-based reconciliation — deliberately no informer machinery;
 * the CR population is small and a full sweep is the idempotent unit.
 *
 * <p>Deletion goes through a finalizer: {@code Retain} (default) removes the
 * ConfigMap entry so serving stops, but database + role + Secret survive and
 * a re-registration reattaches; {@code Delete} additionally drops database,
 * role and Secret — REQ-DBO-TEN-ERASURE-BY-DROP, operationally.
 */
public final class TenantOperator implements AutoCloseable {

    public static final ResourceDefinitionContext CRD_CONTEXT = TenantK8sContract.CRD_CONTEXT;
    public static final String CONFIGMAP = TenantK8sContract.CONFIGMAP;
    public static final String FINALIZER = "jengu.cloud/tenant-protection";
    public static final String TENANT_LABEL = TenantK8sContract.TENANT_LABEL;

    private static final String DUPLICATE_OBJECT = "42710";
    private static final String DUPLICATE_DATABASE = "42P04";

    private final KubernetesClient k8s;
    private final String namespace;
    private final String adminUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String tenantUrlBase;
    private final SecureRandom random = new SecureRandom();
    private volatile Thread loop;
    private volatile boolean running;

    /**
     * @param tenantJdbcUrlBase the JDBC url prefix pods should use to reach
     *                          the instance, ending before the database name
     *                          (e.g. {@code jdbc:postgresql://10.0.0.2:5432/})
     *                          — the operator's own admin url may differ
     *                          (different network vantage point).
     */
    public TenantOperator(KubernetesClient k8s, String namespace,
                          String adminJdbcUrl, String adminUser, String adminPassword,
                          String tenantJdbcUrlBase) {
        this.k8s = k8s;
        this.namespace = namespace;
        this.adminUrl = adminJdbcUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.tenantUrlBase = tenantJdbcUrlBase.endsWith("/") ? tenantJdbcUrlBase : tenantJdbcUrlBase + "/";
    }

    /** Idempotently installs the CRD and waits until the API serves it. */
    public void ensureCrd() {
        try (InputStream in = TenantOperator.class.getResourceAsStream("/tenantregistration-crd.yaml")) {
            CustomResourceDefinition crd = k8s.apiextensions().v1().customResourceDefinitions()
                    .load(in).item();
            k8s.resource(crd).serverSideApply();
        } catch (Exception e) {
            throw new IllegalStateException("CRD installation failed", e);
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                k8s.genericKubernetesResources(CRD_CONTEXT).inNamespace(namespace).list();
                return;
            } catch (Exception notServedYet) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted awaiting CRD", ie);
                }
            }
        }
        throw new IllegalStateException("CRD not served within 30s");
    }

    /** One idempotent sweep over all registrations. Returns codes touched. */
    public List<String> reconcileOnce() {
        List<String> touched = new ArrayList<>();
        for (GenericKubernetesResource cr : k8s.genericKubernetesResources(CRD_CONTEXT)
                .inNamespace(namespace).list().getItems()) {
            String code = str(cr, "spec", "code");
            try {
                if (cr.getMetadata().getDeletionTimestamp() != null) {
                    finalizeDeletion(cr, code);
                } else {
                    reconcile(cr, code);
                }
                touched.add(code);
            } catch (Exception e) {
                setStatus(cr, "Error", e.getMessage());
            }
        }
        return touched;
    }

    public void start(long intervalMillis) {
        running = true;
        loop = Thread.ofVirtual().name("dbo-operator").start(() -> {
            while (running) {
                try {
                    reconcileOnce();
                } catch (Exception sweepFailed) {
                    // next sweep retries; poll reconciliation is self-healing
                }
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void reconcile(GenericKubernetesResource cr, String code) {
        if (!cr.getMetadata().getFinalizers().contains(FINALIZER)) {
            cr.getMetadata().getFinalizers().add(FINALIZER);
            cr = k8s.genericKubernetesResources(CRD_CONTEXT).inNamespace(namespace)
                    .resource(cr).update();
        }
        String role = "tenant_" + code;
        String secretName = secretName(code);
        String password = existingPassword(secretName).orElseGet(this::newPassword);

        try (Connection c = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
            // role first; membership lets the scoped provisioner act as the
            // owner later (ALTER DATABASE settings, policy-driven drops)
            execIgnoring(c, "CREATE ROLE " + role + " LOGIN PASSWORD " + quoteLiteral(password),
                    DUPLICATE_OBJECT);
            exec(c, "ALTER ROLE " + role + " PASSWORD " + quoteLiteral(password));
            execIgnoring(c, "GRANT " + role + " TO " + quoteIdent(adminUser), DUPLICATE_OBJECT);
            // the `tenants` NOLOGIN group scopes pg_hba's samerole rule to
            // operator-provisioned roles only — a bare `samerole all` would
            // also admit the superuser remotely. Operator-owned: creating it
            // here (PG16 auto-ADMIN for the creator) keeps the grant below
            // working without any bootstrap coupling.
            execIgnoring(c, "CREATE ROLE tenants NOLOGIN", DUPLICATE_OBJECT);
            execIgnoring(c, "GRANT tenants TO " + role, DUPLICATE_OBJECT);
            execIgnoring(c, "CREATE DATABASE " + role + " OWNER " + role, DUPLICATE_DATABASE);
            // dbo#18 R3, mirrored from the local provisioner: worst-case feed
            // delay becomes the timeout, by construction
            exec(c, "ALTER DATABASE " + role + " SET idle_in_transaction_session_timeout = '60s'");
            exec(c, "ALTER DATABASE " + role + " SET transaction_timeout = '300s'");
            exec(c, "ALTER ROLE " + quoteIdent(adminUser) + " SET transaction_timeout = '0'");
        } catch (SQLException e) {
            throw new IllegalStateException("provisioning SQL failed for " + code, e);
        }

        Secret secret = new SecretBuilder()
                .withNewMetadata().withName(secretName).withNamespace(namespace)
                .addToLabels(TENANT_LABEL, code).endMetadata()
                .addToStringData("url", tenantUrlBase + role)
                .addToStringData("user", role)
                .addToStringData("password", password)
                .build();
        k8s.secrets().inNamespace(namespace).resource(secret).serverSideApply();

        upsertConfigMapEntry(code + ".json", specJson(cr));
        setStatus(cr, "Ready", null);
    }

    private void finalizeDeletion(GenericKubernetesResource cr, String code) {
        if (!cr.getMetadata().getFinalizers().contains(FINALIZER)) {
            return; // nothing left to do; k8s completes the deletion
        }
        removeConfigMapEntry(code + ".json"); // serving stops under BOTH policies

        if ("Delete".equals(str(cr, "spec", "deletionPolicy"))) {
            String role = "tenant_" + code;
            try (Connection c = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
                exec(c, "DROP DATABASE IF EXISTS " + role + " WITH (FORCE)");
                exec(c, "DROP ROLE IF EXISTS " + role);
            } catch (SQLException e) {
                throw new IllegalStateException("erasure failed for " + code, e);
            }
            k8s.secrets().inNamespace(namespace).withName(secretName(code)).delete();
        }
        cr.getMetadata().getFinalizers().remove(FINALIZER);
        k8s.genericKubernetesResources(CRD_CONTEXT).inNamespace(namespace).resource(cr).update();
    }

    // --- k8s pieces ---

    public static String secretName(String code) {
        return TenantK8sContract.secretName(code);
    }

    private java.util.Optional<String> existingPassword(String secretName) {
        Secret existing = k8s.secrets().inNamespace(namespace).withName(secretName).get();
        if (existing == null || existing.getData() == null
                || !existing.getData().containsKey("password")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new String(
                Base64.getDecoder().decode(existing.getData().get("password")),
                StandardCharsets.UTF_8));
    }

    private void upsertConfigMapEntry(String key, String value) {
        ConfigMap cm = k8s.configMaps().inNamespace(namespace).withName(CONFIGMAP).get();
        if (cm == null) {
            cm = new ConfigMapBuilder()
                    .withNewMetadata().withName(CONFIGMAP).withNamespace(namespace).endMetadata()
                    .addToData(key, value)
                    .build();
            k8s.configMaps().inNamespace(namespace).resource(cm).create();
        } else {
            Map<String, String> data = cm.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(cm.getData());
            data.put(key, value);
            cm.setData(data);
            k8s.configMaps().inNamespace(namespace).resource(cm).update();
        }
    }

    private void removeConfigMapEntry(String key) {
        ConfigMap cm = k8s.configMaps().inNamespace(namespace).withName(CONFIGMAP).get();
        if (cm == null || cm.getData() == null || !cm.getData().containsKey(key)) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>(cm.getData());
        data.remove(key);
        cm.setData(data);
        k8s.configMaps().inNamespace(namespace).resource(cm).update();
    }

    private void setStatus(GenericKubernetesResource cr, String phase, String message) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("phase", phase);
        if (message != null) {
            status.put("message", message);
        }
        cr.setAdditionalProperty("status", status);
        try {
            k8s.genericKubernetesResources(CRD_CONTEXT).inNamespace(namespace).resource(cr).update();
        } catch (Exception statusRace) {
            // next sweep re-reads and re-stamps
        }
    }

    /** Re-emits the CR spec in the dbo#17 spec-file format the manager parses. */
    @SuppressWarnings("unchecked")
    static String specJson(GenericKubernetesResource cr) {
        Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
        StringBuilder sb = new StringBuilder("{\"code\":").append(jsonString((String) spec.get("code")))
                .append(",\"fhirVersion\":").append(jsonString((String) spec.get("fhirVersion")))
                .append(",\"types\":[");
        List<Map<String, Object>> types = (List<Map<String, Object>>) spec.get("types");
        for (int i = 0; i < types.size(); i++) {
            Map<String, Object> t = types.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(jsonString((String) t.get("name")))
                    .append(",\"identity\":").append(jsonString((String) t.get("identity")));
            Object systems = t.get("systems");
            if (systems instanceof List<?> list && !list.isEmpty()) {
                sb.append(",\"systems\":[");
                for (int j = 0; j < list.size(); j++) {
                    if (j > 0) {
                        sb.append(',');
                    }
                    sb.append(jsonString((String) list.get(j)));
                }
                sb.append(']');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // --- SQL pieces ---

    private static String str(GenericKubernetesResource cr, String... path) {
        Object v = cr.get((Object[]) path);
        return v == null ? null : v.toString();
    }

    private void exec(Connection c, String ddl) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    private void execIgnoring(Connection c, String ddl, String sqlState) throws SQLException {
        try {
            exec(c, ddl);
        } catch (SQLException e) {
            if (!sqlState.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private String newPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String quoteLiteral(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
    }
}
