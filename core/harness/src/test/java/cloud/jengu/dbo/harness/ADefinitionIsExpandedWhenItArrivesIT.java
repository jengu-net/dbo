package cloud.jengu.dbo.harness;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The definitions a tenant holds are taken apart when they arrive, into rows
 * the database can check against.
 *
 * <p>The subject is a face root, because it is the tenant that holds a whole
 * version and therefore the one where expanding per boot or per write would
 * cost the most. What is asserted is that the rows are there, that they say
 * what the definition says, and that a second bring-up writes nothing —
 * bringing up reads.
 *
 * <p><b>A world of its own, and the second bring-up is why.</b> The claim is
 * that expansion is paid on arrival and not again, which it proves by
 * counting the rows, bringing the tenant up a second time, and counting
 * again. Both boots have to be this class's: on a shared runtime the second
 * one brings up nothing, so the count would be unchanged for a reason that
 * has nothing to do with the claim.
 *
 * <p>What stays is the two legs that delete rows behind the store and ask it
 * to rebuild them — tampering, which no shared world may be put through. The
 * rest is walked in Rowling Land, in the standard-moves story.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADefinitionIsExpandedWhenItArrivesIT {

    private static final String ROOT = "vaartus-r4";
    /** An ordinary tenant, which is where a profile of one's own actually lives. */
    private static final String CLINIC = "vaartus-kliinik";
    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-expanded");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADefinitionIsExpandedWhenItArrivesIT"),
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
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC));
        UntilServed.scan(manager, ROOT);
        UntilServed.scan(manager, CLINIC);
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
    @DisplayName("and what is kept is decided by the definitions, so a second bring-up of the "
            + "same face keeps the same set")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void whatIsKeptFollowsTheDefinitionsRatherThanTheRoute() throws Exception {
        String canonical = "https://ee.ee/StructureDefinition/teist-korda";
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"TeistKorda","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.gender","path":"Patient.gender","min":1}]}}"""
                .formatted(canonical));
        manager.runtime(CLINIC).orElseThrow().store().shapesChanged();

        List<String> afterTheFirst = queryOf(CLINIC,
                "SELECT canonical FROM definitions.definition_snapshot ORDER BY canonical");
        assertTrue(afterTheFirst.contains(canonical),
                "the profile just written has no kept snapshot: " + afterTheFirst);

        // Forget it, and ask again. The rows stay, which is what a tenant
        // that loaded an image is holding: everything derived except this.
        // Nothing here re-expands, and until the keeping followed the
        // definitions rather than the expansion, nothing would have made it
        // again either — which is how two tenants of one face came to
        // disagree about a table derived from definitions they agree on.
        execOn(CLINIC, "DELETE FROM definitions.definition_snapshot WHERE canonical = ?",
                canonical);
        assertEquals(List.of(), queryOf(CLINIC,
                "SELECT canonical FROM definitions.definition_snapshot WHERE canonical = ?",
                canonical), "the delete did not take");

        manager.runtime(CLINIC).orElseThrow().store().shapesChanged();

        assertEquals(afterTheFirst, queryOf(CLINIC,
                "SELECT canonical FROM definitions.definition_snapshot ORDER BY canonical"),
                "a bring-up that found the rows already expanded did not keep the snapshot "
                        + "that goes with them, so what is kept depends on which route a "
                        + "tenant took to the same face");
    }

    @Test
    @DisplayName("the rows are rebuilt from the records, so they are a projection and not "
            + "a second copy of the truth")
    @Proving(DboPromises.CORE_PAYLOAD_IS_TRUTH)
    void theRowsAreRebuiltFromTheRecords() throws Exception {
        List<String> before = query(
                "SELECT element_id || ' ' || array_to_string(steps, '|') || ' ' || min_occurs"
                + " FROM definitions.definition_element WHERE canonical = ? ORDER BY ordinal", PATIENT);
        assertFalse(before.isEmpty(), "the Patient definition is not expanded at all");

        try (Connection c = tenantConnection(ROOT);
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM definitions.definition_element WHERE canonical = ?")) {
            ps.setString(1, PATIENT);
            ps.executeUpdate();
        }
        assertTrue(query("SELECT element_id FROM definitions.definition_element WHERE canonical = ?",
                PATIENT).isEmpty(), "the rows did not go away");

        // The record never moved; only the projection did. Asking the tenant
        // to take its shapes apart again rebuilds it from the record, which
        // is what makes these rows safe to drop and re-derive.
        manager.runtime(ROOT).orElseThrow().store().shapesChanged();

        assertEquals(before, query(
                "SELECT element_id || ' ' || array_to_string(steps, '|') || ' ' || min_occurs"
                + " FROM definitions.definition_element WHERE canonical = ? ORDER BY ordinal", PATIENT),
                "the rebuilt expansion is not the one that was there");
    }

    // ------------------------------------------------------------- reading

    private long elements() throws Exception {
        return Long.parseLong(query("SELECT count(*) FROM definitions.definition_element").get(0));
    }

    private long unlocatable() throws Exception {
        return Long.parseLong(query(
                "SELECT count(*) FROM definitions.definition_element WHERE unenforceable IS NOT NULL")
                .get(0));
    }

    private List<String> query(String sql, String... arguments) throws Exception {
        return queryOf(ROOT, sql, arguments);
    }

    private List<String> queryOf(String tenant, String sql, String... arguments) throws Exception {
        try (Connection c = tenantConnection(tenant);
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

    /** Reaches behind the store on purpose: what a route leaves out, made absent. */
    private void execOn(String tenant, String sql, String... arguments) throws Exception {
        try (Connection c = tenantConnection(tenant);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                ps.setString(i + 1, arguments[i]);
            }
            ps.executeUpdate();
        }
    }

    private Connection tenantConnection(String tenant) throws Exception {
        String url = SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_'));
        return java.sql.DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
    }
}
