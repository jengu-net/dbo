package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.fhir.validate.ElementChecks;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    // --------------------------------------------------------- a third leg

    /** The types the corpus holds, plus the two ordinary ones the second half writes. */
    private static final Set<String> INDEXED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem", "ConceptMap",
            "OperationDefinition", "CapabilityStatement", "CompartmentDefinition",
            "NamingSystem", "StructureMap", "Patient", "Observation"));

    private static DefinitionIndex index;

    /** Built once: it is the same rows for every document put through it. */
    private static synchronized DefinitionIndex index() {
        if (index == null) {
            Set<String> seeds = new LinkedHashSet<>();
            for (String type : INDEXED) {
                seeds.add("http://hl7.org/fhir/StructureDefinition/" + type);
            }
            index = DefinitionRows.over(source(), DefinitionRows.closureOf(source(), seeds));
        }
        return index;
    }

    @Test
    @DisplayName("over everything the version publishes, neither the index nor the database "
            + "faults anything, and the walk went far enough for that to mean something")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theThirdAnswererNamesWhatTheDatabaseNames() {
        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int descents = 0;
        int deepest = 0;
        int spoken = 0;
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null || !index().holds(canonical)) {
                continue;
            }
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                ElementChecks.Checked checked =
                        ElementChecks.over(index(), canonical, document.document());
                Set<String> ours = new TreeSet<>();
                for (Finding one : checked.findings()) {
                    // Cardinality alone: the walk answers five kinds of thing
                    // now, and this half is compared against dbo.cardinality.
                    if ("cardinality".equals(one.key())) {
                        ours.add(withoutIndices(one.path()));
                    }
                }
                Set<String> theirs = cardinalityPaths(document.document(), canonical);
                compared++;
                descents += checked.descents();
                deepest = Math.max(deepest, checked.deepest());
                if (!ours.isEmpty() || !theirs.isEmpty()) {
                    spoken++;
                }
                if (!ours.equals(theirs)) {
                    divergences.add(ofType.getKey() + " " + document.url()
                            + ": the index says " + ours + ", the database says " + theirs);
                }
            }
        }

        System.out.printf("%n=== the index checker against dbo.cardinality, over r4 ===%n"
                + "documents compared %d, of which either answerer spoke about %d%n"
                + "the walk descended into %d nodes, deepest path %d segments%n"
                + "divergences %d%n", compared, spoken, descents, deepest, divergences.size());
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "only " + compared + " documents were compared");
        // WHAT THIS HALF IS, said rather than implied: both answerers are
        // silent about every one of these documents, so the agreement is an
        // agreement about silence. That is worth asserting — it is the claim
        // that neither faults the specification's own resources — and it is
        // not the claim that they say the same thing when something is wrong.
        // The other half of this pair is where that is asked.
        assertEquals(0, spoken,
                "an answerer faulted the specification's own conformance resources, which is "
                        + "either a real defect in these documents or a false positive");
        // And a clean corpus is also what a checker that never descended
        // reports, so the walk has to have gone somewhere before its silence
        // means anything at all.
        assertTrue(descents > 20_000,
                "the walk barely descended, so agreement means nothing: " + descents);
        assertTrue(deepest >= 4, "the walk never went deep: " + deepest);
        assertEquals(List.of(), divergences,
                "the two answerers over the same rows do not name the same elements");
    }

    private static BoundCodes codes;

    /** The codes behind the required bindings, read once beside the index. */
    private static synchronized BoundCodes codes() {
        if (codes == null) {
            codes = BoundCodes.over(source(), index());
        }
        return codes;
    }

    @Test
    @DisplayName("over everything the version publishes, the rules the index runs and the rules "
            + "the database runs fault the same documents")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theRulesAgreeOverTheVersion() {
        // An invariant is compiled when the definition arrives; both sides run
        // the same compiled text, one in Postgres and one here. Two thirds of
        // them are a grammar this reader implements and the rest it declines
        // — so what is compared is the rules BOTH ran, and how many were
        // declined is reported rather than hidden, because a reader that
        // declined everything would agree perfectly.
        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int spoken = 0;
        int foundHere = 0;
        int foundThere = 0;
        Set<String> agreedOn = new TreeSet<>();
        for (Map.Entry<String, List<FaceRootPackages.Definition>> ofType : corpus().entrySet()) {
            String canonical = definitions.theTypeItself(ofType.getKey()).orElse(null);
            if (canonical == null || !index().holds(canonical)) {
                continue;
            }
            for (FaceRootPackages.Definition document : ofType.getValue()) {
                Set<String> ours = new TreeSet<>();
                for (Finding one : ElementChecks.over(index(), canonical,
                        document.document()).findings()) {
                    if (RULE_KEYS.matcher(one.key() == null ? "" : one.key()).matches()) {
                        ours.add(one.key());
                    }
                }
                Set<String> theirs = ruleKeys(document.document(), canonical);
                compared++;
                foundHere += ours.size();
                foundThere += theirs.size();
                agreedOn.addAll(ours);
                if (!ours.isEmpty() || !theirs.isEmpty()) {
                    spoken++;
                }
                // The reader answers a subset, so what it reports must be a
                // subset of what the database reports. A rule it faults that
                // the database does not is the failure that matters: the two
                // ran the same compiled text and disagreed.
                Set<String> onlyOurs = new TreeSet<>(ours);
                onlyOurs.removeAll(theirs);
                if (!onlyOurs.isEmpty()) {
                    divergences.add(ofType.getKey() + " " + document.url()
                            + ": the index faults " + onlyOurs + " and the database does not");
                }
            }
        }
        // Every one of these documents breaks a rule, and the reason is the
        // fixture rather than the corpus: a definition is read with its
        // narrative removed, so dom-6 fails on all of them. That is what makes
        // this comparison worth running on the corpus at all — the cardinality
        // half is an agreement about silence, and this one is not.
        System.out.printf("%n=== the rules, index against database, over r4 ===%n"
                + "documents compared %d, either answerer spoke about %d%n"
                + "rule findings: %d from the index, %d from the database, over %d keys%n"
                + "divergences where the index faults what the database does not: %d%n",
                compared, spoken, foundHere, foundThere, agreedOn.size(),
                divergences.size());
        System.out.println("  the index reported: " + agreedOn);
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "only " + compared + " documents were compared");
        // A reader that declined every rule would be a perfect subset of the
        // database and prove nothing, so what it DID run is asserted too.
        assertTrue(foundHere > 100,
                "the index ran the rules and faulted almost nothing, so agreeing with the "
                        + "database about a subset means nothing: " + foundHere);
        assertEquals(List.of(), divergences,
                "the two ran the same compiled rule and disagreed");
    }

    /** A rule key, which is how an invariant finding is named apart from the other checks. */
    private static final java.util.regex.Pattern RULE_KEYS =
            java.util.regex.Pattern.compile("[a-z][a-z0-9]*-[0-9]+");

    /** The rule keys dbo.invariant_issues reports. */
    private static Set<String> ruleKeys(byte[] document, String canonical) {
        Set<String> keys = new TreeSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key FROM dbo.invariant_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("asking the database about the rules failed", e);
        }
        return keys;
    }

    @Test
    @DisplayName("a required binding is decided in the process, against a few hundred codes, "
            + "and the database says the same")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aRequiredBindingIsDecidedFromTheCodesHeld() {
        String patient = "http://hl7.org/fhir/StructureDefinition/Patient";
        System.out.printf("%n=== the codes behind %s's required bindings ===%n"
                + "%d value sets answerable, %d codes held, %d declined as too large%n",
                tenant.code(), codes().valueSets(), codes().codes(), codes().declined().size());

        // Patient.gender is bound to administrative-gender at required
        // strength, which is four codes.
        bothBind(patient, "{\"resourceType\":\"Patient\",\"gender\":\"female\"}", Set.of());
        bothBind(patient, "{\"resourceType\":\"Patient\",\"gender\":\"kass\"}",
                Set.of("Patient.gender"));

        // A CodeableConcept is satisfied by ANY of its codings, so one good
        // coding beside one bad one is not a refusal.
        bothBind(patient,
                "{\"resourceType\":\"Patient\",\"maritalStatus\":{\"coding\":["
                        + "{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\","
                        + "\"code\":\"M\"}]}}",
                Set.of());

        // And a code from a system the value set is not built from: knowable
        // without holding anything, and both know it.
        bothBind(patient,
                "{\"resourceType\":\"Patient\",\"maritalStatus\":{\"coding\":["
                        + "{\"system\":\"https://ee.ee/oma\",\"code\":\"X\"}]}}",
                Set.of());

        assertTrue(codes().codes() > 100,
                "too few codes held for this to have decided anything: " + codes().codes());
    }

    /** Both answerers on the binding check alone. */
    private void bothBind(String canonical, String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = new TreeSet<>();
        for (Finding one : ElementChecks.over(index(), codes(), canonical, bytes).findings()) {
            if ("binding".equals(one.key())) {
                ours.add(withoutIndices(one.path()));
            }
        }
        Set<String> theirs = new TreeSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.binding_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(bytes, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    theirs.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("asking the database about a binding failed", e);
        }
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), theirs, "the database: " + document);
    }

    @Test
    @DisplayName("and on documents that are actually wrong, at every depth, they name the same "
            + "elements")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theyAgreeAboutWhatIsWrong() {
        // The corpus is correct, so agreeing about it is half a proof: two
        // answerers that both say nothing agree perfectly. These are the
        // documents where something has to be said.
        String patient = "http://hl7.org/fhir/StructureDefinition/Patient";
        String observation = "http://hl7.org/fhir/StructureDefinition/Observation";

        // A required element absent. Observation.status and Observation.code
        // are both 1..1.
        bothSay(observation, "{\"resourceType\":\"Observation\"}",
                Set.of("Observation.status", "Observation.code"));

        // An element allowed once, sent twice — the case the toolchain drops
        // in silence and both of these report.
        bothSay(patient, "{\"resourceType\":\"Patient\",\"gender\":[\"female\",\"male\"]}",
                Set.of("Patient.gender"));

        // Unbounded, so many is correct and neither may speak.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"a\"},"
                        + "{\"family\":\"b\"}]}",
                Set.of());

        // DEPTH, inside a backbone: one contact holding two names is wrong.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"contact\":[{\"name\":[{\"family\":\"a\"},"
                        + "{\"family\":\"b\"}]}]}",
                Set.of("Patient.contact.name"));

        // And per parent: two contacts holding one name each is correct. An
        // answerer counting across the document gets this one wrong.
        bothSay(patient,
                "{\"resourceType\":\"Patient\",\"contact\":[{\"name\":{\"family\":\"a\"}},"
                        + "{\"name\":{\"family\":\"b\"}}]}",
                Set.of());

        // INSIDE A DATATYPE THEY DIVERGE, and the index is the one that
        // reaches further. HumanName.family is 0..1 and is stated in
        // HumanName's own structure; Patient's snapshot names Patient.name as
        // a HumanName and stops. The database walks one profile's rows, so it
        // has nothing to say here and says nothing — which is the promise it
        // makes rather than a defect. The index holds the closure, so it
        // enters HumanName and speaks.
        //
        // This is the whole reason a third answerer is worth having and not
        // only cheaper: it answers where one profile's rows stop.
        String insideAType =
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":[\"a\",\"b\"]}]}";
        assertEquals(Set.of("Patient.name.family"), pathsFromTheIndex(patient, insideAType),
                "the index checker did not enter the datatype's own structure");
        assertEquals(Set.of(), cardinalityPaths(insideAType.getBytes(StandardCharsets.UTF_8),
                        patient),
                "the database spoke inside a datatype no profile constrains, which is a change "
                        + "in how far the rows reach and not a change to this comparison");
    }

    /**
     * Both answerers, over one document, against what should be said.
     *
     * <p>The expectation is stated as well as the agreement, because two
     * answerers can agree by both being silent and that is exactly what the
     * corpus half cannot rule out.
     */
    private void bothSay(String canonical, String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = pathsFromTheIndex(canonical, document);
        Set<String> theirs = cardinalityPaths(bytes, canonical);
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), theirs, "the database: " + document);
    }

    /** What the index checker faults, as the elements rather than the occurrences. */
    private static Set<String> pathsFromTheIndex(String canonical, String document) {
        Set<String> paths = new TreeSet<>();
        for (Finding one : ElementChecks.over(index(), canonical,
                document.getBytes(StandardCharsets.UTF_8)).findings()) {
            if ("cardinality".equals(one.key())) {
                paths.add(withoutIndices(one.path()));
            }
        }
        return paths;
    }

    /** What dbo.cardinality faults, as the definition paths it names. */
    private static Set<String> cardinalityPaths(byte[] document, String canonical) {
        Set<String> paths = new TreeSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.cardinality_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("asking the database about cardinality failed", e);
        }
        return paths;
    }

    /**
     * An instance path reduced to the definition path it is an instance of.
     *
     * <p>The database names the element — {@code Patient.contact.name} — and
     * the index checker names the occurrence — {@code Patient.contact[0].name}
     * — because a caller fixing a document needs to know which contact. What
     * is compared is which ELEMENT each faulted, so the occurrence is dropped
     * on this side rather than added on the other.
     */
    private static String withoutIndices(String path) {
        return path.replaceAll("\\[\\d+\\]", "");
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
            // The error and fatal issues alone, and not the outcome whole.
            // Truncating the outcome was the obvious thing and it cut the
            // findings off: an outcome leads with warnings, so the first
            // fifteen hundred characters of one can be entirely the part that
            // decides nothing, and a reader concludes there were no errors.
            String outcome = tenant.store()
                    .validationOutcome(new String(document, StandardCharsets.UTF_8));
            StringBuilder deciding = new StringBuilder();
            java.util.regex.Matcher issue = java.util.regex.Pattern
                    .compile("\\{\"severity\":\"(error|fatal)\".*?\\}(?=,\\{\"severity\"|\\]\\})")
                    .matcher(outcome);
            while (issue.find()) {
                deciding.append("    ").append(issue.group()).append(System.lineSeparator());
            }
            return deciding.isEmpty()
                    ? "no error or fatal issue, so what counted it is the refusal itself"
                    : deciding.toString();
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
