package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two checkers, over the whole of what a version publishes.
 *
 * <p>The advisory tally on a tenant's own writes says whether they agree
 * about that tenant's traffic. This says whether they agree about the
 * specification: every definition the version ships is a real document of a
 * real type, deep, sliced, bound and referenced, and it is the corpus this
 * store already carries.
 *
 * <p><b>What the corpus is not.</b> It is not the instance examples — a
 * patient, an observation. Those ship in a package of their own that this
 * store does not carry, because a store has no use for them. What it carries
 * is the conformance resources, and they are documents like any other.
 *
 * <p><b>A baseline that may only fall.</b> The number recorded is what the two
 * disagree about today, per resource type. A change that lowers it re-records
 * it; a change that raises it fails here, which is the point — the whole
 * argument for the database answering at all is that it answers the same.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheTwoAnswersAreComparedOverTheVersionIT {

    private static final String ROOT = "korpus-juur";

    /**
     * Per type, so the slow ones do not decide how long this takes and the
     * rare ones are not crowded out. In filename order, so the same documents
     * are compared every run and the baseline means something.
     */
    private static final int PER_TYPE = 40;

    private static final Path BASELINE = Path.of("..", "..", "config", "divergence-baseline.txt");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static DefinitionStore definitions;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-corpus");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheTwoAnswersAreComparedOverTheVersionIT"),
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
        definitions = new DefinitionStore(source());
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
    @DisplayName("over everything the version publishes, the two disagree about no more than "
            + "what was recorded")
    @Proving(DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION)
    void theyDisagreeAboutNoMoreThanWasRecorded() throws Exception {
        Map<String, int[]> perType = new TreeMap<>();
        int compared = 0;
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null) {
                continue; // the root holds no definition of this type to judge by
            }
            int[] tally = perType.computeIfAbsent(ofType.getKey(), ignored -> new int[3]);
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                boolean toolchainFound = theToolchainRefuses(document.document());
                boolean databaseFound = definitions.issuesUnder(document.document(), canonical)
                        .orElse(0) > 0;
                tally[0]++;
                compared++;
                if (toolchainFound != databaseFound) {
                    tally[toolchainFound ? 1 : 2]++;
                }
            }
        }
        assertTrue(compared > 200, "only " + compared + " documents were compared");

        String observed = asLines(perType);
        Path baseline = BASELINE.toAbsolutePath().normalize();
        if (!Files.exists(baseline) || Boolean.getBoolean("dbo.divergence.record")) {
            Files.writeString(baseline, PREAMBLE + observed);
            System.out.println("divergence baseline recorded: " + baseline);
            return;
        }
        assertNoWorseThan(Files.readString(baseline), observed);
    }

    /**
     * Every type's divergence is compared to its own recorded number, so a
     * type that got worse is named rather than hidden inside a total that
     * something else improved.
     */
    private static void assertNoWorseThan(String recorded, String observed) {
        Map<String, int[]> was = parse(recorded);
        Map<String, int[]> now = parse(observed);
        List<String> worse = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : now.entrySet()) {
            int[] before = was.get(entry.getKey());
            if (before == null) {
                continue; // a type nobody had compared before
            }
            int[] after = entry.getValue();
            if (after[1] > before[1] || after[2] > before[2]) {
                worse.add(entry.getKey() + ": was onlyTheToolchain=" + before[1]
                        + " onlyTheDatabase=" + before[2] + ", now onlyTheToolchain=" + after[1]
                        + " onlyTheDatabase=" + after[2]);
            }
        }
        assertEquals(List.of(), worse,
                "the two answers agree about less of the version than they did. Re-record "
                        + "with -Ddbo.divergence.record=true only when the change is meant.\n"
                        + observed);
    }

    // -------------------------------------------------------------- corpus

    /** What the version publishes, by type, bounded and in a fixed order. */
    private static Map<String, List<FaceRootPackages.Definition>> corpus() {
        Map<String, List<FaceRootPackages.Definition>> byType = new LinkedHashMap<>();
        for (FaceRootPackages.Definition definition : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
                        "ConceptMap", "OperationDefinition", "CapabilityStatement",
                        "CompartmentDefinition", "NamingSystem", "StructureMap"))) {
            List<FaceRootPackages.Definition> held =
                    byType.computeIfAbsent(definition.typeName(), ignored -> new ArrayList<>());
            if (held.size() < PER_TYPE) {
                held.add(definition);
            }
        }
        return byType;
    }

    private boolean theToolchainRefuses(byte[] document) {
        try {
            String outcome = manager.runtime(ROOT).orElseThrow().store()
                    .validationOutcome(new String(document, StandardCharsets.UTF_8));
            return outcome.contains("\"severity\":\"error\"")
                    || outcome.contains("\"severity\":\"fatal\"");
        } catch (RuntimeException refused) {
            // A refusal is a finding said louder; what is compared is whether
            // it found anything.
            return true;
        }
    }

    // ------------------------------------------------------------ the file

    private static final String PREAMBLE = """
            # What the toolchain and the database disagree about, over everything the
            # version publishes. One line per resource type:
            #
            #   <type> compared=<n> onlyTheToolchain=<n> onlyTheDatabase=<n>
            #
            # GENERATED. Re-record with:
            #     ./gradlew :core:harness:test --tests '*TheTwoAnswersAreCompared*' \\
            #         -Ddbo.divergence.record=true
            #
            # It may fall and may not rise. The database is measured beside the
            # toolchain and acts on nothing, and the whole case for it answering at
            # all is that it answers the same.
            #
            # What the remaining ten are, read one by one rather than assumed: rules
            # the toolchain knows that no definition states. A canonical url must be
            # absolute; a uuid must be lowercase; an identifier under urn:ietf:rfc:3986
            # must be a full uri; a StructureMap's source context must be one it
            # declared. They are hard-coded in the validator, so nothing compiled FROM
            # the definitions can produce them, and compiling invariants did not move
            # this number by one.
            #
            # Which bounds what "the specification is data" can reach. A checker built
            # from the definitions answers what the definitions say, and a validator
            # carries knowledge besides.
            """;

    private static String asLines(Map<String, int[]> perType) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> entry : perType.entrySet()) {
            int[] tally = entry.getValue();
            out.append(entry.getKey()).append(" compared=").append(tally[0])
                    .append(" onlyTheToolchain=").append(tally[1])
                    .append(" onlyTheDatabase=").append(tally[2]).append('\n');
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
            out.put(parts[0], new int[] {
                    number(parts[1]), number(parts[2]), number(parts[3])});
        }
        return out;
    }

    private static int number(String part) {
        return Integer.parseInt(part.substring(part.indexOf('=') + 1));
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + ROOT.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
