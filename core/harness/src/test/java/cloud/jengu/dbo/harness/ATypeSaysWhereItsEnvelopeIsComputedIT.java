package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
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

/**
 * A tenant asks for its envelope to be computed where the bytes are, and what
 * it writes is findable.
 *
 * <p>The seam has been provable for a while and reachable from nothing: a
 * database extractor was something a test could construct and no tenant could
 * ask for, which is the shape this codebase has been bitten by four times —
 * built, proven, and mounted by nobody. A type declares it now.
 *
 * <p><b>Declared per type, and never inferred.</b> What a document is found by
 * is the whole of what a search answers, so a type computed one way where the
 * other was meant goes quietly unfindable rather than loudly wrong, and an
 * empty result is indistinguishable from there being nothing to find. A tenant
 * asks for this once the two sides have been compared over what it holds,
 * which is what the envelope baseline records.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATypeSaysWhereItsEnvelopeIsComputedIT {

    static final String CODE = "where-the-bytes-are";
    static final String URL = "https://bytes.test/vs/declared";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String service;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-where-bytes");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATypeSaysWhereItsEnvelopeIsComputedIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // NOT a face root. The function reads the compiled parameters this
        // tenant holds, and an ordinary tenant holds them because it comes up
        // from the face's image — so this is the shape a real tenant has, and
        // it carries its own records rather than the whole published corpus.
        Files.writeString(dir.resolve(CODE + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"ValueSet","identity":"canonical","handling":"operational",
                   "extractor":"database"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(CODE));
        UntilServed.scan(manager, CODE);
        service = serviceToken();
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
     * Written through the ordinary door, found through the ordinary door. What
     * differs is only where the envelope was computed, which is the one thing
     * a caller should never be able to tell.
     */
    @Test
    @Timeout(600)
    @Proving(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED)
    @DisplayName("a type declared as computed in the database is written and found the same as "
            + "one computed here")
    void aDeclaredDatabaseExtractorServesTheType() throws Exception {
        HttpResponse<String> written = post("/ValueSet", """
                {"resourceType":"ValueSet","url":"%s","version":"1","status":"active",
                 "name":"Declared"}""".formatted(URL));
        assertEquals(201, written.statusCode(), written.body());

        // Counted on the ENTRIES, never on the body: a search bundle echoes the
        // query in its own self link, so a body that merely contains the url
        // proves only that the url was asked about.
        String byUrl = get("/ValueSet?url=" + URLEncoder.encode(URL, StandardCharsets.UTF_8))
                .body();
        assertEquals(1, found(byUrl),
                "written, and not findable by the url it declares — which is what an envelope "
                        + "computed in the wrong place looks like from outside: " + byUrl);

        // And by an ordinary parameter AND the url together, so this is the
        // whole envelope rather than the identity alone. Both, because a
        // face-root tenant carries hundreds of active value sets and asking
        // for status alone is a question about the first page rather than
        // about this record.
        String byBoth = get("/ValueSet?status=active&url="
                + URLEncoder.encode(URL, StandardCharsets.UTF_8)).body();
        assertEquals(1, found(byBoth),
                "found by its identity and not by an ordinary parameter beside it, so the "
                        + "identity landed and the rest of the envelope did not: " + byBoth);

        // And the negative, so the match above is the envelope agreeing rather
        // than the search ignoring what it was asked.
        String wrongStatus = get("/ValueSet?status=draft&url="
                + URLEncoder.encode(URL, StandardCharsets.UTF_8)).body();
        assertEquals(0, found(wrongStatus),
                "found under a status it does not have, so the envelope is not being "
                        + "consulted at all: " + wrongStatus);
    }

    /** How many entries a search bundle actually carried. */
    private static int found(String bundle) {
        int at = 0;
        int count = 0;
        while ((at = bundle.indexOf("\"fullUrl\"", at)) >= 0) {
            count++;
            at++;
        }
        return count;
    }

    /**
     * And a reindex of it happens there too, with no payload crossing the wire.
     *
     * <p>The ordinary path reads every payload into this process, extracts and
     * writes back — the whole of a type moving twice for work the database can
     * do in place. Proven by emptying the envelope first, because a rebuild
     * that does nothing and a rebuild that does not happen look identical from
     * outside.
     *
     * <p>On an ordinary tenant rather than a face root, deliberately: a face
     * root carries every ValueSet the version publishes, and reindexing all of
     * them in one test measures the corpus rather than the mechanism.
     */
    @Test
    @Timeout(600)
    @Proving(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED)
    @DisplayName("a reindex of a type computed in the database rebuilds what a search asks by")
    void aReindexHappensWhereTheBytesAre() throws Exception {
        assertEquals(201, post("/ValueSet", """
                {"resourceType":"ValueSet","url":"%s/reindexed","version":"1","status":"active",
                 "name":"Reindexed"}""".formatted(URL)).statusCode());

        var engine = manager.runtime(CODE).orElseThrow().engine();
        String id = engine.getByIdentifier("ValueSet",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                                URL + "/reindexed")))
                .stream().findFirst().orElseThrow().id();

        try (java.sql.Connection c = tenantSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE definitions.definitions_data SET envelope = '{}'::jsonb"
                             + " WHERE id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(id));
            assertEquals(1, ps.executeUpdate(), "the record this is about is not where it "
                    + "was looked for");
        }

        engine.rebuildEnvelopes("ValueSet");

        assertEquals(1, engine.getByIdentifier("ValueSet",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                                URL + "/reindexed"))).size(),
                "the envelope was emptied and the reindex did not put it back");
    }

    private static org.postgresql.ds.PGSimpleDataSource tenantSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + CODE.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
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

    private static String serviceToken() throws Exception {
        manager.authority(CODE).ensureClient("seeder", "seeder-secret",
                List.of("system/*.read", "system/*.write"));
        String form = "grant_type=client_credentials&client_id=seeder&client_secret="
                + URLEncoder.encode("seeder-secret", StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(CODE).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString()).body()
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
