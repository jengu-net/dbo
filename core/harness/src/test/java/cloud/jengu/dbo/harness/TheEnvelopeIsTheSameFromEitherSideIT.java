package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index, built twice, and compared.
 *
 * <p>What a document can be found by is an envelope, and it has been built in
 * the JVM by evaluating thirty-odd expressions over an object tree on every
 * write. The parameters are compiled when they arrive, so the database can
 * build the same envelope over the document already in hand — which is the
 * point of doing any of this, and worth exactly nothing unless what it builds
 * is the same.
 *
 * <p><b>What is held here is the typed rules, one at a time and in full.</b>
 * Each kind produces a shape a search asks by, and those shapes are the
 * contract: three forms per coded value, a string folded and kept as written,
 * a date at the moment its span opens, a reference split into what it points
 * at. A rule that produces the wrong shape is a document that quietly stops
 * being findable, which reads as an empty result rather than as a fault.
 *
 * <p>What is NOT held here yet is identity with the extractor in force over a
 * corpus. The definition types are extracted by a narrow hand-written path of
 * their own rather than by these parameters, so comparing the two compares
 * different contracts; the corpus comparison belongs with ordinary resource
 * types, and those need documents this store does not carry.
 *
 * <p>Nothing reads the database's envelope yet. It is built beside the one in
 * force, and being right about the rules is what comes first.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheEnvelopeIsTheSameFromEitherSideIT {

    private static final String ROOT = "umbrik-juur";

    /** Per type, so the crowded ones do not decide the number for the rest. */
    private static final int PER_TYPE = 25;

    private static final Path BASELINE = Path.of("..", "..", "config", "envelope-baseline.txt");
    private static final ObjectMapper JSON = new ObjectMapper();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-envelope");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheEnvelopeIsTheSameFromEitherSideIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, ROOT);
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
    @Timeout(900)
    @DisplayName("each typed rule produces exactly the shape a search asks by")
    @Proving(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE)
    void eachTypedRuleProducesTheShapeASearchAsksBy() throws Exception {
        // A coded value in three shapes, because FHIR's token syntax is three
        // questions: this code in this system, this code in any system, and
        // anything at all in this system. A form the index does not carry is a
        // search that finds nothing, which looks like an answer.
        assertEquals(
                JSON.readTree("[{\"t\":\"tok\",\"s\":\"urn:s\",\"v\":\"c\"},"
                        + "{\"t\":\"toks\",\"v\":\"urn:s\"},"
                        + "{\"t\":\"tokc\",\"v\":\"c\"}]"),
                pairs("k", "token",
                        "{\"coding\":[{\"system\":\"urn:s\",\"code\":\"c\"}]}"));

        // Without a system there is no exclusive claim to make, so only the
        // bare form: the same digits in two namespaces are two things.
        assertEquals(JSON.readTree("[{\"t\":\"tokc\",\"v\":\"c\"}]"),
                pairs("k", "token", "{\"code\":\"c\"}"));

        // A plain value is a bare code.
        assertEquals(JSON.readTree("[{\"t\":\"tokc\",\"v\":\"final\"}]"),
                pairs("k", "token", "\"final\""));

        // A string twice: folded for the ordinary case-insensitive search, and
        // as written for :exact.
        assertEquals(JSON.readTree("{\"k\":[{\"t\":\"str\",\"v\":\"aba\"}],"
                        + "\"k_xct\":[{\"t\":\"str\",\"v\":\"AbA\"}]}"),
                keyed("k", "string", "\"AbA\""));

        // A uri is case-sensitive, so it is kept as written and only once.
        assertEquals(JSON.readTree("{\"k\":[{\"t\":\"str\",\"v\":\"urn:X\"}]}"),
                keyed("k", "uri", "\"urn:X\""));

        // A date names a span at whatever precision its author had, and is
        // indexed by the moment that span opens.
        assertEquals(JSON.readTree("[{\"t\":\"date\",\"v\":\"2020-01-01T00:00:00.000Z\"}]"),
                pairs("k", "date", "\"2020\""));
        assertEquals(JSON.readTree("[{\"t\":\"date\",\"v\":\"2020-03-01T00:00:00.000Z\"}]"),
                pairs("k", "date", "\"2020-03\""));
        // A period is its start, because a lower bound is what an ordering asks
        assertEquals(JSON.readTree("[{\"t\":\"date\",\"v\":\"2021-05-06T00:00:00.000Z\"}]"),
                pairs("k", "date", "{\"start\":\"2021-05-06\",\"end\":\"2021-06-01\"}"));

        // A reference contributes nothing to the envelope, because an edge is
        // not a key: a search by reference is answered by a join against the
        // edges a document has, and putting one here as well would index a
        // dimension nothing reads.
        assertEquals(JSON.readTree("{}"),
                keyed("k", "reference", "{\"reference\":\"Patient/123\"}"));

        // What a reference DOES contribute is the logical one — a pointer by
        // business identifier, which the :identifier modifier asks about and
        // which has nowhere else to live.
        assertEquals(JSON.readTree("{\"k_identifier\":["
                        + "{\"t\":\"tok\",\"s\":\"urn:s\",\"v\":\"9\"},"
                        + "{\"t\":\"toks\",\"v\":\"urn:s\"},"
                        + "{\"t\":\"tokc\",\"v\":\"9\"}]}"),
                keyed("k", "reference",
                        "{\"identifier\":{\"system\":\"urn:s\",\"value\":\"9\"}}"));

        // A number is a number, and something that is not one contributes
        // nothing rather than a wrong answer.
        assertEquals(JSON.readTree("[{\"t\":\"num\",\"v\":12.5}]"),
                pairs("k", "number", "\"12.5\""));
        assertEquals(JSON.readTree("[]"), pairs("k", "number", "\"not a number\""));

        // And the two this store does not take apart contribute nothing, here
        // as everywhere: this moves where extraction happens, not what it does.
        assertEquals(JSON.readTree("[]"), pairs("k", "quantity", "{\"value\":1}"));
        assertEquals(JSON.readTree("[]"), pairs("k", "composite", "{\"value\":1}"));
    }

    @Test
    @Timeout(900)
    @DisplayName("the envelope is built from the parameters the tenant holds, and names them "
            + "by their code")
    @Proving(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE)
    void theEnvelopeIsBuiltFromTheParametersHeld() throws Exception {
        // Not from a list in the release: what a document is indexed by is
        // what this tenant can be asked, which is the compiled rows — the
        // version's own parameters and whatever it has authored.
        JsonNode built = builtEnvelopeOf("""
                {"resourceType":"ValueSet","url":"https://envelope.test/vs","status":"draft",
                 "name":"Whatever"}""", "ValueSet");

        assertTrue(built.has("url"),
                "the envelope carries no url, so the parameters were not read: " + built);
        assertEquals(JSON.readTree("[{\"t\":\"str\",\"v\":\"https://envelope.test/vs\"}]"),
                built.get("url"),
                "a uri is kept as written and once");
        assertTrue(built.has("status"), "status is a published parameter and is not here: "
                + built);
    }

    // ------------------------------------------------------------- the two

    /** What one hit contributes under one key, as the database builds it. */
    private static JsonNode pairs(String key, String kind, String hit) throws Exception {
        JsonNode keyed = keyed(key, kind, hit);
        return keyed.has(key) ? keyed.get(key) : JSON.readTree("[]");
    }

    /** Every key one hit contributes to, since a string contributes to two. */
    private static JsonNode keyed(String key, String kind, String hit) throws Exception {
        try (Connection c = tenantSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(jsonb_object_agg(key, vs), '{}'::jsonb) FROM (
                       SELECT key, jsonb_agg(value) AS vs
                         FROM dbo.envelope_pairs(?, ?, ?::jsonb) GROUP BY key) one""")) {
            ps.setString(1, key);
            ps.setString(2, kind);
            ps.setString(3, hit);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return JSON.readTree(rs.getString(1));
            }
        }
    }

    private static JsonNode builtEnvelopeOf(String document, String type) throws Exception {
        try (Connection c = tenantSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT dbo.envelope(?::jsonb, ?)")) {
            ps.setString(1, document);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return JSON.readTree(rs.getString(1));
            }
        }
    }

    private static PGSimpleDataSource tenantSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + ROOT.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
