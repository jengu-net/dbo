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
        UntilServed.scan(manager, CLINIC);

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
        assertEquals(List.of("cardinality_issues", "located"), query(
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

    // ------------------------------------------------------------- reading

    private List<String> issues(String document) throws Exception {
        try (Connection c = tenantConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT detail FROM dbo.cardinality_issues(?::jsonb, ?) ORDER BY path")) {
            ps.setString(1, document);
            ps.setString(2, PROFILE);
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
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + CLINIC.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
