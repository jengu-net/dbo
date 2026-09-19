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
 * A role named by identity grants what a role named by id grants.
 *
 * <p>A declaration cannot carry an id — nothing on the declaring side has seen
 * this store's ids, which is what declaring means — so a declared
 * PractitionerRole names its practitioner and its organisation the way FHIR
 * provides for: by a logical reference. The store indexes those. The grant
 * lookup used to ask only the id edge, so such a role applied cleanly, was
 * findable by {@code :identifier}, and granted nothing: a person who could
 * sign in and do nothing, with no card anywhere saying why.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADeclaredRelationGrantsIT {

    static SharedTenants.Tenant tenant;
    static String CODE;
    /** The shape declares what people and organisations are keyed by here. */
    static final String LOGIN = SharedTenants.LOGINS;
    static final String ORG_CODE = SharedTenants.ORGS;

    static String service;
    static String organisationId;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        // Shared. It is about what a declared role grants and where it
        // reaches, which is about the role rather than about a tenant — and
        // every assertion names the person and organisation it wrote.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_GRANTS);
        CODE = tenant.code();
        service = serviceToken();

        organisationId = idOf(post("/Organization", """
                {"resourceType":"Organization","identifier":[{"system":"%s","value":"declared-roles-lab"}],
                 "name":"Main Lab"}""".formatted(ORG_CODE)));

        // Two grants for one role code: one tenant-wide, one at the lab. If the
        // organisation of a declared role cannot be resolved, the role reads as
        // held nowhere in particular and the tenant-wide one applies — which is
        // the silent widening this has to catch, not merely an absent scope.
        TenantAuthority authority = tenant.authority();
        authority.ensureRoleGrant("lab-tech", List.of("user/*.read"));
        authority.ensureRoleGrant("lab-tech", "declared-roles-lab",
                List.of("user/*.read", "user/Observation.write"));
    }

    /**
     * The shape a declared tenant produces: every relation a logical
     * reference, because ids do not exist on the declaring side.
     */
    @Test
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    @DisplayName("a role naming its practitioner and its organisation by identity grants the "
            + "organisation's scopes, and is scoped to that organisation")
    void aRoleNamedByIdentityGrants() throws Exception {
        String personId = aClinician("albus@hogwarts.scot", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"identifier":{"system":"%s","value":"albus@hogwarts.scot"}},
                 "organization":{"identifier":{"system":"%s","value":"declared-roles-lab"}},
                 "code":[{"coding":[{"system":"urn:example:role","code":"lab-tech"}]}]}"""
                .formatted(LOGIN, ORG_CODE));

        TenantAuthority.Grants grants = tenant.authority().evaluateGrants(personId);

        assertTrue(grants.roles().contains("lab-tech"),
                "the role was applied, is findable, and granted nothing: " + grants);
        assertTrue(grants.scopes().contains("user/Observation.write"),
                "the grant at the organisation the role names was not the one applied: "
                        + grants);
        assertFalse(grants.organisations().isEmpty(),
                "a role scoped to an organisation came back reaching the whole tenant, which "
                        + "is the failure that looks like success: " + grants);
        assertTrue(grants.organisations().contains(organisationId),
                "scoped, and to somewhere other than the organisation it names: " + grants);
    }

    /** The same role written the way a platform writes one, for comparison. */
    @Test
    @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
    @DisplayName("a role naming them by id grants exactly the same thing, so neither way is a "
            + "second class of relation")
    void aRoleNamedByIdGrantsTheSame() throws Exception {
        String practitioner = "severus@hogwarts.scot";
        String personId = aClinician(practitioner, null);
        String practitionerId = idOf(get("/Practitioner?identifier="
                + URLEncoder.encode(LOGIN + "|" + practitioner, StandardCharsets.UTF_8)));
        assertEquals(201, post("/PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "organization":{"reference":"Organization/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"lab-tech"}]}]}"""
                .formatted(practitionerId, organisationId)).statusCode());

        TenantAuthority.Grants grants = tenant.authority().evaluateGrants(personId);

        assertTrue(grants.scopes().contains("user/Observation.write"), grants.toString());
        assertTrue(grants.organisations().contains(organisationId), grants.toString());
    }

    /**
     * A practitioner, the person who is one, and optionally the role — the
     * order a declared set arrives in does not matter, so the role may be
     * written before or after anything it names.
     */
    private String aClinician(String login, String rolePayload) throws Exception {
        assertEquals(201, post("/Practitioner", """
                {"resourceType":"Practitioner","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(LOGIN, login, login)).statusCode());
        String practitionerId = idOf(get("/Practitioner?identifier="
                + URLEncoder.encode(LOGIN + "|" + login, StandardCharsets.UTF_8)));
        HttpResponse<String> person = post("/Person", """
                {"resourceType":"Person","identifier":[{"system":"%s","value":"%s"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(LOGIN, login, practitionerId));
        assertEquals(201, person.statusCode(), person.body());
        if (rolePayload != null) {
            assertEquals(201, post("/PractitionerRole", rolePayload).statusCode());
        }
        return idOf(person);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.fhir() + path))
                        .header("Authorization", "Bearer " + service)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.fhir() + path))
                        .header("Authorization", "Bearer " + service).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The id of a created resource, or of the first entry of a bundle. */
    private static String idOf(HttpResponse<String> response) {
        String body = response.body();
        int at = body.indexOf("\"id\"");
        return body.substring(body.indexOf('"', body.indexOf(':', at)) + 1,
                body.indexOf('"', body.indexOf('"', body.indexOf(':', at)) + 1));
    }

    private static String serviceToken() throws Exception {
        tenant.authority().ensureClient("seeder", "seeder-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=seeder&client_secret="
                + URLEncoder.encode("seeder-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString()).body()
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
