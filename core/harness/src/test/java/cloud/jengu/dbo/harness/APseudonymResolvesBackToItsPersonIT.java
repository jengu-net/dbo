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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a pseudonym back into the person it was made from.
 *
 * <p>An HMAC does not invert, so this is a walk over the people whose keys
 * could have produced it — never a stored index, because an index is the
 * correlatable link the derivation exists in order not to have, and it would
 * have to be deleted on an erasure by somebody remembering to.
 *
 * <p>The walk is the easy half to believe. What these tests are really about
 * is the two things that make it safe to offer at all: that it is a
 * <b>disclosure</b> and says so on the trail, and that <b>asking does not
 * create the link</b> — the trail records which person, under which scope, and
 * never the pseudonym itself, because a row pairing those two is the index
 * arriving by the back door, one question at a time.
 *
 * <p>A miss is an answer rather than a fault, and a person who has been erased
 * is a miss like any other. That is not a gap: after the key is destroyed
 * nothing remains that could tell "erased" from "never here", and a record
 * kept so that it could would be a residue of somebody who asked to be gone.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class APseudonymResolvesBackToItsPersonIT {

    static SharedTenants.Tenant tenant;
    static String CLINIC;
    /** The shape declares which system a Person is keyed by, so it is not this
     * class's to choose any more. */
    private static final String EID = SharedTenants.EID;
    private static final String WHY = "reuniting a health fact with the person it is about";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static String her;
    static String him;
    static String them;
    static String hers;

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
        her = aPerson("39002020288");
        him = aPerson("39002020299");
        them = aPerson("39002020300");
    }

    @Test
    @Order(1)
    @DisplayName("a pseudonym resolves to the person it was derived from, and the answer is "
            + "that person rather than a record about them")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void itFindsThePersonItWasMadeFrom() throws Exception {
        hers = pseudonym("Person/" + her, "research");
        HttpResponse<String> found = resolve(hers, "research", WHY);
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("\"found\":true"), found.body());

        // Proven by round trip rather than by comparing to an id the test was
        // told: the person it answered with, asked for a pseudonym under a
        // different scope, answers what the original subject answers there.
        // Only the same person's key can do that.
        String person = personIn(found.body());
        assertEquals(pseudonym("Person/" + her, "billing"), pseudonym(person, "billing"),
                "the resolution answered somebody whose key is not the one the pseudonym "
                        + "was made from");
    }

    @Test
    @Order(2)
    @DisplayName("the same value under another scope resolves to nobody, and that is an "
            + "answer rather than a fault")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void anotherScopeFindsNobody() throws Exception {
        HttpResponse<String> other = resolve(hers, "billing", WHY);
        assertEquals(200, other.statusCode(),
                "a miss was reported as an error, which makes 'nobody is this' "
                        + "indistinguishable from 'something went wrong': " + other.body());
        assertTrue(other.body().contains("\"found\":false"), other.body());
    }

    @Test
    @Order(3)
    @DisplayName("it refuses without a scope and without a purpose, because a reveal nobody "
            + "can account for afterwards is the failure this verb has to avoid")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void itRefusesWithoutAScopeOrAPurpose() throws Exception {
        HttpResponse<String> unscoped = ask("/identity/pseudonym/resolve",
                "{\"pseudonym\":\"" + hers + "\",\"actor\":\"broker\",\"purpose\":\"x\"}");
        assertEquals(400, unscoped.statusCode(),
                "a pseudonym without the scope that made it was accepted: " + unscoped.body());

        HttpResponse<String> purposeless = ask("/identity/pseudonym/resolve",
                "{\"pseudonym\":\"" + hers + "\",\"scope\":\"research\",\"actor\":\"broker\"}");
        assertEquals(400, purposeless.statusCode(),
                "a pseudonym was turned back into a person without anybody saying why: "
                        + purposeless.body());
    }

    @Test
    @Order(4)
    @DisplayName("the trail holds who asked and why, and does not hold the pseudonym — the "
            + "asking must not build the index the derivation exists without")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void theTrailKeepsTheReasonAndNotTheLink() throws Exception {
        List<String> trail = trail();
        assertFalse(trail.isEmpty(),
                "nothing was recorded, so 'who resolved a pseudonym of mine, and why' has "
                        + "no answer — and a reveal nobody can account for afterwards is "
                        + "worse than a slow one");
        assertTrue(trail.stream().allMatch(row -> row.contains(WHY) && row.contains("broker")),
                "a resolution is on the trail without who asked or why: " + trail);
        assertTrue(trail.stream().anyMatch(row -> row.contains("\"found\":false")),
                "only the resolutions that reached somebody were recorded, so a trail of "
                        + "attempts on a person reads as fewer than there were: " + trail);

        // The whole design, asserted. After several resolutions of this value,
        // the value itself is written nowhere — not in a payload, not in an
        // envelope, not in an index. A row pairing a pseudonym with its person
        // is the stored mapping arriving one question at a time, and it would
        // outlive the erasure that was meant to end the correlation.
        assertEquals(0, rowsMentioning(hers),
                "the pseudonym is in a column somewhere, which is the correlatable link "
                        + "this verb was built to answer without creating");
        assertEquals(0, payloadsMentioning(hers),
                "the pseudonym is inside a stored payload, which is the same link one "
                        + "indirection further in");
    }

    @Test
    @Order(5)
    @DisplayName("it still finds her when the tenant holds more people than the walk reads at "
            + "once, because a walk that stopped at its first page would answer nobody and "
            + "look exactly like a miss")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void itCrossesItsOwnPageBoundary() throws Exception {
        // Seeded below her in id order so she is deterministically past the
        // first page rather than usually past it: a walk whose cursor does not
        // advance, or that mistakes a full page for the end, fails every time
        // instead of on the runs where the dice went the wrong way.
        // The filler borrows a third person's key rather than minting one,
        // because only the vault can wrap a key — a third person's, so the
        // two the other tests assert on stay the only holders of theirs.
        String theirs = pseudonym("Person/" + them, "filler");
        String whose = personIn(resolve(theirs, "filler", WHY).body());
        assertTrue(peopleBefore(SCAN_PAGE + 100, whose) > SCAN_PAGE,
                "the tenant was not filled past one page, so this proves nothing");

        HttpResponse<String> found = resolve(hers, "research", WHY);
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("\"found\":true"),
                "she is past the first page and was not found, so the walk reads one page "
                        + "and reports the rest of the tenant as nobody: " + found.body());
    }

    @Test
    @Order(6)
    @DisplayName("erasing the person ends it, and ends it as a miss: nothing is left that "
            + "could tell an erased person from one who was never here")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void erasureEndsIt() throws Exception {
        HttpResponse<String> erased = ask("/erasure", "{\"subject\":\"Person/" + her + "\"}",
                token("desk", "desk-secret"));
        assertEquals(202, erased.statusCode(), erased.body());

        HttpResponse<String> after = resolve(hers, "research", WHY);
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("\"found\":false"),
                "the pseudonym still reaches the person after their key was destroyed, so "
                        + "the correlation the erasure was meant to end survives it: "
                        + after.body());

        // Erasure reaches one human. Somebody else's pseudonym still resolves,
        // or the walk has stopped working rather than stopped finding her.
        String his = pseudonym("Person/" + him, "research");
        HttpResponse<String> still = resolve(his, "research", WHY);
        assertTrue(still.body().contains("\"found\":true"),
                "erasing one person stopped another being resolvable: " + still.body());
    }

    /**
     * What the vault reads per round trip. Held here as the number the test
     * has to exceed; a walk is only interesting once it has more than one
     * page to walk.
     */
    private static final int SCAN_PAGE = 500;

    /**
     * Fills the vault with people sorting below everybody real, and answers
     * how many there are.
     *
     * <p>They share a key with somebody who exists, which is the cheap part:
     * the scan's work is a derivation per person, and what it must not do is
     * stop before reaching the one whose key actually made the pseudonym.
     */
    private static long peopleBefore(int many, String whose) throws Exception {
        try (var c = tenant().getConnection()) {
            byte[] wrapped;
            try (var one = c.prepareStatement(
                    "SELECT wrapped_key FROM pdi.person WHERE id = ?::uuid")) {
                one.setString(1, whose);
                try (var rs = one.executeQuery()) {
                    assertTrue(rs.next() && rs.getBytes(1) != null,
                            "no live key to fill the vault from");
                    wrapped = rs.getBytes(1);
                }
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO pdi.person (id, wrapped_key) VALUES (?::uuid, ?)
                    ON CONFLICT (id) DO NOTHING""")) {
                for (int i = 1; i <= many; i++) {
                    ps.setString(1, "00000000-0000-4000-8000-%012d".formatted(i));
                    ps.setBytes(2, wrapped);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (var count = c.prepareStatement(
                    "SELECT count(*) FROM pdi.person WHERE wrapped_key IS NOT NULL");
                 var rs = count.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Every resolution this tenant has recorded, as written. */
    private static List<String> trail() throws Exception {
        List<String> rows = new java.util.ArrayList<>();
        try (var c = tenant().getConnection()) {
            for (String table : dataTables(c)) {
                try (var ps = c.prepareStatement("SELECT convert_from(payload, 'UTF8') FROM "
                        + table + " WHERE type = 'PseudonymResolution'");
                     var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getString(1));
                    }
                } catch (java.sql.SQLException noSuchColumn) {
                    // Not a table objects are stored in.
                }
            }
        }
        return rows;
    }

    /**
     * How many stored payloads carry the value.
     *
     * <p>Separate from {@link #rowsMentioning} because a payload is bytea, and
     * a whole-row text render shows it as hex — so a value hiding in one would
     * pass a scan of the row text without ever being looked at.
     */
    private static long payloadsMentioning(String value) throws Exception {
        long found = 0;
        try (var c = tenant().getConnection()) {
            for (String table : dataTables(c)) {
                try (var ps = c.prepareStatement("SELECT count(*) FROM " + table
                        + " WHERE convert_from(payload, 'UTF8') LIKE ?")) {
                    ps.setString(1, "%" + value + "%");
                    try (var one = ps.executeQuery()) {
                        one.next();
                        found += one.getLong(1);
                    }
                } catch (java.sql.SQLException notPayloadBearing) {
                    // No payload column, or bytes that are not text: neither
                    // is a place a pseudonym could be sitting as a string.
                }
            }
        }
        return found;
    }

    private static List<String> dataTables(java.sql.Connection c) throws Exception {
        List<String> tables = new java.util.ArrayList<>();
        try (var ps = c.prepareStatement("""
                SELECT table_schema, table_name FROM information_schema.columns
                WHERE column_name = 'payload'
                  AND table_schema NOT IN ('pg_catalog', 'information_schema')""");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add("\"" + rs.getString(1) + "\".\"" + rs.getString(2) + "\"");
            }
        }
        return tables;
    }

    private static org.postgresql.ds.PGSimpleDataSource tenant() {
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(tenant.databaseUrl());
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }

    private static String personIn(String body) {
        return Extracted.field(body, "person");
    }

    private static HttpResponse<String> resolve(String pseudonym, String scope, String purpose)
            throws Exception {
        return ask("/identity/pseudonym/resolve", """
                {"pseudonym":"%s","scope":"%s","actor":"broker","purpose":"%s"}"""
                .formatted(pseudonym, scope, purpose));
    }

    private static String pseudonym(String subject, String scope) throws Exception {
        HttpResponse<String> answered = ask("/identity/pseudonym",
                "{\"subject\":\"" + subject + "\",\"scope\":\"" + scope + "\"}");
        assertEquals(200, answered.statusCode(), answered.body());
        return Extracted.field(answered.body(), "pseudonym");
    }

    private static HttpResponse<String> ask(String path, String body) throws Exception {
        return ask(path, body, token("broker", "broker-secret"));
    }

    private static HttpResponse<String> ask(String path, String body, String bearer)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + path))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String aPerson(String number) throws Exception {
        HttpResponse<String> person = HTTP.send(HttpRequest.newBuilder(
                        URI.create(tenant.fhir() + "/Person"))
                        .header("Authorization", "Bearer " + token("broker", "broker-secret"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Person",
                                 "identifier":[{"system":"%s","value":"%s"}],
                                 "name":[{"family":"Tagasi"}]}"""
                                .formatted(EID, number))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, person.statusCode(), person.body());
        return Extracted.lastSegment(person.headers().firstValue("Location").orElseThrow());
    }

    /** How many rows anywhere in this tenant carry the value. */
    private static long rowsMentioning(String value) throws Exception {
        long found = 0;
        try (var c = tenant().getConnection();
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

    private static String base() {
        return tenant.fhir().replace("/fhir", "");
    }

    private static String token(String client, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return Extracted.tokenIn(HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    }
}
