package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.VaultTransit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's working key: made once, wrapped by the key its declaration names,
 * and the tenant down while that key will not unwrap it.
 *
 * <p>A world of its own because it brings tenants up, takes them down and
 * brings them up again under a second manager, against a Vault of its own
 * whose keys it rotates and deletes. Its tenants are named for this run,
 * because a tenant's database is named by its code and a database server may
 * outlive the run that made it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantsWorkingKeyIsHeldWhereItsOwnerSaysIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    /** A tenant that ran before it had a working key. */
    private static final String EARLIER = "earlier-" + RUN;
    /** A tenant whose working key its owner's Transit key wraps. */
    private static final String HELD = "held-" + RUN;

    private final HttpClient http = HttpClient.newHttpClient();
    private final byte[] kek = new byte[32];
    private GenericContainer<?> vault;
    private LocalDatabasePerTenantProvisioner databases;
    private Path dir;
    private TenantRuntimeManager manager;

    @BeforeAll
    void aDeploymentWithAVault() throws Exception {
        new java.security.SecureRandom().nextBytes(kek);
        vault = new GenericContainer<>("hashicorp/vault:1.17")
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root").withExposedPorts(8200)
                .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));
        vault.start();
        vaultPost("/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        vaultPost("/v1/transit/keys/tenant-" + HELD, "{}");
        vaultPost("/v1/transit/keys/tenant-" + EARLIER, "{}");
        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantsWorkingKeyIsHeldWhereItsOwnerSaysIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        dir = Files.createTempDirectory("dbo-working-key");
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (vault != null) {
            vault.stop();
        }
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("a tenant that ran with its keys under the deployment's key moves them under "
            + "a working key of its own as it comes up, signs on as before, and the "
            + "deployment's key opens none of them afterwards")
    @Proving(DboPromises.TEN_A_TENANTS_WORKING_KEY_IS_HELD_WHERE_ITS_DECLARATION_SAYS)
    void anEarlierTenantMovesItsKeys() throws Exception {
        // As it was before there was a working key: its authority's keys and
        // a secret wrapped by the deployment's key itself.
        DataSource before = databases.provision(TenantSpec.parse(spec(EARLIER, ""))).dataSource();
        TenantAuthority earlier = new TenantAuthority(new PgObjectStore(before,
                IdentityModel.registrations()), "http://earlier", new KeyProtector(kek));
        earlier.ensureSigningKey();
        earlier.keepSecret("lab-password", "earlier-pa55".getBytes(StandardCharsets.UTF_8));

        declare(EARLIER, "");
        up(EARLIER);
        assertEquals("earlier-pa55", secret(EARLIER, "lab-password"));
        assertTrue(tokenWorks(EARLIER), "the tenant no longer signs");
        PgObjectStore identities = new PgObjectStore(source(EARLIER),
                IdentityModel.registrations());
        assertFalse(opensUnder(kek, identities, "HeldSecret", "wrapped"),
                "the deployment's key still opens the tenant's secret");
        assertFalse(opensUnder(kek, identities, "SigningKey", "privateEnc"),
                "the deployment's key still opens the tenant's signing key");
    }

    @Test
    @Order(2)
    @DisplayName("a person vault moved under a working key keeps its people's keys and finds "
            + "them by the identifiers it indexed before")
    @Proving(DboPromises.TEN_A_TENANTS_WORKING_KEY_IS_HELD_WHERE_ITS_DECLARATION_SAYS)
    void thePeoplesKeysMoveAndTheirIndexStays() {
        DataSource source = source(EARLIER);
        PersonVault earlier = new PersonVault(source, kek);
        String person = java.util.UUID.randomUUID().toString();
        byte[] personKey = earlier.keyFor(person, true).orElseThrow();
        earlier.claim(person, List.<String[]>of(new String[] {"urn:example:nid", "39001010000"}));

        byte[] working = new byte[32];
        new java.security.SecureRandom().nextBytes(working);
        byte[] index = PersonVault.indexKeyOf(kek);
        assertEquals(1, new PersonVault(source, working, index, kek).rewrapAll());
        PersonVault moved = new PersonVault(source, working, index, null);
        assertTrue(java.util.Arrays.equals(personKey, moved.keyFor(person, false).orElseThrow()),
                "a person's key changed in the move");
        assertEquals(person, moved.findByIdentifier("urn:example:nid", "39001010000")
                .orElse(null), "the index lost the person");
        assertThrows(IllegalStateException.class,
                () -> new PersonVault(source, kek).keyFor(person, false),
                "the deployment's key still opens a person's key");
    }

    @Test
    @Order(3)
    @DisplayName("a tenant whose working key a Transit key wraps comes up once Vault unwraps "
            + "it, is rewrapped under the key's new version when that rotates, and a tenant "
            + "redeclared under one moves its working key under it")
    @Proving(DboPromises.TEN_A_TENANTS_WORKING_KEY_IS_HELD_WHERE_ITS_DECLARATION_SAYS)
    void aTransitKeyWrapsIt() throws Exception {
        declare(HELD, held(HELD));
        up(HELD);
        assertTrue(wrapped(HELD).startsWith("vault:v1:"), wrapped(HELD));
        manager.authority(HELD).keepSecret("lab-password",
                "held-pa55".getBytes(StandardCharsets.UTF_8));

        vaultPost("/v1/transit/keys/tenant-" + HELD + "/rotate", "{}");
        restart();
        up(HELD);
        assertTrue(wrapped(HELD).startsWith("vault:v2:"), wrapped(HELD));
        assertEquals("held-pa55", secret(HELD, "lab-password"));

        declare(EARLIER, held(EARLIER));
        long giveUp = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!wrapped(EARLIER).startsWith("vault:") && System.nanoTime() < giveUp) {
            manager.scanOnce();
            Thread.sleep(500);
        }
        assertTrue(wrapped(EARLIER).startsWith("vault:v1:"), wrapped(EARLIER));
        up(EARLIER);
        assertEquals("earlier-pa55", secret(EARLIER, "lab-password"));
    }

    @Test
    @Order(4)
    @DisplayName("a tenant whose Transit key is taken away is taken down within the time its "
            + "key is trusted for, does not come up again, and nothing it keeps opens under "
            + "the deployment's key")
    @Proving(DboPromises.TEN_A_TENANTS_WORKING_KEY_IS_HELD_WHERE_ITS_DECLARATION_SAYS)
    void aRevokedKeySealsTheTenant() throws Exception {
        vaultPost("/v1/transit/keys/tenant-" + HELD + "/config", "{\"deletion_allowed\":true}");
        vaultDelete("/v1/transit/keys/tenant-" + HELD);
        manager.workingKeyRecheck(Duration.ZERO);
        manager.scanOnce();
        assertTrue(manager.runtime(HELD).isEmpty(), "a tenant whose key is gone still serves");
        for (int pass = 0; pass < 3; pass++) {
            manager.scanOnce();
        }
        assertTrue(manager.runtime(HELD).isEmpty(), "a tenant whose key is gone came back");
        assertTrue(String.valueOf(manager.troubles().get(HELD)).contains("tenant-" + HELD),
                "the ledger does not say why: " + manager.troubles().get(HELD));

        PgObjectStore identities = new PgObjectStore(source(HELD),
                IdentityModel.registrations());
        assertFalse(opensUnder(kek, identities, "HeldSecret", "wrapped"),
                "the deployment's key opens what the owner's key was taken from");
        assertFalse(opensUnder(kek, identities, "SigningKey", "privateEnc"),
                "the deployment's key opens the tenant's signing key");
    }

    // ── the deployment ──

    private static String spec(String code, String extra) {
        return """
                {"code":"%s","face":"r5"%s,
                 "types":[{"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(code, extra);
    }

    private static String held(String code) {
        return ",\"workingKey\":{\"key\":\"tenant-" + code + "\"}";
    }

    private void declare(String code, String extra) throws Exception {
        Files.writeString(dir.resolve(code + ".json"), spec(code, extra));
        if (manager == null) {
            restart();
        }
    }

    private void restart() {
        if (manager != null) {
            manager.close();
        }
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.vault(new VaultTransit(vaultAddress(), "transit", () -> "root"));
    }

    private void up(String code) throws Exception {
        UntilServed.scan(manager, code);
        manager.authority(code).ensureClient("desk", "desk-secret", List.of("work"));
    }

    private String secret(String code, String name) {
        return manager.authority(code).secret(name)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    private boolean tokenWorks(String code) throws Exception {
        HttpResponse<String> issued = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + manager.port() + "/t/" + code + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=desk"
                                        + "&client_secret=desk-secret")).build(),
                HttpResponse.BodyHandlers.ofString());
        return issued.statusCode() == 200 && issued.body().contains("access_token")
                && manager.authority(code).validate(field(issued.body(), "access_token"))
                .isPresent();
    }

    /** Whether a key opens what every record of a type holds wrapped in one field. */
    private static boolean opensUnder(byte[] key, PgObjectStore identities, String type,
            String field) {
        List<StoredObject> held = identities.select(Criteria.of(type));
        assertFalse(held.isEmpty(), "no " + type + " to try");
        for (StoredObject one : held) {
            String wrapped = field(new String(one.payload(), StandardCharsets.UTF_8), field);
            try {
                new KeyProtector(key).unwrap(Base64.getDecoder().decode(wrapped));
                return true;
            } catch (IllegalStateException doesNotOpen) {
                // as it should be
            }
        }
        return false;
    }

    /** The tenant's working key as its database holds it. */
    private String wrapped(String code) {
        PgObjectStore identities = new PgObjectStore(source(code),
                IdentityModel.registrations());
        return identities.select(Criteria.of("WorkingKey")).stream().findFirst()
                .map(one -> new String(Base64.getDecoder().decode(field(new String(one.payload(),
                        StandardCharsets.UTF_8), "wrapped")), StandardCharsets.UTF_8))
                .orElse("");
    }

    // ── Vault ──

    private URI vaultAddress() {
        return URI.create("http://" + vault.getHost() + ":" + vault.getMappedPort(8200));
    }

    private void vaultPost(String path, String body) throws Exception {
        HttpResponse<String> answered = http.send(HttpRequest.newBuilder(
                        vaultAddress().resolve(path)).header("X-Vault-Token", "root")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(answered.statusCode() / 100 == 2, path + ": " + answered.body());
    }

    private void vaultDelete(String path) throws Exception {
        HttpResponse<String> answered = http.send(HttpRequest.newBuilder(
                        vaultAddress().resolve(path)).header("X-Vault-Token", "root")
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(answered.statusCode() / 100 == 2, path + ": " + answered.body());
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }

    /** The tenant's database, as the deployment provisioned it. */
    private DataSource source(String code) {
        return databases.provision(TenantSpec.parse(spec(code, ""))).dataSource();
    }
}
