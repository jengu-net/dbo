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
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pseudonym that is computed rather than kept.
 *
 * <p>A pseudonym stored as a record is a link somebody can read: two rows
 * joined by a value, correlatable by anyone who reaches both, and outliving
 * the erasure that was supposed to end the correlation. Derived from the
 * person's own key there is nothing to read and nothing to outlive — the
 * unlinkability is a property of the construction rather than of who is
 * allowed to look.
 *
 * <p>What a test can show is the observable half: that it is stable, that
 * scopes and people do not collide, and that erasure makes the derivation
 * impossible rather than merely refused. That two pseudonyms cannot be
 * related by somebody without the key is a property of a keyed function and
 * is not something a test demonstrates by trying.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class APseudonymIsDerivedAndNeverKeptIT {

    static SharedTenants.Tenant tenant;
    static String CLINIC;
    /** The shape declares which system a Person is keyed by, so it is not this
     * class's to choose any more. */
    private static final String EID = SharedTenants.EID;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static String identity;
    static String first;
    static String second;

    @BeforeAll
    void up() throws Exception {
        // Shared. What it proves is about a pseudonym and the number behind
        // it, and every question it asks the database is asked about a value
        // this class itself wrote — which is what lets it share a tenant.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_PDI_PERSON);
        CLINIC = tenant.code();
        tenant.authority().ensureClient("broker", "broker-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.IDENTITY, "system/*.write",
                        "system/*.read"));
        tenant.authority().ensureClient("desk", "desk-secret", List.of("erasure"));
        identity = aPerson("39002020266");
        second = aPerson("39002020277");
    }

    @Test
    @Order(1)
    @DisplayName("the same person and scope answer the same pseudonym, which is what makes it "
            + "usable as an identifier, and nothing was written to make it so")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void itIsStableWithoutBeingStored() throws Exception {
        first = pseudonym(identity, "research");
        assertFalse(first.isBlank());
        assertEquals(first, pseudonym(identity, "research"),
                "asking twice answered differently, so it cannot be used to recognise "
                        + "anybody");

        // Nothing kept: the answer is the same because the key and the scope
        // are, not because a row remembers it. A stored pseudonym is a link
        // anybody who reaches both rows can read.
        assertEquals(0, rowsMentioning(first),
                "the pseudonym was written down somewhere, which is the correlatable link "
                        + "this exists to not have");
    }

    @Test
    @Order(2)
    @DisplayName("another scope answers something else, and so does another person, so one "
            + "pseudonym says nothing about the rest")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void scopesAndPeopleDoNotCollide() throws Exception {
        assertNotEquals(first, pseudonym(identity, "billing"),
                "two scopes answered the same value, so a pseudonym in one context "
                        + "identifies the same person in every other");
        assertNotEquals(first, pseudonym(second, "research"),
                "two people answered the same value in one scope, which is worse than a "
                        + "correlation: it is a collision");
    }

    @Test
    @Order(3)
    @DisplayName("a scope is opaque and required, because a pseudonym without one would be "
            + "the person's only pseudonym")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void aScopeIsRequired() throws Exception {
        HttpResponse<String> without = ask("{\"subject\":\"Person/" + identity + "\"}");
        assertEquals(400, without.statusCode(), without.body());
    }

    @Test
    @Order(4)
    @DisplayName("erasing the person makes the derivation impossible rather than forbidden, so "
            + "a pseudonym issued yesterday cannot be recomputed today")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void erasureMakesItImpossible() throws Exception {
        HttpResponse<String> erased = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/erasure"))
                        .header("Authorization", "Bearer " + token("desk", "desk-secret"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"Person/" + identity + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, erased.statusCode(), erased.body());

        HttpResponse<String> after = ask("{\"subject\":\"Person/" + identity
                + "\",\"scope\":\"research\"}");
        assertEquals(410, after.statusCode(),
                "the pseudonym is still derivable after the person was erased, so the "
                        + "correlation the erasure was meant to end survives it: "
                        + after.statusCode() + " " + after.body());

        // And the other person is untouched: erasure reaches one human, not
        // everybody who shares a scope with them.
        assertFalse(pseudonym(second, "research").isBlank(),
                "erasing one person stopped another's pseudonym being derivable");
    }

    private static String pseudonym(String person, String scope) throws Exception {
        HttpResponse<String> answered = ask("{\"subject\":\"Person/" + person
                + "\",\"scope\":\"" + scope + "\"}");
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body().replaceAll("(?s).*\"pseudonym\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> ask(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/identity/pseudonym"))
                        .header("Authorization", "Bearer " + token("broker", "broker-secret"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** How many rows anywhere in this tenant carry the value. */
    private static long rowsMentioning(String value) throws Exception {
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(tenant.databaseUrl());
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        long found = 0;
        try (var c = ds.getConnection();
             var tables = c.prepareStatement("""
                     SELECT table_schema, table_name FROM information_schema.tables
                     WHERE table_type = 'BASE TABLE'
                       AND table_schema NOT IN ('pg_catalog', 'information_schema')""");
             var rs = tables.executeQuery()) {
            while (rs.next()) {
                String qualified = "\"" + rs.getString(1) + "\".\"" + rs.getString(2) + "\"";
                try (var count = c.prepareStatement(
                        "SELECT count(*) FROM " + qualified + " t WHERE t::text LIKE ?")) {
                    count.setString(1, "%" + value + "%");
                    try (var one = count.executeQuery()) {
                        one.next();
                        found += one.getLong(1);
                    }
                } catch (java.sql.SQLException notCountable) {
                    // A table whose row type will not render as text is not
                    // one a pseudonym could be hiding in as a string.
                }
            }
        }
        return found;
    }

    private static String aPerson(String number) throws Exception {
        HttpResponse<String> person = HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.fhir() + "/Person"))
                        .header("Authorization", "Bearer " + token("broker", "broker-secret"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Person",
                                 "identifier":[{"system":"%s","value":"%s"}],
                                 "name":[{"family":"Varjunimi"}]}"""
                                .formatted(EID, number))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, person.statusCode(), person.body());
        return person.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");
    }

    private static String base() {
        return tenant.fhir().replace("/fhir", "");
    }

    private static String token(String client, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll("(?s).*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
