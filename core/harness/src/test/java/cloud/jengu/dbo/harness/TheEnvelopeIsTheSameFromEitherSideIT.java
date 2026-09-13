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

        // A reference is what it points at, split.
        assertEquals(JSON.readTree("[{\"t\":\"ref\",\"tt\":\"Patient\",\"ti\":\"123\"}]"),
                pairs("k", "reference", "{\"reference\":\"Patient/123\"}"));
        assertEquals(JSON.readTree("[{\"t\":\"ref\",\"tt\":\"Patient\",\"ti\":\"123\"}]"),
                pairs("k", "reference", "\"https://example.test/fhir/Patient/123\""));

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

    @Test
    @Timeout(900)
    @DisplayName("over every document this face carries, the database builds the envelope, the "
            + "claims and the edges the engine stored")
    void theThreePartsAreWhatTheEngineStored() throws Exception {
        // Not against the extractor called directly, but against what actually
        // landed: the envelope column, the identifier rows and the reference
        // rows a real write left behind. That is the comparison worth making,
        // because it is the one the engine will stop doing.
        Map<String, int[]> perType = new TreeMap<>();
        List<String> differences = new ArrayList<>();

        try (Connection c = tenantSource().getConnection()) {
            for (String type : List.of("StructureDefinition", "SearchParameter",
                    "ValueSet", "CodeSystem")) {
                for (Stored stored : carried(c, type)) {
                    int[] counted = perType.computeIfAbsent(type, k -> new int[2]);
                    counted[0]++;
                    String why = disagreement(c, stored);
                    if (why != null) {
                        counted[1]++;
                        if (differences.size() < 10) {
                            differences.add(type + " " + stored.id() + ": " + why);
                        }
                    }
                }
            }
        }

        Map<String, String> compared = new TreeMap<>();
        perType.forEach((type, counted) ->
                compared.put(type, counted[0] + " compared, " + counted[1] + " differing"));
        assertEquals(4, perType.size(),
                "a type this face carries contributed no document, so the comparison is "
                        + "narrower than it reads: " + compared);
        assertTrue(perType.values().stream().allMatch(counted -> counted[0] == PER_TYPE),
                "a type contributed fewer documents than were asked for, so the face is not "
                        + "carrying what this assumed: " + compared);
        // Recorded rather than demanded. The two sides are not the same yet and
        // saying so is the point: what a document is found by is the whole of
        // what a search answers, so this number has to reach zero per type
        // before a type is served from the database's side — and until it does,
        // it may fall and may not rise.
        Map<String, Integer> differing = new TreeMap<>();
        perType.forEach((type, counted) -> differing.put(type, counted[1]));
        Map<String, Integer> was = recorded();
        List<String> risen = new ArrayList<>();
        differing.forEach((type, now) -> {
            Integer before = was.get(type);
            if (before != null && now > before) {
                risen.add(type + " " + before + " -> " + now);
            }
        });
        record(perType);
        assertEquals(List.of(), risen,
                "more documents disagree than did before, so something that was findable "
                        + "from the database's envelope no longer is. The first few: "
                        + differences);
    }

    // ------------------------------------------------------------- the two
    /** One document as the engine left it. */
    private record Stored(String id, String type, String payload, String envelope) {}

    /** A page of what this face carries, per type, so no type decides the number. */
    private static List<Stored> carried(Connection c, String type) throws Exception {
        List<Stored> stored = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id::text, type, convert_from(payload, 'UTF8'), envelope::text
                  FROM %s_data WHERE type = ? AND NOT deleted
                 ORDER BY id LIMIT ?""".formatted(Domains.tables(Domains.DEFINITIONS)))) {
            ps.setString(1, type);
            ps.setInt(2, PER_TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stored.add(new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4)));
                }
            }
        }
        return stored;
    }

    /**
     * What the database makes of one document against what the engine stored,
     * or null when they agree.
     *
     * <p>The canonical claim is taken off the engine's side rather than added
     * to the database's: which types are identified by their own url is
     * registration's knowledge, so the function does not have it and says so.
     */
    private static String disagreement(Connection c, Stored stored) throws Exception {
        JsonNode parts;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT dbo.envelope_parts(?::jsonb, ?)")) {
            ps.setString(1, stored.payload());
            ps.setString(2, stored.type());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                parts = JSON.readTree(rs.getString(1));
            }
        }
        JsonNode built = parts.get("envelope");
        JsonNode kept = JSON.readTree(stored.envelope());
        if (!built.equals(kept)) {
            // Which keys, not merely that they differ: an envelope is thirty
            // keys and "differs" sends the reader back to the database to ask
            // the question this already knows the answer to.
            Set<String> onlyBuilt = new java.util.TreeSet<>();
            built.fieldNames().forEachRemaining(onlyBuilt::add);
            Set<String> onlyKept = new java.util.TreeSet<>();
            kept.fieldNames().forEachRemaining(onlyKept::add);
            Set<String> shared = new java.util.TreeSet<>(onlyBuilt);
            shared.retainAll(onlyKept);
            onlyBuilt.removeAll(shared);
            onlyKept.removeAll(shared);
            Set<String> valued = new java.util.TreeSet<>();
            for (String key : shared) {
                if (!built.get(key).equals(kept.get(key))) {
                    valued.add(key + " database=" + built.get(key) + " engine=" + kept.get(key));
                }
            }
            return "envelope differs; onlyInDatabase=" + onlyBuilt + " onlyInEngine=" + onlyKept
                    + " differingValues=" + valued;
        }
        Set<String> claimed = new java.util.TreeSet<>();
        for (JsonNode one : parts.get("identifiers")) {
            claimed.add(one.get("system").asText() + "|" + one.get("value").asText());
        }
        if (!claimed.equals(storedClaims(c, stored))) {
            return "claims differ: database=" + claimed + " engine=" + storedClaims(c, stored);
        }
        Set<String> pointed = new java.util.TreeSet<>();
        for (JsonNode one : parts.get("references")) {
            pointed.add(one.get("refType").asText() + " -> " + one.get("targetType").asText()
                    + "/" + one.get("targetId").asText());
        }
        if (!pointed.equals(storedEdges(c, stored))) {
            return "edges differ: database=" + pointed + " engine=" + storedEdges(c, stored);
        }
        return null;
    }

    private static Set<String> storedClaims(Connection c, Stored stored) throws Exception {
        Set<String> claims = new java.util.TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT system, value FROM %s_identifier
                 WHERE object_id = ?::uuid AND system <> ?"""
                .formatted(Domains.tables(Domains.DEFINITIONS)))) {
            ps.setString(1, stored.id());
            ps.setString(2, cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claims.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
        }
        return claims;
    }

    private static Set<String> storedEdges(Connection c, Stored stored) throws Exception {
        Set<String> edges = new java.util.TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT ref_type, target_type, target_id FROM %s_reference WHERE owner_id = ?::uuid"""
                .formatted(Domains.tables(Domains.DEFINITIONS)))) {
            ps.setString(1, stored.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    edges.add(rs.getString(1) + " -> " + rs.getString(2) + "/" + rs.getString(3));
                }
            }
        }
        return edges;
    }

    /** What disagreed last time, per type, or nothing on a first run. */
    private static Map<String, Integer> recorded() throws Exception {
        Path baseline = baseline();
        if (!Files.exists(baseline)) {
            return Map.of();
        }
        Map<String, Integer> was = new TreeMap<>();
        for (String line : Files.readAllLines(baseline)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            was.put(parts[0], Integer.parseInt(parts[2].substring("differing=".length())));
        }
        return was;
    }

    private static Path baseline() {
        return Files.isDirectory(BASELINE.getParent()) ? BASELINE
                : Path.of("config", "envelope-baseline.txt");
    }

    /** What was compared, so a later run can see the corpus shrink. */
    private static void record(Map<String, int[]> perType) throws Exception {
        StringBuilder out = new StringBuilder("""
                # What the database's envelope, claims and edges agree with the engine about,
                # over the documents a face carries. One line per resource type:
                #
                #   <type> compared=<n> differing=<n>
                #
                # GENERATED by TheEnvelopeIsTheSameFromEitherSideIT.
                #
                # `differing` may fall and may not rise. It is not zero: the database keys an
                # envelope by the parameter's own code where the engine underscores it, it
                # carries parameters the definition path does not extract, and one hit reached
                # by two compiled paths appears twice. A type is served from the database's
                # envelope when its number here is zero and not before — a difference is not a
                # wrong answer but a missing one, and an empty result reads like there being
                # nothing to find.
                """);
        perType.forEach((type, counted) -> out.append(type).append(" compared=")
                .append(counted[0]).append(" differing=").append(counted[1]).append('\n'));
        Files.writeString(baseline(), out.toString());
    }


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
