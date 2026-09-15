package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grant that could be widened and never narrowed.
 *
 * <p>There was one verb. A provisioning client posts what its configuration
 * names on every sweep, so adding a scope worked; taking one away did nothing
 * anybody could see, and a role dropped from the configuration entirely was
 * never posted at all — so nothing touched it and it stayed granted. The
 * operator edits the repository, the sweep reports success, and the permission
 * is still there. Wrong direction, and silent.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AGrantCanBeTakenBackIT {

    static final String CODE = "tagasi-votmine";
    static final String LOGIN = "https://logins.test/login";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String personId;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-withdraw");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AGrantCanBeTakenBackIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CODE + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(CODE, LOGIN, LOGIN));
        UntilServed.scan(manager, CODE);

        manager.authority(CODE).ensureRoleGrant("laborant", List.of("user/Specimen.read"));
        personId = aClinician("minerva@hogwarts.scot");
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("the grant is what it was, so the withdrawal below is taking something away "
            + "rather than finding nothing")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void theRoleGrantsBeforeItIsTakenBack() {
        TenantAuthority.Grants grants = manager.authority(CODE).evaluateGrants(personId);
        assertTrue(grants.scopes().contains("user/Specimen.read"),
                "the role granted nothing to begin with, so this proves nothing about "
                        + "withdrawing it: " + grants.scopes());
        assertTrue(manager.authority(CODE).activeRoleCodes().contains("laborant"));
    }

    @Test
    @Order(2)
    @DisplayName("withdrawing it stops it granting anything, at the moment a token would be "
            + "built rather than at the next sweep")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void withdrawingStopsTheGrant() {
        assertTrue(manager.authority(CODE).withdrawRoleGrant("laborant"),
                "there was an active grant and the withdrawal says there was not");

        TenantAuthority.Grants after = manager.authority(CODE).evaluateGrants(personId);
        assertFalse(after.scopes().contains("user/Specimen.read"),
                "the role still grants what it granted, so an operator who removed it from "
                        + "configuration is told it is gone and it is not: " + after.scopes());
        assertFalse(manager.authority(CODE).activeRoleCodes().contains("laborant"),
                "the directory still lists a role nobody holds any more");
    }

    @Test
    @Order(3)
    @DisplayName("withdrawing it again is a true answer rather than a failure, because the "
            + "client re-runs on every boot")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void withdrawingTwiceIsNotAFailure() {
        assertFalse(manager.authority(CODE).withdrawRoleGrant("laborant"),
                "withdrawing what is already withdrawn said it had just taken something away");
        assertFalse(manager.authority(CODE).withdrawRoleGrant("never-granted"),
                "withdrawing a grant that never existed said it had just taken something away");
    }

    /**
     * The point of deactivating rather than deleting. A withdrawn grant and
     * one that never existed behave identically at token time and must not
     * read identically afterwards.
     */
    @Test
    @Order(4)
    @DisplayName("the withdrawn grant is still a record, saying when it stopped and what it "
            + "could do while it lasted")
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    void aWithdrawnGrantIsStillAnswerable() throws Exception {
        // Read from the database rather than over the wire or through the
        // clinical engine: a RoleGrant is store-authored, lives in the
        // identity domain, and is deliberately neither a served type nor one
        // the tenant's own engine knows.
        String found = null;
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AGrantCanBeTakenBackIT")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + CODE.replace('-', '_')));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT convert_from(payload, 'UTF8') FROM "
                     + cloud.jengu.dbo.core.api.Domains.tables(
                             cloud.jengu.dbo.auth.IdentityModel.DOMAIN)
                     + "_data WHERE type = 'RoleGrant' AND NOT deleted");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                String payload = rs.getString(1);
                if (payload.contains("\"laborant\"")) {
                    found = payload;
                }
            }
        }
        assertTrue(found != null,
                "the grant was deleted rather than deactivated, so nobody can answer when "
                        + "the role stopped being able to do that");

        assertTrue(found.contains("withdrawn"),
                "the grant was deleted rather than deactivated, so nobody can answer when "
                        + "the role stopped being able to do that: " + found);
        assertTrue(found.contains("withdrawnAt"), found);
        assertTrue(found.contains("user/Specimen.read"),
                "the scopes went with the withdrawal, so what it could do while it lasted "
                        + "is unanswerable: " + found);
    }

    private static String aClinician(String login) throws Exception {
        String token = serviceToken();
        // The id from the answer rather than searched for afterwards: a
        // search that finds nothing hands back the whole bundle, which lands
        // inside the next body and fails as a JSON error three lines away.
        HttpResponse<String> practitioner = post("/Practitioner", """
                {"resourceType":"Practitioner","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Minerva"}]}""".formatted(LOGIN, login), token);
        assertEquals(201, practitioner.statusCode(), practitioner.body());
        String practitionerId = practitioner.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
        HttpResponse<String> person = post("/Person", """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"%s"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(LOGIN, login, practitionerId), token);
        assertEquals(201, person.statusCode(), person.body());
        assertEquals(201, post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"laborant"}]}]}"""
                .formatted(practitionerId), token).statusCode());
        return person.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
    }

    private static HttpResponse<String> post(String path, String body, String token)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String read(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + serviceToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String serviceToken() throws Exception {
        manager.authority(CODE).ensureClient("svc", "svc-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=svc&client_secret="
                + URLEncoder.encode("svc-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CODE).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
