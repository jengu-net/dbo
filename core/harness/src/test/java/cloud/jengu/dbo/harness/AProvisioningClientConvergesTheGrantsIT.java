package cloud.jengu.dbo.harness;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client converging this tenant's grants on what its configuration names.
 *
 * <p>Converging is a read and then a write, and only the write existed. A
 * grant could be widened — the client posts what configuration names and the
 * store updates it — and narrowing silently did nothing, because a role
 * dropped from configuration is never posted at all and so was never touched.
 * The withdrawal verb could not close that alone: a client cannot withdraw
 * what it has no way to learn about.
 *
 * <p>The alternatives are worse and are why this is a read rather than
 * something a client keeps for itself. Remembering what was posted last time
 * is state about this store's contents held somewhere else, which drifts the
 * moment anything else writes. Replaying a configuration repository's history
 * converges only if every commit was applied in order, so a restored or
 * diverged store stays wrong — reconciling from history is not reconciling.
 *
 * <p>What the answer has to carry is decided by what a reconcile compares:
 * the organisation, because a grant at one and a tenant-wide grant are
 * different grants and withdrawing the wrong one is worse than reading
 * nothing; and the scopes, because the drift hardest to see is a role that
 * still exists with more than configuration now gives it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AProvisioningClientConvergesTheGrantsIT {

    static SharedTenants.Tenant tenant;
    static String CODE;
    /** The shape declares what people are keyed by here. */
    static final String LOGIN = SharedTenants.LOGINS;
    private static final HttpClient HTTP = HttpClient.newHttpClient();


    @BeforeAll
    void up() throws Exception {
        // Shared. It is about what a grant does and stops doing, not about a
        // tenant, and every assertion names the person this class created.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_GRANTS);
        CODE = tenant.code();
        tenant.authority().ensureClient("svc", "svc-secret",
                List.of("system/*.read", "system/*.write"));
    }

    @Test
    @Order(1)
    @DisplayName("what was granted can be read back, with the organisation it was granted at "
            + "and the scopes it was granted")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void whatWasWrittenCanBeRead() throws Exception {
        assertEquals(200, grant("{\"role\":\"laborant\",\"scopes\":[\"user/Specimen.read\"]}"));
        assertEquals(200, grant("{\"role\":\"laborant\",\"organisation\":\"peamaja\","
                + "\"scopes\":[\"user/Specimen.read\",\"user/Specimen.write\"]}"));

        String read = read("");
        assertTrue(read.contains("\"role\":\"laborant\""), read);

        // The two are different grants and both exist. A read that flattened
        // them would let a client withdraw the tenant-wide one believing it
        // had withdrawn the other.
        assertTrue(read.contains("\"organisation\":null"),
                "the tenant-wide grant does not say it is tenant-wide: " + read);
        assertTrue(read.contains("\"organisation\":\"peamaja\""),
                "the grant at an organisation does not say where: " + read);

        // And the scopes, because that is what a reconcile compares. A role
        // that still exists with MORE than configuration now gives it is the
        // drift a list of role codes could never show.
        assertTrue(read.contains("\"user/Specimen.write\""),
                "the scopes as granted are not in the answer, so a client can see that a "
                        + "role exists and not whether it is still right: " + read);
    }

    @Test
    @Order(2)
    @DisplayName("a withdrawn grant is not in the answer unless it is asked for, because one "
            + "sitting unremarked in a list of grants reads as a role that is present")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void withdrawnGrantsAreNotTheDefaultAnswer() throws Exception {
        assertEquals(200, grant("{\"role\":\"laborant\",\"organisation\":\"peamaja\","
                + "\"withdraw\":\"true\"}"));

        String active = read("");
        assertFalse(active.contains("\"peamaja\""),
                "a withdrawn grant is in the default answer, so a client takes the role for "
                        + "present and never re-grants it when configuration names it: "
                        + active);
        assertTrue(active.contains("\"role\":\"laborant\""),
                "withdrawing the grant at one organisation took the tenant-wide one with it: "
                        + active);

        // The auditor's question, over the same door: what could it do, and
        // when did it stop.
        String all = read("?status=all");
        assertTrue(all.contains("\"peamaja\"") && all.contains("\"status\":\"withdrawn\""),
                "the withdrawn grant is unreachable, so 'when did this role stop' has no "
                        + "answer over the wire: " + all);
        assertTrue(all.contains("\"withdrawnAt\""), all);
        assertTrue(all.contains("\"user/Specimen.write\""),
                "the withdrawn grant lost the scopes it had, so what it could do when it "
                        + "stopped is unanswerable: " + all);
    }

    @Test
    @Order(3)
    @DisplayName("the converge that could not be written before: read what is granted, "
            + "withdraw what configuration no longer names, and read back agreement")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void aClientConvergesTheStoreOnItsConfiguration() throws Exception {
        assertEquals(200, grant("{\"role\":\"koristaja\",\"scopes\":[\"user/*.read\"]}"));
        assertEquals(200, grant("{\"role\":\"valvur\",\"scopes\":[\"user/*.read\"]}"));

        // What configuration names today. 'koristaja' and 'valvur' have been
        // removed from it; nothing posts them, which is exactly why nothing
        // used to take them away.
        Set<String> named = Set.of("laborant");

        Set<String> held = rolesIn(read(""));
        assertTrue(held.containsAll(Set.of("koristaja", "valvur")),
                "the roles to converge away are not there to begin with: " + held);

        for (String role : held) {
            if (!named.contains(role)) {
                assertEquals(200, grant("{\"role\":\"" + role + "\",\"withdraw\":\"true\"}"));
            }
        }

        assertEquals(named, rolesIn(read("")),
                "the store did not converge on what configuration names, which is the whole "
                        + "of what this read exists for");
    }

    @Test
    @Order(4)
    @DisplayName("the read stands behind the same scope as the writes: anonymous is refused, "
            + "and a human's token never reaches the provisioning plane")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void itIsOnTheProvisioningPlane() throws Exception {
        assertEquals(401, HTTP.send(HttpRequest.newBuilder(URI.create(door())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        tenant.authority().ensureClient("inimene", "inimene-secret",
                List.of("user/*.read"));
        String human = tokenFor("inimene", "inimene-secret");
        assertEquals(403, HTTP.send(HttpRequest.newBuilder(URI.create(door()))
                        .header("Authorization", "Bearer " + human).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(),
                "a user-plane token read what this tenant's authority is shaped like");
    }

    @Test
    @Order(5)
    @DisplayName("an unrecognised status is refused by name, because a caller who believed it "
            + "had asked for the withdrawn ones would reconcile against a shorter list")
    @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
    void anUnrecognisedStatusIsRefusedByName() throws Exception {
        HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(
                        URI.create(door() + "?status=withdrawn"))
                        .header("Authorization", "Bearer " + serviceToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("withdrawn"),
                "the refusal does not name what was asked for: " + refused.body());
    }

    // ---------------------------------------------------------- plumbing

    private static final Pattern ROLE = Pattern.compile("\"role\":\"([^\"]+)\"");

    private static Set<String> rolesIn(String body) {
        Set<String> roles = new java.util.TreeSet<>();
        Matcher found = ROLE.matcher(body);
        while (found.find()) {
            roles.add(found.group(1));
        }
        return roles;
    }

    private static String door() {
        return tenant.base() + "/oidc/admin/role-grants";
    }

    private static int grant(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(door()))
                        .header("Authorization", "Bearer " + serviceToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String read(String query) throws Exception {
        HttpResponse<String> answered = HTTP.send(HttpRequest.newBuilder(
                        URI.create(door() + query))
                        .header("Authorization", "Bearer " + serviceToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private static String serviceToken() throws Exception {
        return tokenFor("svc", "svc-secret");
    }

    private static String tokenFor(String client, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client + "&client_secret="
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
