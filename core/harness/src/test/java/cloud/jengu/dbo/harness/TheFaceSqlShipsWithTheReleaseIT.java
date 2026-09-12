package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.definitions.FaceFunctions;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The functions a tenant's database answers with come from the release, and
 * they read the definitions the tenant holds.
 *
 * <p>What is proven here is the arrangement rather than a verdict: the
 * functions are in place after bring-up because the running dbo put them
 * there, they are the ones this build carries, a bring-up that changes
 * nothing installs nothing, and what they answer is computed from the rows a
 * definition was expanded into.
 *
 * <p>The one verdict they give so far is how often an element may occur, and
 * the case it has to get right is the one a path from the document root gets
 * wrong: a count that belongs inside a parent.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheFaceSqlShipsWithTheReleaseIT {

    private static final String CLINIC = "reeglid-kliinik";
    private static final String PROFILE = "https://ee.ee/StructureDefinition/uhe-nimega-patsient";
    private static final String PINNED = "https://ee.ee/StructureDefinition/ik-patsient";
    /** A tenant holding its whole version as records, which is where a binding can be judged. */
    private static final String ROOT = "reeglid-juur";
    private static final String SHAPE = "http://hl7.org/fhir/StructureDefinition/StructureDefinition";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-face-sql");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheFaceSqlShipsWithTheReleaseIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC));
        Files.writeString(dir.resolve(ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, CLINIC);
        UntilServed.scan(manager, ROOT);

        // A rule of this tenant's own: one name, and one name per contact is
        // what Patient already says.
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"UheNimegaPatsient","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.name","path":"Patient.name","min":1,"max":"1"}]}}"""
                .formatted(PROFILE));
        // And one that pins values rather than counts: an identifier system
        // it issues under, and a marital status it exists to record.
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"IkPatsient","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.identifier.system","path":"Patient.identifier.system",
                    "fixedUri":"https://ee.ee/ik"},
                   {"id":"Patient.maritalStatus","path":"Patient.maritalStatus",
                    "patternCodeableConcept":{"coding":[{"system":
                      "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}]}}]}}"""
                .formatted(PINNED));
        manager.runtime(CLINIC).orElseThrow().store().shapesChanged();
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
    @DisplayName("the release put its functions in the tenant's database, and a bring-up that "
            + "changes nothing installs nothing")
    @Proving(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE)
    void theReleaseInstalledItsOwnFunctions() throws Exception {
        assertEquals(List.of("dbo"), query(
                "SELECT nspname FROM pg_namespace WHERE nspname = 'dbo'"),
                "the functions have no schema of their own, so code and data share one");
        assertEquals(List.of("binding_in", "binding_issues", "cardinality_in",
                        "cardinality_issues", "coded_values", "date_key", "descends_from",
                        "envelope", "envelope_pairs", "in_value_set", "instances",
                        "invariant_holds", "invariant_in", "invariant_issues", "located",
                        "record_exists", "reference_edges", "reference_in",
                        "reference_issues", "token_forms", "validate", "value_in",
                        "value_issues", "walked"), query(
                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = 'dbo' ORDER BY p.proname"),
                "the release did not install the functions it carries");

        String installed = FaceFunctions.installedIn(tenantSource());
        assertNotNull(installed, "nothing records which functions are in place");

        List<String> before = query("SELECT installed_at::text FROM state.face_sql");
        assertEquals(installed, FaceFunctions.install(tenantSource()),
                "installing again changed which functions are in place");
        assertEquals(before, query("SELECT installed_at::text FROM state.face_sql"),
                "a release whose SQL did not change reinstalled it anyway");
    }

    @Test
    @DisplayName("the installed functions read a definition from one schema and nowhere else")
    @Proving(DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN)
    void theFunctionsReadDefinitionsFromOneSchema() throws Exception {
        // Asserted over what was INSTALLED rather than over the files, because
        // a function created from an older script is what the database would
        // actually run.
        //
        // Not "no function mentions the shared schema": one of them must reach
        // the records, since a reference points at one, and it finds them by
        // scanning every schema a domain's tables can be in. What may not
        // happen is a definition or a vocabulary being read from outside the
        // schema a face is cut from — that function would answer from rows an
        // image neither carries nor restores.
        // The body is fetched inside a subquery so it is only ever asked for
        // about a plain function in this schema: pg_get_functiondef refuses an
        // aggregate, and a planner free to call it before the filter finds one.
        List<String> reachingElsewhere = query(
                "SELECT proname || ' reads ' ||"
                + " substring(body from 'state[.](?:definition|term)_[a-z_]+') FROM ("
                + "   SELECT p.proname, pg_get_functiondef(p.oid) AS body"
                + "     FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + "    WHERE n.nspname = 'dbo' AND p.prokind = 'f') f"
                + " WHERE body ~ 'state[.](definition|term)_'"
                + " ORDER BY proname");
        assertEquals(List.of(), reachingElsewhere,
                "a face function reads what a face gave the tenant from outside the schema "
                        + "the face is cut from");
    }

    @Test
    @DisplayName("an element is counted inside the parent it occurs in, which is the count "
            + "the profile actually states")
    @Proving(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE)
    void anElementIsCountedInsideItsParent() throws Exception {
        // Three contacts, one name each. Flattened from the root that is
        // three names where one is allowed, and the document is correct.
        assertTrue(issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}],
                 "contact":[{"name":{"family":"A"}},{"name":{"family":"B"}},
                            {"name":{"family":"C"}}]}""").stream()
                        .noneMatch(issue -> issue.startsWith("cardinality")),
                "a patient whose contacts each have a name was refused");

        // One contact with two names is what the profile forbids, and only
        // a count taken inside that contact can see it.
        List<String> tooMany = issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}],
                 "contact":[{"name":[{"family":"A"},{"family":"B"}]}]}""");
        assertEquals(1, tooMany.size(), "expected one finding, got: " + tooMany);
        assertTrue(tooMany.get(0).contains("Patient.contact.name")
                        && tooMany.get(0).contains("2 times"),
                "the finding does not say what occurred too often: " + tooMany);
    }

    @Test
    @DisplayName("the tenant's own rule is answered from the rows its profile was expanded "
            + "into, inherited elements and all")
    @Proving(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE)
    void theTenantsOwnRuleIsAnswered() throws Exception {
        assertTrue(issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}]}""").stream()
                        .noneMatch(issue -> issue.contains("Patient.name")),
                "a patient with the one name the profile requires was refused");

        List<String> none = issues("""
                {"resourceType":"Patient","gender":"female"}""");
        assertTrue(none.stream().anyMatch(issue -> issue.contains("Patient.name")),
                "a patient with no name at all passed a profile that requires one: " + none);

        List<String> two = issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"},{"family":"Kask"}]}""");
        assertTrue(two.stream().anyMatch(issue -> issue.contains("Patient.name")),
                "a patient with two names passed a profile that allows one: " + two);

        // And an element nothing can locate is not counted as absent: a row
        // with no way to find it would refuse every document that has what
        // it asks for.
        assertFalse(issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}]}""").stream()
                        .anyMatch(issue -> issue.contains("unenforceable")),
                "an element that cannot be located was counted anyway");
    }

    @Test
    @DisplayName("a coded value is judged against the terminology the tenant holds, and one "
            + "nothing here can judge is reported by nobody")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void aCodeIsJudgedAgainstWhatTheTenantHolds() throws Exception {
        // On a tenant holding its whole version as records, because that is
        // what makes a binding answerable here at all: the value set's rules
        // and the concepts under it are rows in this same database. A tenant
        // not on a face holds the terminology packages and not the core's
        // own value sets, and for those its answer is that it cannot say.
        assertTrue(rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/a","name":"A",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient"}""")
                        .stream().noneMatch(issue -> issue.startsWith("binding")),
                "a status inside the binding was refused");

        List<String> bogus = rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/b","name":"B",
                 "status":"kehtetu","kind":"resource","abstract":false,"type":"Patient"}""");
        assertTrue(bogus.stream().anyMatch(issue -> issue.contains("kehtetu")
                        && issue.contains("StructureDefinition.status")),
                "a status outside the binding was accepted: " + bogus);

        // A code from a system this tenant does not hold: nobody here can
        // say, and saying nothing is the answer rather than a refusal.
        assertTrue(rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/c","name":"C",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient",
                 "jurisdiction":[{"coding":[{"system":"https://ee.ee/oma-maa","code":"EE"}]}]}""")
                        .stream().noneMatch(issue -> issue.startsWith("binding")),
                "a code from a system this tenant does not hold was refused as invalid");
    }

    @Test
    @DisplayName("what an element must equal and what it must contain are answered from the "
            + "rows the profile was expanded into")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void whatAnElementMustHoldIsAnswered() throws Exception {
        assertTrue(issuesAgainst(PINNED, """
                {"resourceType":"Patient","identifier":[{"system":"https://ee.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":
                   "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}],
                   "text":"Abielus"}}""").stream()
                        .noneMatch(issue -> issue.startsWith("fixed")
                                || issue.startsWith("pattern")),
                "a patient holding exactly what the profile pins was refused");

        List<String> wrong = issuesAgainst(PINNED, """
                {"resourceType":"Patient","identifier":[{"system":"https://vale.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":
                   "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"U"}]}}""");
        assertTrue(wrong.stream().anyMatch(issue -> issue.contains("fixed to")
                        && issue.contains("Patient.identifier.system")),
                "an identifier under the wrong system was accepted: " + wrong);
        assertTrue(wrong.stream().anyMatch(issue -> issue.contains("must contain")),
                "a marital status the profile does not state was accepted: " + wrong);

        // A pattern is containment, not equality: the document above carries
        // a text beside the coding the profile pins, and that is allowed.
        assertFalse(issuesAgainst(PINNED, """
                {"resourceType":"Patient","maritalStatus":{"coding":[{"system":
                   "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}],
                   "text":"Abielus"}}""").stream()
                        .anyMatch(issue -> issue.contains("must contain")),
                "a pattern was read as equality, so carrying more than it states was refused");
    }

    @Test
    @DisplayName("a write is shown to both, the toolchain's verdict is the one used, and what "
            + "they made of it is counted")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void bothAreAskedAndOnlyOneAnswers() throws Exception {
        String before = tally();

        // A patient the profile is happy with. Accepted, as it would be
        // without any of this.
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"Patient","meta":{"profile":["%s"]},
                 "name":[{"family":"Tamm","given":["Mari"]}]}""".formatted(PROFILE));

        // And one the profile refuses: two names where it allows one. The
        // refusal is the toolchain's, and it still arrives.
        cloud.jengu.dbo.fhir.common.ValidationFailedException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        cloud.jengu.dbo.fhir.common.ValidationFailedException.class,
                        () -> manager.runtime(CLINIC).orElseThrow().store().create("""
                                {"resourceType":"Patient","meta":{"profile":["%s"]},
                                 "name":[{"family":"Tamm"},{"family":"Kask"}]}"""
                                .formatted(PROFILE)));
        assertTrue(refused.getMessage().contains("name"),
                "the refusal did not come from the toolchain as it always did: "
                        + refused.getMessage());

        assertNotEquals(before, tally(), "neither write was shown to the database");
        assertTrue(compared() >= 2, "fewer writes were compared than were made: " + tally());
    }

    @Test
    @DisplayName("a document the database cannot judge is counted as that, and never as the "
            + "database finding nothing")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void whatCannotBeComparedIsCountedAsThat() throws Exception {
        long unheldBefore = countOf("notHeld");

        // This tenant holds its own two profiles expanded and nothing else —
        // it is not on a face — so a patient claiming neither of them has no
        // rows to be judged against. Saying "the database found nothing"
        // about that would read as agreement and mean silence.
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"Patient","name":[{"family":"Saar"}]}""");

        assertTrue(countOf("notHeld") > unheldBefore,
                "a write with nothing to compare against was counted as a comparison: "
                        + tally());
    }

    @Test
    @DisplayName("what the comparison costs a write, said rather than assumed")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void whatItCostsIsMeasured() throws Exception {
        // Both through the write path, so what differs between them is the
        // comparison and not the connection: a patient claiming the profile
        // is judged against every element of it, and one claiming nothing has
        // no rows to be judged against and costs a single existence query.
        int writes = 50;
        long unclaimed = timed(writes, """
                {"resourceType":"Patient","name":[{"family":"Mets","given":["Ants"]}]}""");
        long claimed = timed(writes, """
                {"resourceType":"Patient","meta":{"profile":["%s"]},
                 "name":[{"family":"Mets","given":["Ants"]}]}""".formatted(PROFILE));

        System.out.printf(
                "METRICS advisory-validation write=%dus claimed=%dus comparison=%dus over %d%n",
                unclaimed, claimed, claimed - unclaimed, writes);
        // A bound rather than a target: what this guards is the comparison
        // becoming the cost of the write. The number itself belongs in the
        // commit that changes it.
        assertTrue(claimed - unclaimed < 50_000,
                "the comparison added " + (claimed - unclaimed)
                        + "us to a write, which is no longer advisory");
    }

    /** Microseconds per write, warmed. */
    private long timed(int writes, String document) {
        for (int i = 0; i < 5; i++) {
            manager.runtime(CLINIC).orElseThrow().store().create(document);
        }
        long from = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            manager.runtime(CLINIC).orElseThrow().store().create(document);
        }
        return (System.nanoTime() - from) / writes / 1_000;
    }

    @Test
    @DisplayName("a tenant holding its version as records can be written a profile, because "
            + "its definitions say where they came from")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void aProfileCanBeWrittenToATenantHoldingItsVersionAsRecords() {
        // The toolchain will not believe a type exists unless some definition
        // it holds says its type is that AND came from a package named like
        // the specification's core. Held as records under a package called
        // "records", every one of these was refused — on exactly the tenants
        // whose design is to hold the specification as records.
        for (String type : List.of("Patient", "Observation", "Task")) {
            manager.runtime(ROOT).orElseThrow().store().create("""
                    {"resourceType":"StructureDefinition",
                     "url":"https://ee.ee/sd/oma-%s","name":"Oma%s","status":"active",
                     "kind":"resource","abstract":false,"type":"%s",
                     "baseDefinition":"http://hl7.org/fhir/StructureDefinition/%s",
                     "derivation":"constraint",
                     "differential":{"element":[{"id":"%s","path":"%s"}]}}"""
                    .formatted(type, type, type, type, type, type));
        }
    }

    @Test
    @DisplayName("a reference is resolved against the records beside the document, and one "
            + "this store cannot speak for is left alone")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void aReferenceIsResolvedAgainstTheRecords() throws Exception {
        String id = manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"Patient","name":[{"family":"Viide"}]}""").id();

        assertTrue(issuesAgainst(CLINIC, PINNED, """
                {"resourceType":"Patient",
                 "link":[{"other":{"reference":"Patient/%s"},"type":"seealso"}]}"""
                .formatted(id)).stream().noneMatch(issue -> issue.startsWith("reference")),
                "a reference to a record this store holds was refused");

        List<String> missing = issuesAgainst(CLINIC, PINNED, """
                {"resourceType":"Patient",
                 "link":[{"other":{"reference":"Patient/8f2b1a54-0000-4000-8000-000000000000"},
                          "type":"seealso"}]}""");
        assertTrue(missing.stream().anyMatch(issue -> issue.contains("Patient.link.other")
                        && issue.contains("not a record this store holds")),
                "a reference to nothing at all was accepted: " + missing);

        // What this store cannot speak for it does not judge: another
        // server's url, and a fragment naming something inside the document.
        assertTrue(issuesAgainst(CLINIC, PINNED, """
                {"resourceType":"Patient",
                 "link":[{"other":{"reference":"https://teine.ee/fhir/Patient/7"},
                          "type":"seealso"}]}""").stream()
                        .noneMatch(issue -> issue.startsWith("reference")),
                "a reference to another server was judged as if it were this one's");
        assertTrue(issuesAgainst(CLINIC, PINNED, """
                {"resourceType":"Patient",
                 "contained":[{"resourceType":"Patient","id":"sees"}],
                 "link":[{"other":{"reference":"#sees"},"type":"seealso"}]}""").stream()
                        .noneMatch(issue -> issue.startsWith("reference")),
                "a reference to something contained in the document was judged as a record");
    }

    @Test
    @DisplayName("a slice is told apart by a predicate compiled when the definition arrived, "
            + "so telling it apart costs a write nothing procedural")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void slicingIsCompiledRatherThanInterpreted() throws Exception {
        String bp = "http://hl7.org/fhir/StructureDefinition/bp";
        String observation = "http://hl7.org/fhir/StructureDefinition/Observation";

        // What the row holds is a filter, not a rule to be interpreted: the
        // discriminator was read once, when the definition arrived.
        List<String> systolic = rootQuery(
                "SELECT unnest(steps) FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Observation.component:SystolicBP'", bp);
        assertEquals(1, systolic.size(), "the systolic component is located by " + systolic);
        assertTrue(systolic.get(0).contains(" ? ("),
                "the slice is located without a predicate, so nothing tells it apart: "
                        + systolic);

        // And it does tell them apart at a write. The profile requires one
        // systolic component; a document whose components carry another code
        // has none, and only the predicate can know that.
        assertTrue(findings(bp, bloodPressure("8480-6")).stream()
                        .noneMatch(issue -> issue.contains("component:SystolicBP")),
                "a blood pressure with a systolic component was told it had none: "
                        + findings(bp, bloodPressure("8480-6")));
        assertTrue(findings(bp, bloodPressure("9999-9")).stream()
                        .anyMatch(issue -> issue.contains("Observation.component")),
                "a component under the wrong code counted as the slice the profile requires: "
                        + findings(bp, bloodPressure("9999-9")));

        // What it costs, against the same document under the definition the
        // profile narrows — which has the same elements and none of the
        // slices.
        int rounds = 30;
        long sliced = timedValidate(rounds, bp, bloodPressure("8480-6"));
        long plain = timedValidate(rounds, observation, bloodPressure("8480-6"));
        // Stated with what it is a cost OF: a profile carries its base's
        // elements and its slices' as well, so some of the difference is
        // simply more rows and some is the predicates on them.
        String slicedRows = rootQuery("SELECT count(*)::text FROM definitions.definition_element"
                + " WHERE canonical = ?", bp).get(0);
        String plainRows = rootQuery("SELECT count(*)::text FROM definitions.definition_element"
                + " WHERE canonical = ?", observation).get(0);
        String withPredicates = rootQuery("SELECT count(*)::text FROM definitions.definition_element"
                + " WHERE canonical = ? AND array_to_string(steps, '') LIKE '%?%'", bp).get(0);
        System.out.printf("METRICS slicing sliced=%dus/%srows unsliced=%dus/%srows "
                + "predicates=%s over %d rounds%n",
                sliced, slicedRows, plain, plainRows, withPredicates, rounds);
        assertTrue(sliced < plain * 5,
                "telling slices apart cost " + sliced + "us against " + plain
                        + "us without them, which is the procedural cost this avoided");
    }

    /** A blood pressure whose systolic component carries the given code. */
    private static String bloodPressure(String systolicCode) {
        return """
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":
                   "http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}],
                 "code":{"coding":[{"system":"http://loinc.org","code":"85354-9"}]},
                 "subject":{"reference":"Patient/8f2b1a54-0000-4000-8000-000000000000"},
                 "effectiveDateTime":"2026-09-11",
                 "component":[
                   {"code":{"coding":[{"system":"http://loinc.org","code":"%s"}]},
                    "valueQuantity":{"value":120,"unit":"mmHg","system":"http://unitsofmeasure.org",
                                     "code":"mm[Hg]"}},
                   {"code":{"coding":[{"system":"http://loinc.org","code":"8462-4"}]},
                    "valueQuantity":{"value":80,"unit":"mmHg","system":"http://unitsofmeasure.org",
                                     "code":"mm[Hg]"}}]}""".formatted(systolicCode);
    }

    private List<String> findings(String profile, String document) throws Exception {
        return issuesAgainst(ROOT, profile, document);
    }

    /**
     * Microseconds per validation, warmed, on ONE connection — what is being
     * compared is two profiles, and opening a connection costs more than
     * either of them.
     */
    private long timedValidate(int rounds, String profile, String document) throws Exception {
        try (Connection c = tenantSource(ROOT).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM dbo.validate(?::jsonb, ?)")) {
            ps.setString(1, document);
            ps.setString(2, profile);
            for (int i = 0; i < 3; i++) {
                ps.executeQuery().close();
            }
            long from = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                ps.executeQuery().close();
            }
            return (System.nanoTime() - from) / rounds / 1_000;
        }
    }

    @Test
    @DisplayName("a broken rule is reported by its own key, a warning refuses nothing, and one "
            + "that cannot be run is reported by nobody")
    @Proving(DboPromises.VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE)
    void aBrokenRuleIsReportedByItsKey() throws Exception {
        String shape = "http://hl7.org/fhir/StructureDefinition/StructureDefinition";

        // dom-2: a contained resource may not itself contain one. The rule
        // is R4's; what is new is that this store runs it.
        List<String> broken = rootIssuesWithKeys("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/x","name":"X",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient",
                 "contained":[{"resourceType":"Patient","id":"a",
                               "contained":[{"resourceType":"Patient","id":"b"}]}]}""");
        assertTrue(broken.stream().anyMatch(issue -> issue.startsWith("dom-2")),
                "a contained resource holding another was accepted: " + broken);

        assertTrue(rootIssuesWithKeys("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/y","name":"Y",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient"}""")
                        .stream().noneMatch(issue -> issue.startsWith("dom-2")),
                "a definition containing nothing was told it contained too much");

        // Severity is the rule's own, and only an error is a refusal.
        List<String> severities = rootQuery(
                "SELECT DISTINCT severity FROM dbo.validate(?::jsonb, ?) ORDER BY severity",
                """
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/z","name":"Z",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient"}""",
                shape);
        assertTrue(severities.contains("warning"),
                "no rule reported advice, so severity is not the rule's own: " + severities);
    }

    private List<String> rootIssuesWithKeys(String document) throws Exception {
        return rootQuery("SELECT key || ' ' || detail FROM dbo.validate(?::jsonb, ?)"
                + " ORDER BY key", document,
                "http://hl7.org/fhir/StructureDefinition/StructureDefinition");
    }

    private List<String> rootQuery(String sql, String... arguments) throws Exception {
        try (Connection c = tenantSource(ROOT).getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                ps.setString(i + 1, arguments[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
                return rows;
            }
        }
    }

    @Test
    @DisplayName("what each check costs a write, so the expensive one is known rather than "
            + "guessed at")
    @Proving(DboPromises.VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE)
    void whatEachCheckCosts() throws Exception {
        String shape = "http://hl7.org/fhir/StructureDefinition/StructureDefinition";
        String document = """
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/kulu","name":"Kulu",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.name","path":"Patient.name","min":1},
                   {"id":"Patient.gender","path":"Patient.gender","max":"1"}]}}""";

        // What there is to do: the rows walked, and the rules run over them.
        String elements = rootQuery("SELECT count(*)::text FROM definitions.definition_element"
                + " WHERE canonical = ?", shape).get(0);
        String rules = rootQuery("SELECT count(*)::text FROM definitions.definition_invariant"
                + " WHERE canonical = ? AND path IS NOT NULL", shape).get(0);

        int rounds = 20;
        StringBuilder shares = new StringBuilder();
        long whole = 0;
        for (String check : List.of("cardinality_issues", "value_issues", "binding_issues",
                "reference_issues", "invariant_issues", "validate")) {
            long each = timedCheck(rounds, check, document, shape);
            if ("validate".equals(check)) {
                whole = each;
            } else {
                shares.append(' ').append(check.replace("_issues", "")).append('=')
                        .append(each).append("us");
            }
        }
        System.out.printf("METRICS tier-one elements=%s rules=%s%s whole=%dus over %d rounds%n",
                elements, rules, shares, whole, rounds);

        // A bound rather than a target: what this guards is a check quietly
        // becoming the cost of the write. The numbers belong in the commit
        // that moves them.
        assertTrue(whole < 200_000,
                "tier one took " + whole + "us over " + elements + " elements and "
                        + rules + " rules, which is no longer something to run beside a write");
    }

    /** Microseconds per call of one check, warmed, on one connection. */
    private long timedCheck(int rounds, String check, String document, String profile)
            throws Exception {
        try (Connection c = tenantSource(ROOT).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM dbo." + check + "(?::jsonb, ?)")) {
            ps.setString(1, document);
            ps.setString(2, profile);
            for (int i = 0; i < 3; i++) {
                ps.executeQuery().close();
            }
            long from = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                ps.executeQuery().close();
            }
            return (System.nanoTime() - from) / rounds / 1_000;
        }
    }

    // ------------------------------------------------------------- reading

    private String tally() {
        return ((cloud.jengu.dbo.fhir.element.ElementStore)
                manager.runtime(CLINIC).orElseThrow().store()).advisoryTally();
    }

    private long compared() {
        return countOf("agreed") + countOf("onlyTheToolchain") + countOf("onlyTheDatabase");
    }

    private long countOf(String name) {
        for (String part : tally().split(" ")) {
            if (part.startsWith(name + "=")) {
                return Long.parseLong(part.substring(name.length() + 1));
            }
        }
        throw new IllegalStateException(name + " is not in " + tally());
    }


    private List<String> issues(String document) throws Exception {
        return issuesAgainst(CLINIC, PROFILE, document);
    }

    private List<String> rootIssues(String document) throws Exception {
        return issuesAgainst(ROOT, SHAPE, document);
    }

    private List<String> issuesAgainst(String profile, String document) throws Exception {
        return issuesAgainst(CLINIC, profile, document);
    }

    private List<String> issuesAgainst(String tenant, String profile, String document)
            throws Exception {
        try (Connection c = tenantSource(tenant).getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key || ' | ' || detail FROM dbo.validate(?::jsonb, ?)"
                     + " WHERE severity = 'error' ORDER BY path, key")) {
            ps.setString(1, document);
            ps.setString(2, profile);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
                return found;
            }
        }
    }

    private List<String> query(String sql) throws Exception {
        try (Connection c = tenantConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            return rows;
        }
    }

    private Connection tenantConnection() throws Exception {
        return tenantSource().getConnection();
    }

    private PGSimpleDataSource tenantSource() {
        return tenantSource(CLINIC);
    }

    private PGSimpleDataSource tenantSource(String tenant) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
