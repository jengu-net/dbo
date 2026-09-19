package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * A declared role grants what it says, with the membrane on.
 *
 * <p>Two things that were each proven separately and whose combination was
 * not. A declared PractitionerRole names its practitioner by identity because
 * a declaration cannot carry an id; a tenant holding identifying data in its
 * vault removes {@code Practitioner.identifier} from the payload and reveals
 * it only to a reader that states a purpose. The grant lookup read the claims
 * out of the payload, so with the membrane on it found none, matched no role,
 * and refused a person who had every right to sign in — with nothing anywhere
 * saying why.
 *
 * <p>The claims were never hidden: they are index rows the store holds of a
 * person record either way, because identity-keyed writes and identifier
 * lookups are built on them. Only the place they were being read from was
 * behind the membrane.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADeclaredRelationGrantsBehindTheMembraneIT {

    static final String CODE = "membrane-roles";
    static final String LOGIN = "https://logins.test/login";
    static final String ORG_CODE = "https://orgs.test/code";
    static final String ALBUS = "albus@hogwarts.test";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String service;
    static String organisationId;
    static String practitionerId;
    static String personId;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-membrane-roles");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeclaredRelationGrantsBehindTheMembraneIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // The membrane on, which is the whole point of this tenant.
        Files.writeString(dir.resolve(CODE + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"types":[
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Practitioner","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"}]}"""
                .formatted(CODE, ORG_CODE, LOGIN, LOGIN));
        UntilServed.scan(manager, CODE);
        service = serviceToken();

        organisationId = idOf(post("/Organization", """
                {"resourceType":"Organization","identifier":[{"system":"%s","value":"main-lab"}],
                 "name":"Main Lab"}""".formatted(ORG_CODE)));
        practitionerId = idOf(post("/Practitioner", """
                {"resourceType":"Practitioner","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Albus"}]}""".formatted(LOGIN, ALBUS)));
        personId = idOf(post("/Person", """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"%s"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(LOGIN, ALBUS, practitionerId)));
        // The role as a declaration writes it: every relation by identity,
        // because nothing on a declaring side has seen this store's ids.
        assertEquals(201, post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"identifier":{"system":"%s","value":"%s"}},
                 "organization":{"identifier":{"system":"%s","value":"main-lab"}},
                 "code":[{"coding":[{"system":"urn:example:role","code":"lab-tech"}]}]}"""
                .formatted(LOGIN, ALBUS, ORG_CODE)).statusCode());

        TenantAuthority authority = manager.authority(CODE);
        authority.ensureRoleGrant("lab-tech", List.of("user/*.read"));
        authority.ensureRoleGrant("lab-tech", "main-lab",
                List.of("user/*.read", "user/Observation.write"));
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

    /**
     * First, that the membrane is actually on — otherwise this test would pass
     * for the same reason its sibling without a vault does, and prove nothing
     * about the combination.
     */
    @Test
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    @DisplayName("the practitioner's identifier is not in the document a reader without a "
            + "purpose gets back")
    void theMembraneIsOn() throws Exception {
        String read = get("/Practitioner/" + practitionerId).body();

        assertFalse(read.contains(ALBUS),
                "the login is in the payload, so this tenant's vault is not holding it and "
                        + "the rest of this class proves nothing about a membrane: " + read);
    }

    /**
     * And the grant, which is what was refused: the claims come from the index
     * rather than the document, so the role is matched by the identity it
     * names and the organisation it names is the one it is scoped to.
     */
    @Test
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    @DisplayName("a role naming its practitioner by an identifier the vault holds still grants, "
            + "and is still scoped to the organisation it names")
    void aRoleNamedByAVaultedIdentifierGrants() {
        TenantAuthority.Grants grants = manager.authority(CODE).evaluateGrants(personId);

        assertTrue(grants.roles().contains("lab-tech"),
                "the role was applied, reads back, and granted nothing — which is what a "
                        + "person meets as access_denied with no card anywhere: " + grants);
        assertTrue(grants.scopes().contains("user/Observation.write"),
                "the grant at the organisation the role names was not the one applied: "
                        + grants);
        assertFalse(grants.organisations().isEmpty(),
                "a role scoped to an organisation came back reaching the whole tenant: "
                        + grants);
        assertTrue(grants.organisations().contains(organisationId), grants.toString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(CODE) + path))
                        .header("Authorization", "Bearer " + service).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(HttpResponse<String> response) {
        String body = response.body();
        assertEquals(201, response.statusCode(), body);
        int at = body.indexOf("\"id\"");
        return body.substring(body.indexOf('"', body.indexOf(':', at)) + 1,
                body.indexOf('"', body.indexOf('"', body.indexOf(':', at)) + 1));
    }

    private static String serviceToken() throws Exception {
        manager.authority(CODE).ensureClient("seeder", "seeder-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=seeder&client_secret="
                + URLEncoder.encode("seeder-secret", StandardCharsets.UTF_8);
        return Extracted.tokenIn(HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CODE).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString()).body());
    }
}
