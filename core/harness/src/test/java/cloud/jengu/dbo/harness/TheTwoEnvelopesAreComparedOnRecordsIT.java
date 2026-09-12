package cloud.jengu.dbo.harness;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index, built twice over the same records, and compared.
 *
 * <p>What a document can be found by is its envelope, and it has been built in
 * the JVM by evaluating thirty-odd expressions over an object tree on every
 * write. The database can now build one from the compiled parameters and the
 * bytes already in hand. Moving where that happens is worth exactly nothing
 * unless what comes out is the same, so this writes records through a tenant —
 * which makes the JVM build and store its envelope — and asks the database for
 * its own over the same bytes.
 *
 * <p><b>Ordinary records, on purpose.</b> The first attempt at this compared
 * the two over the definitions a version publishes and found nought agreement
 * out of a hundred, for a reason that was not a defect: definition types are
 * extracted by a narrow hand-written path of their own, so the comparison was
 * between two different contracts. The parameter-driven rules govern ordinary
 * resources, and those are what is compared here.
 *
 * <p><b>The documents are written here because this store carries none.</b> A
 * version ships its conformance resources and not its examples. So the corpus
 * is small and deliberate instead of large and borrowed: one document per
 * shape the rules have to get right — a coded value, an identifier with a
 * system and one without, a folded string, a date at three precisions, a
 * period, a reference, a number, a uri.
 *
 * <p><b>What the record still names is the choice a question asks about.</b>
 * A parameter can be a question rather than a path — {@code deceased} is
 * whether a patient is dead, not a value read out of one — and its answer is
 * compiled as a predicate. A predicate over a choice element still names the
 * element the way the model spells it, so the one that asks after
 * {@code Patient.deceased} does not see a {@code deceasedDateTime}. The paths
 * are spelled out per key and the predicates are not, yet.
 *
 * <p><b>The number may rise and may not fall.</b> A key the two disagree about
 * is a search that answers differently depending on which side built the
 * index, and the difference shows up as an empty result rather than as a
 * fault.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheTwoEnvelopesAreComparedOnRecordsIT {

    private static final String TENANT = "umbrik";
    private static final String MRN = "https://envelope.test/mrn";

    /** Handed over by the build, which is also what makes it an input. */
    private static final Path BASELINE =
            Path.of(System.getProperty("dbo.envelope.baseline",
                    "../../config/envelope-baseline.txt"));
    private static final ObjectMapper JSON = new ObjectMapper();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-envelope-records");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheTwoEnvelopesAreComparedOnRecordsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"},
                  {"name":"Encounter","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT, MRN));
        UntilServed.scan(manager, TENANT);

        for (String document : documents()) {
            manager.runtime(TENANT).orElseThrow().store().create(document);
        }
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
    @DisplayName("the database builds the envelope the JVM built, over the same records, and "
            + "agrees about no less than was recorded")
    @Proving(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE)
    void theTwoEnvelopesAreComparedAndHeldTo() throws Exception {
        Map<String, int[]> perType = new TreeMap<>();
        Map<String, Set<String>> differingKeys = new TreeMap<>();

        try (Connection c = tenantSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT type, envelope, convert_from(payload, 'UTF8'),
                            dbo.envelope(convert_from(payload, 'UTF8')::jsonb, type)
                       FROM state.r4_data WHERE NOT deleted ORDER BY type, id""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString(1);
                int[] tally = perType.computeIfAbsent(type, ignored -> new int[2]);
                tally[0]++;
                // The shape stamp is the engine's, not the extractor's: it
                // says which pack versions the accept was judged against,
                // and it joins the envelope after extraction from the accept
                // event rather than from the bytes. Comparing it here would
                // be comparing the engine with itself.
                com.fasterxml.jackson.databind.node.ObjectNode inForce =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                JSON.readTree(rs.getString(2));
                inForce.remove("_shape");
                JsonNode built = JSON.readTree(rs.getString(4));
                if (inForce.equals(built)) {
                    tally[1]++;
                } else {
                    differingKeys.computeIfAbsent(type, ignored -> new TreeSet<>())
                            .addAll(keysThatDiffer(inForce, built));
                }
            }
        }

        int compared = perType.values().stream().mapToInt(t -> t[0]).sum();
        assertTrue(compared >= 8, "only " + compared + " records were compared");

        // The edges are the other half of what a write produces, and they are
        // held one way round only. Every edge the write stored has to be
        // built, because one the database misses is a chain that goes quiet.
        // An edge it builds and the write did not is the opposite — the
        // extractor in force drops a reference parameter whose expression its
        // evaluator cannot run, and the compiled form can run it — so those
        // are not a fault: that is the compiled form doing more, not less.
        List<String> stored = edgesInForce();
        List<String> built = edgesTheDatabaseBuilds();
        List<String> missing = new ArrayList<>(stored);
        missing.removeAll(built);
        assertEquals(List.of(), missing,
                "the database builds no edge for a reference the write stored");

        // The identifiers are the third thing a write derives, and the one a
        // conditional write is adjudicated on. Held outright: an identifier
        // the database does not build is a record that cannot be written to
        // by the name its author knows it by.
        assertEquals(identifiersInForce(), identifiersTheDatabaseBuilds(),
                "the identifiers the database builds are not the ones the write stored");

        String observed = asLines(perType, differingKeys);
        Path baseline = BASELINE.toAbsolutePath().normalize();
        if (!Files.exists(baseline) || Boolean.getBoolean("dbo.envelope.record")) {
            Files.writeString(baseline, PREAMBLE + observed);
            System.out.println("envelope baseline recorded: " + baseline);
            System.out.println(observed);
            return;
        }
        assertNoWorseThan(Files.readString(baseline), observed);
    }

    /** What the write stored: one row per identifier claimed. */
    private static List<String> identifiersInForce() throws Exception {
        return lines("""
                SELECT i.type || ' ' || COALESCE(i.system, '') || '|' || i.value
                  FROM state.r4_identifier i JOIN state.r4_data d ON d.id = i.object_id
                 WHERE NOT d.deleted ORDER BY 1""");
    }

    /** The same, built from the bytes by the database. */
    private static List<String> identifiersTheDatabaseBuilds() throws Exception {
        return lines("""
                SELECT d.type || ' ' || COALESCE(i.system, '') || '|' || i.value
                  FROM state.r4_data d,
                       LATERAL dbo.identifiers(
                           convert_from(d.payload, 'UTF8')::jsonb, d.type) i
                 WHERE NOT d.deleted ORDER BY 1""");
    }

    /** What the write stored: one row per edge, as a sortable line. */
    private static List<String> edgesInForce() throws Exception {
        return lines("""
                SELECT d.type || ' ' || r.ref_type || ' ' || r.target_type
                       || '/' || r.target_id
                  FROM state.r4_reference r JOIN state.r4_data d ON d.id = r.owner_id
                 WHERE NOT d.deleted ORDER BY 1""");
    }

    /** The same, built from the bytes by the database. */
    private static List<String> edgesTheDatabaseBuilds() throws Exception {
        return lines("""
                SELECT d.type || ' ' || e.ref_type || ' ' || e.target_type
                       || '/' || e.target_id
                  FROM state.r4_data d,
                       LATERAL dbo.reference_edges(
                           convert_from(d.payload, 'UTF8')::jsonb, d.type) e
                 WHERE NOT d.deleted ORDER BY 1""");
    }

    private static List<String> lines(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = tenantSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Per type against its own number, so a type that got worse is named. */
    private static void assertNoWorseThan(String recorded, String observed) {
        Map<String, int[]> was = parse(recorded);
        Map<String, int[]> now = parse(observed);
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : now.entrySet()) {
            int[] before = was.get(entry.getKey());
            if (before != null && entry.getValue()[1] < before[1]) {
                worse.add(entry.getKey() + ": agreed about " + before[1] + " of " + before[0]
                        + ", now " + entry.getValue()[1] + " of " + entry.getValue()[0]);
            }
        }
        assertEquals(List.of(), worse,
                "the database agrees with the envelope in force about fewer records than it "
                        + "did. Re-record with -Ddbo.envelope.record=true only when meant.\n"
                        + observed);
    }

    /** Which keys the two disagree about, which is what names the work left. */
    private static Set<String> keysThatDiffer(JsonNode inForce, JsonNode built) {
        Set<String> keys = new TreeSet<>();
        Set<String> all = new TreeSet<>();
        inForce.fieldNames().forEachRemaining(all::add);
        built.fieldNames().forEachRemaining(all::add);
        for (String key : all) {
            JsonNode a = inForce.get(key);
            JsonNode b = built.get(key);
            if (a == null) {
                keys.add(key + "(only the database)");
            } else if (b == null) {
                keys.add(key + "(only in force)");
            } else if (!a.equals(b)) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ------------------------------------------------------------ the corpus

    /** One document per shape the rules have to get right. */
    private static List<String> documents() {
        List<String> all = new ArrayList<>();
        all.add("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"1"}],
                 "name":[{"family":"Kask","given":["Mari","ANNE"]}],
                 "gender":"female","birthDate":"1980-02-29","active":true}"""
                .formatted(MRN));
        all.add("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"2"},{"value":"no-system"}],
                 "name":[{"family":"Tamm"}],"birthDate":"1990"}"""
                .formatted(MRN));
        all.add("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"3"}],
                 "name":[{"family":"Saar"}],"birthDate":"1975-06",
                 "telecom":[{"system":"phone","value":"+37255555"}],
                 "deceasedBoolean":false}"""
                .formatted(MRN));
        all.add("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"1234-5",
                                    "display":"A code"}],"text":"a measurement"},
                 "subject":{"reference":"Patient/abc"},
                 "effectiveDateTime":"2021-03-04T05:06:07Z",
                 "valueQuantity":{"value":12.5,"unit":"kg"}}""");
        all.add("""
                {"resourceType":"Observation","status":"amended",
                 "code":{"coding":[{"code":"no-system-code"}]},
                 "subject":{"reference":"https://elsewhere.test/fhir/Patient/xyz"},
                 "effectivePeriod":{"start":"2022-01-02","end":"2022-02-03"}}""");
        all.add("""
                {"resourceType":"Observation","status":"registered",
                 "code":{"coding":[{"system":"http://loinc.org","code":"9999-9"},
                                   {"system":"urn:other","code":"X"}]},
                 "subject":{"reference":"Patient/abc"}}""");
        all.add("""
                {"resourceType":"Encounter","status":"finished",
                 "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode",
                          "code":"AMB"},
                 "subject":{"reference":"Patient/abc"},
                 "period":{"start":"2020-12-31T23:00:00Z"}}""");
        all.add("""
                {"resourceType":"Encounter","status":"planned",
                 "class":{"code":"IMP"},
                 "period":{"start":"2023"}}""");
        // What the engine indexes on its own: no parameter expresses a
        // profile or a tag, and a search by either answers empty rather than
        // wrong when nothing wrote them.
        all.add("""
                {"resourceType":"Patient",
                 "meta":{"profile":["http://hl7.org/fhir/StructureDefinition/Patient"],
                         "tag":[{"system":"urn:tags","code":"synced"},{"code":"bare"}]},
                 "identifier":[{"system":"%s","value":"5"}],
                 "name":[{"family":"Lepik"}]}"""
                .formatted(MRN));
        // Crowded ones, because most of what a parameter has to get right is
        // a repeat, a nested element or a second choice in the same document.
        all.add("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"4"}],
                 "name":[{"family":"Kuusk","given":["Jaan"]},
                         {"family":"Kuusk-Mets","given":["Jaan","Peeter"]}],
                 "telecom":[{"system":"email","value":"Jaan@Example.Test"},
                            {"system":"phone","value":"+37244444"}],
                 "address":[{"city":"Tallinn","postalCode":"10111","country":"EE",
                             "line":["Pikk 1"]}],
                 "gender":"male","birthDate":"2000-07-14",
                 "deceasedDateTime":"2024-11-02T08:00:00+02:00",
                 "communication":[{"language":{"coding":[
                     {"system":"urn:ietf:bcp:47","code":"et"}]}}]}"""
                .formatted(MRN));
        all.add("""
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category",
                                         "code":"vital-signs"}]}],
                 "code":{"coding":[{"system":"http://loinc.org","code":"85354-9"}]},
                 "subject":{"reference":"Patient/abc"},
                 "effectiveDateTime":"2023-08-09",
                 "component":[
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8480-6"}]},
                    "valueQuantity":{"value":120,"unit":"mmHg"}},
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8462-4"}]},
                    "valueQuantity":{"value":80,"unit":"mmHg"}}]}""");
        all.add("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"3141-9"}]},
                 "subject":{"identifier":{"system":"%s","value":"9"}},
                 "effectiveDateTime":"2019",
                 "valueString":"a written value"}"""
                .formatted(MRN));
        all.add("""
                {"resourceType":"Encounter","status":"in-progress",
                 "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode",
                          "code":"EMER"},
                 "type":[{"coding":[{"system":"urn:type","code":"walk-in"}],
                          "text":"Walk in"}],
                 "subject":{"reference":"Patient/abc"},
                 "participant":[{"individual":{"reference":"Practitioner/p1"}}],
                 "period":{"start":"2024-02-29T10:00:00Z","end":"2024-02-29T11:30:00Z"},
                 "reasonCode":[{"coding":[{"system":"urn:reason","code":"pain"}]}]}""");
        return all;
    }

    // ------------------------------------------------------------- the file

    private static final String PREAMBLE = """
            # The envelope in force, and the one the database builds from the same
            # bytes. One line per resource type:
            #
            #   <type> compared=<n> agreed=<n> differing=<keys>
            #
            # GENERATED. Re-record with:
            #     ./gradlew :core:harness:test --tests '*TheTwoEnvelopesAreCompared*' \\
            #         -Ddbo.envelope.record=true
            #
            # `agreed` may rise and may not fall. What a document can be found by is
            # the whole of what a search answers, so moving where the envelope is
            # built is worth nothing unless what it builds is the same — and a
            # difference is a document that quietly stops being findable, which reads
            # as an empty result rather than as a fault.
            #
            # The keys named on each line are the work left, not decoration: each is a
            # way of asking that the two sides answer differently today. Nothing is
            # served from the database's envelope until a type's line says it agrees
            # about all of them.
            #
            # The records are written by the test, because a version ships its
            # conformance resources and not its examples. One document per shape the
            # rules have to get right, rather than a large borrowed corpus.
            """;

    private static String asLines(Map<String, int[]> perType, Map<String, Set<String>> differing) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> entry : perType.entrySet()) {
            out.append(entry.getKey()).append(" compared=").append(entry.getValue()[0])
                    .append(" agreed=").append(entry.getValue()[1])
                    .append(" differing=")
                    .append(String.join("|", differing.getOrDefault(entry.getKey(), Set.of())))
                    .append('\n');
        }
        return out.toString();
    }

    private static Map<String, int[]> parse(String lines) {
        Map<String, int[]> out = new TreeMap<>();
        for (String line : lines.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            out.put(parts[0], new int[] {number(parts[1]), number(parts[2])});
        }
        return out;
    }

    private static int number(String part) {
        return Integer.parseInt(part.substring(part.indexOf('=') + 1));
    }

    private static PGSimpleDataSource tenantSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + TENANT));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
