package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.k8s.KubernetesSecretProvisioner;
import cloud.jengu.dbo.tenant.k8s.SpecDirSync;
import cloud.jengu.dbo.operator.TenantOperator;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#18 Slice B on a REAL Kubernetes API server (k3s) with the Postgres
 * instance OUTSIDE the cluster — matching the Hetzner topology (host-native
 * PG). The operator runs with the SCOPED dbo_provisioner role
 * (CREATEDB CREATEROLE, not superuser). The serving side is the unchanged
 * dbo#17 manager, fed by SpecDirSync and pooled by
 * KubernetesSecretProvisioner — the tenant role's own credentials, never
 * the admin's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorIT {

    static final String NS = "dbo";
    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static K3sContainer k3s;
    static KubernetesClient client;
    static TenantOperator operator;
    static String provisionerUrl;
    static Path dir;
    static KubernetesSecretProvisioner provisioner;
    static SpecDirSync sync;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.4-k3s1"));
        postgres.start();
        k3s.start();

        // the Hetzner pattern's scoped role: CREATEDB CREATEROLE, NOT superuser
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "CREATE ROLE dbo_provisioner LOGIN PASSWORD 'prov-secret' CREATEDB CREATEROLE")) {
            ps.execute();
        }
        provisionerUrl = postgres.getJdbcUrl()
                .substring(0, postgres.getJdbcUrl().lastIndexOf('/') + 1) + "postgres";

        client = new KubernetesClientBuilder()
                .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml())).build();
        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName(NS).endMetadata().build()).create();

        operator = new TenantOperator(client, NS,
                provisionerUrl, "dbo_provisioner", "prov-secret",
                provisionerUrl.substring(0, provisionerUrl.lastIndexOf('/') + 1));
        operator.ensureCrd();

        dir = Files.createTempDirectory("dbo-operator-specs");
        provisioner = new KubernetesSecretProvisioner(client, NS, 30_000);
        sync = new SpecDirSync(client, NS, dir);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
        if (operator != null) {
            operator.close();
        }
        if (client != null) {
            client.close();
        }
        if (k3s != null) {
            k3s.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static GenericKubernetesResource registration(String code, String deletionPolicy) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("jengu.cloud/v1alpha1");
        cr.setKind("TenantRegistration");
        cr.setMetadata(new ObjectMetaBuilder().withName(code).withNamespace(NS).build());
        cr.setAdditionalProperty("spec", Map.of(
                "code", code,
                "fhirVersion", "r4",
                "deletionPolicy", deletionPolicy,
                "types", List.of(
                        Map.of("name", "Patient", "identity", "identifier", "systems", List.of(EID)),
                        Map.of("name", "Observation", "identity", "internal"))));
        return cr;
    }

    private void createAndReconcile(String code, String deletionPolicy) {
        client.genericKubernetesResources(TenantOperator.CRD_CONTEXT).inNamespace(NS)
                .resource(registration(code, deletionPolicy)).create();
        operator.reconcileOnce();
    }

    private long countIn(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                provisionerUrl, "dbo_provisioner", "prov-secret");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** CR → scoped role provisions db+role, Secret works with ITS creds, CM entry, Ready. */
    @Test
    @Order(1)
    @Timeout(300)
    void aRegistrationBecomesDatabaseRoleSecretAndConfigMapEntry() throws Exception {
        createAndReconcile("opitenant", "Retain");

        assertEquals(1, countIn("SELECT count(*) FROM pg_database WHERE datname = 'tenant_opitenant'"));
        assertEquals(1, countIn("SELECT count(*) FROM pg_roles WHERE rolname = 'tenant_opitenant'"));

        // the Secret's OWN credentials connect (tenant role, not admin)
        Secret secret = client.secrets().inNamespace(NS).withName("tenant-opitenant-db").get();
        assertNotNull(secret);
        assertEquals("opitenant", secret.getMetadata().getLabels().get(TenantOperator.TENANT_LABEL));
        // §13 bootstrap client custody rides the same Secret
        assertEquals("tenant-bootstrap", decode(secret, "client_id"));
        assertTrue(decode(secret, "client_secret").length() >= 24);
        try (Connection c = DriverManager.getConnection(
                decode(secret, "url"), decode(secret, "user"), decode(secret, "password"));
             PreparedStatement ps = c.prepareStatement("SELECT current_user");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals("tenant_opitenant", rs.getString(1));
        }

        // hba's samerole scope: every provisioned role is enrolled in `tenants`
        assertEquals(1, countIn("""
                SELECT count(*) FROM pg_auth_members m
                JOIN pg_roles g ON g.oid = m.roleid
                JOIN pg_roles r ON r.oid = m.member
                WHERE g.rolname = 'tenants' AND r.rolname = 'tenant_opitenant'"""));

        // R3 applied by this provisioner too
        assertEquals(1, countIn("""
                SELECT count(*) FROM pg_db_role_setting s
                JOIN pg_database d ON d.oid = s.setdatabase
                WHERE d.datname = 'tenant_opitenant' AND s.setrole = 0
                  AND 'transaction_timeout=300s' = ANY(s.setconfig)"""));

        String status = client.genericKubernetesResources(TenantOperator.CRD_CONTEXT)
                .inNamespace(NS).withName("opitenant").get().get("status", "phase");
        assertEquals("Ready", status);
        assertTrue(client.configMaps().inNamespace(NS).withName(TenantOperator.CONFIGMAP)
                .get().getData().containsKey("opitenant.json"));

        // §14/§15 blocks survive the CRD schema AND the re-emit (the slice E
        // pruning gap) — proven on a dedicated registration
        GenericKubernetesResource poliis = new GenericKubernetesResource();
        poliis.setApiVersion("jengu.cloud/v1alpha1");
        poliis.setKind("TenantRegistration");
        poliis.setMetadata(new ObjectMetaBuilder().withName("poliis").withNamespace(NS).build());
        poliis.setAdditionalProperty("spec", Map.of(
                "code", "poliis", "fhirVersion", "r4", "deletionPolicy", "Delete",
                "pdi", true,
                "audit", Map.of("level", "writes"),
                "writeDiscipline", Map.of("default", "append-only"),
                "retention", Map.of("perType", Map.of("Observation", Map.of("removeAfter", "P30D"))),
                "types", List.of(Map.of("name", "Patient", "identity", "internal"))));
        client.genericKubernetesResources(TenantOperator.CRD_CONTEXT).inNamespace(NS)
                .resource(poliis).create();
        operator.reconcileOnce();
        String specJson = client.configMaps().inNamespace(NS).withName(TenantOperator.CONFIGMAP)
                .get().getData().get("poliis.json");
        assertTrue(specJson.contains("\"pdi\":true")
                && specJson.contains("\"audit\"") && specJson.contains("\"writes\"")
                && specJson.contains("\"append-only\"") && specJson.contains("\"P30D\""), specJson);
        cloud.jengu.dbo.tenant.TenantSpec parsed = cloud.jengu.dbo.tenant.TenantSpec.parse(specJson);
        assertTrue(parsed.pdi() && parsed.policies().auditsWrites());
        // retract it so later serving tests see only opitenant
        client.genericKubernetesResources(TenantOperator.CRD_CONTEXT)
                .inNamespace(NS).withName("poliis").delete();
        operator.reconcileOnce();
    }

    /** Full chain: ConfigMap → SpecDirSync → #17 manager + secret-backed pool → live endpoint. */
    @Test
    @Order(2)
    @Timeout(300)
    void theUnchangedTenantManagerServesFromOperatorProvisionedState() throws Exception {
        assertEquals(Set.of("opitenant.json"), sync.syncOnce());
        assertEquals(Set.of("opitenant"), manager.scanOnce());

        String base = manager.baseUrl("opitenant");
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/Patient"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Patient",
                                 "identifier":[{"system":"%s","value":"47101010033"}],
                                 "name":[{"family":"Operaator"}]}""".formatted(EID)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        assertTrue(http.send(HttpRequest.newBuilder(URI.create(base + "/Patient?family=operaator")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body().contains("Operaator"));
    }

    /** Retain policy: deregistration stops serving; database, role and Secret survive; re-registration reattaches. */
    @Test
    @Order(3)
    @Timeout(300)
    void retainDeletionStopsServingButKeepsDataAndReRegistrationReattaches() throws Exception {
        String passwordBefore = decode(
                client.secrets().inNamespace(NS).withName("tenant-opitenant-db").get(), "password");

        client.genericKubernetesResources(TenantOperator.CRD_CONTEXT)
                .inNamespace(NS).withName("opitenant").delete();
        operator.reconcileOnce(); // finalizer executes Retain

        assertNull(client.genericKubernetesResources(TenantOperator.CRD_CONTEXT)
                .inNamespace(NS).withName("opitenant").get(), "finalizer must let the CR go");
        assertFalse(client.configMaps().inNamespace(NS).withName(TenantOperator.CONFIGMAP)
                .get().getData().containsKey("opitenant.json"));
        assertEquals(Set.of(), sync.syncOnce());
        assertEquals(Set.of(), manager.scanOnce());
        // Retain: everything below the registration survives
        assertEquals(1, countIn("SELECT count(*) FROM pg_database WHERE datname = 'tenant_opitenant'"));
        assertNotNull(client.secrets().inNamespace(NS).withName("tenant-opitenant-db").get());

        // re-registration reattaches: same secret password, data intact
        createAndReconcile("opitenant", "Retain");
        assertEquals(passwordBefore, decode(
                client.secrets().inNamespace(NS).withName("tenant-opitenant-db").get(), "password"));
        sync.syncOnce();
        manager.scanOnce();
        String found = http.send(HttpRequest.newBuilder(URI.create(
                        manager.baseUrl("opitenant") + "/Patient?family=operaator")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(found.contains("Operaator"), "data must survive Retain deregistration");
    }

    /** Delete policy: erasure-by-drop — database, role and Secret are gone. */
    @Test
    @Order(4)
    @Timeout(300)
    void deleteDeletionDropsDatabaseRoleAndSecret() throws Exception {
        createAndReconcile("kaduja", "Delete");
        assertEquals(1, countIn("SELECT count(*) FROM pg_database WHERE datname = 'tenant_kaduja'"));

        client.genericKubernetesResources(TenantOperator.CRD_CONTEXT)
                .inNamespace(NS).withName("kaduja").delete();
        operator.reconcileOnce();

        assertEquals(0, countIn("SELECT count(*) FROM pg_database WHERE datname = 'tenant_kaduja'"));
        assertEquals(0, countIn("SELECT count(*) FROM pg_roles WHERE rolname = 'tenant_kaduja'"));
        assertNull(client.secrets().inNamespace(NS).withName("tenant-kaduja-db").get());
        assertFalse(client.configMaps().inNamespace(NS).withName(TenantOperator.CONFIGMAP)
                .get().getData().containsKey("kaduja.json"));
    }

    private static String decode(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
