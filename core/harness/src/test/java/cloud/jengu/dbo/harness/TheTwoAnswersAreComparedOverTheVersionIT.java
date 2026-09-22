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

    static SharedTenants.Tenant tenant;
    static String ROOT;

    /**
     * Per type, so the slow ones do not decide how long this takes and the
     * rare ones are not crowded out. In filename order, so the same documents
     * are compared every run and the baseline means something.
     */
    private static final int PER_TYPE = 40;

    private static final Path BASELINE = Path.of("..", "..", "config", "divergence-baseline.txt");

    static DefinitionStore definitions;

    @BeforeAll
    void up() throws Exception {
        // Shared. A face root holds the version's whole definition set,
        // which is the most expensive thing this suite builds — and four
        // classes were each building one to ask a question about what the
        // version says, not about the tenant holding it.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        ROOT = tenant.code();
        definitions = new DefinitionStore(source());
    }

    @Test
    @DisplayName("over everything the version publishes, the two disagree about no more than "
            + "what was recorded")
    @Proving(DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION)
    void theyDisagreeAboutNoMoreThanWasRecorded() throws Exception {
        Map<String, int[]> perType = new TreeMap<>();
        // Written to a file rather than printed. A test's standard output goes
        // nowhere by default and this task forwards none of it, so a naming
        // that printed would be a naming nobody reads — which is how the
        // recording flag above spent a while doing nothing.
        StringBuilder named = new StringBuilder();
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
                    // What the tally cannot say: WHICH finding. A count of
                    // documents orders the work and never names it, so the
                    // findings are printable on request — the ten are read one
                    // by one and the reading is what the baseline's own preamble
                    // is made of.
                    if (Boolean.getBoolean("dbo.divergence.name")) {
                        named.append(ofType.getKey()).append("  ")
                                .append(toolchainFound ? "onlyTheToolchain" : "onlyTheDatabase")
                                .append(System.lineSeparator())
                                .append(saidBy(document.document(), toolchainFound, canonical))
                                .append(System.lineSeparator())
                                .append(System.lineSeparator());
                    }
                }
            }
        }
        assertTrue(compared > 200, "only " + compared + " documents were compared");
        if (Boolean.getBoolean("dbo.divergence.name")) {
            Path where = Path.of("build", "divergence-findings.txt");
            Files.createDirectories(where.getParent());
            Files.writeString(where, named.toString());
            System.out.println("divergence findings written: " + where.toAbsolutePath());
        }

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

    /** The errors the side that found something reported, for reading. */
    private String saidBy(byte[] document, boolean toolchain, String canonical) {
        try {
            if (!toolchain) {
                return "the database found "
                        + definitions.issuesUnder(document, canonical).orElse(0);
            }
            String outcome = tenant.store()
                    .validationOutcome(new String(document, StandardCharsets.UTF_8));
            return outcome.length() > 1500 ? outcome.substring(0, 1500) + "…" : outcome;
        } catch (RuntimeException refused) {
            return "refused: " + refused.getMessage();
        }
    }

    private boolean theToolchainRefuses(byte[] document) {
        try {
            String outcome = tenant.store()
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
            # What the remaining FIVE are, read one by one — and read by the findings that
            # actually decided each one. An outcome carries everything the face has to say,
            # warnings included; only error and fatal decide this count, and the first
            # sentence in an outcome is usually neither.
            #
            # RULES THE VALIDATOR CARRIES IN ITS OWN CODE, which no definition states: a
            # canonical url must be absolute, a uuid must be lowercase, an identifier under
            # urn:ietf:rfc:3986 must be a full uri. Three rules over six documents, and the
            # absolute-url one accounted for eighteen findings by itself. Nothing compiled
            # FROM the definitions can produce these, and compiling invariants did not move
            # them by one.
            #
            # TWO OF THE THREE ARE WRITTEN NOW, in dbo.admits beside the primitive forms:
            # a uuid is lowercase and a canonical carries a scheme. That closed every
            # CapabilityStatement — five documents, and the count with them. What a
            # definition cannot state, somebody states once.
            #
            # A STRUCTUREMAP CHECKED AS A PROGRAM rather than as a document: a source or
            # target context must be one the map declared, and a target path must exist on
            # the type it targets. Both maps this version publishes diverge this way. A
            # checker built from StructureDefinitions has nothing to say about either,
            # because neither is a statement about the shape of a StructureMap.
            #
            # ONE IS THE TOOLCHAIN FAILING, not this store lacking. Constraint cid-0 cannot
            # be evaluated at all — the validator holds that invariant and reports that the
            # name in its own expression is not valid for any of the possible types. It is
            # a divergence and it is counted, but the side that said nothing is not the
            # side that was wrong.
            #
            # ONE IS CONTENT: codes under http://snomed.info/sct that this tenant's
            # terminology does not hold. No rule of any kind answers that. It closes by
            # carrying the content and would not have moved however many invariants were
            # compiled.
            #
            # So the ceiling that "the specification is data" runs into was the first group
            # and only it, and two thirds of that group is now written down. What is left
            # is a program checker, a defect in the toolchain, and a gap in what is loaded
            # — none of which a checker built from StructureDefinitions was ever going to
            # answer.
            #
            # Which of the five is which is printable rather than remembered:
            #     ./gradlew :core:harness:test --tests '*TheTwoAnswersAreCompared*' \
            #         -Ddbo.divergence.name=true
            #     cat core/harness/build/divergence-findings.txt
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
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
