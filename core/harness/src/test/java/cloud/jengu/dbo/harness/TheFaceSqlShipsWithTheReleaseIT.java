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
            provisioner.close();
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
        assertEquals(List.of("binding_issues", "cardinality_issues", "coded_values",
                        "descends_from", "in_value_set", "instances", "located",
                        "validate", "value_issues"), query(
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
    @DisplayName("an element is counted inside the parent it occurs in, which is the count "
            + "the profile actually states")
    @Proving(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE)
    void anElementIsCountedInsideItsParent() throws Exception {
        // Three contacts, one name each. Flattened from the root that is
        // three names where one is allowed, and the document is correct.
        assertEquals(List.of(), issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}],
                 "contact":[{"name":{"family":"A"}},{"name":{"family":"B"}},
                            {"name":{"family":"C"}}]}"""),
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
        assertEquals(List.of(), issues("""
                {"resourceType":"Patient","name":[{"family":"Tamm"}]}"""),
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
        assertEquals(List.of(), rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/a","name":"A",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient"}"""),
                "a status inside the binding was refused");

        List<String> bogus = rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/b","name":"B",
                 "status":"kehtetu","kind":"resource","abstract":false,"type":"Patient"}""");
        assertTrue(bogus.stream().anyMatch(issue -> issue.contains("kehtetu")
                        && issue.contains("StructureDefinition.status")),
                "a status outside the binding was accepted: " + bogus);

        // A code from a system this tenant does not hold: nobody here can
        // say, and saying nothing is the answer rather than a refusal.
        assertEquals(List.of(), rootIssues("""
                {"resourceType":"StructureDefinition","url":"https://ee.ee/sd/c","name":"C",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient",
                 "jurisdiction":[{"coding":[{"system":"https://ee.ee/oma-maa","code":"EE"}]}]}"""),
                "a code from a system this tenant does not hold was refused as invalid");
    }

    @Test
    @DisplayName("what an element must equal and what it must contain are answered from the "
            + "rows the profile was expanded into")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void whatAnElementMustHoldIsAnswered() throws Exception {
        assertEquals(List.of(), issuesAgainst(PINNED, """
                {"resourceType":"Patient","identifier":[{"system":"https://ee.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":
                   "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}],
                   "text":"Abielus"}}"""),
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

    // ------------------------------------------------------------- reading

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
                     "SELECT detail FROM dbo.validate(?::jsonb, ?) ORDER BY path, key")) {
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
