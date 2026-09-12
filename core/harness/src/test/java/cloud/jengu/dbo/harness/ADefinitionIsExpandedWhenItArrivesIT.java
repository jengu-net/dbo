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
            provisioner.close();
        }
    }

    @Test
    @DisplayName("the version arrives expanded: an element per row, located by a jsonpath, "
            + "and a second bring-up expands nothing")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void theVersionArrivesExpanded() throws Exception {
        assertTrue(elements() > 10_000,
                "a version of seven hundred structures came to " + elements() + " elements");

        List<String> steps = query(
                "SELECT unnest(steps) FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.contact.name'", PATIENT);
        assertEquals(List.of("$.\"name\"[*]"), steps,
                "a contact's name is not located inside a contact");

        List<String> parent = query(
                "SELECT parent_id FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.contact.name'", PATIENT);
        assertEquals(List.of("Patient.contact"), parent,
                "a contact's name is looked for in the document rather than in its contact");

        List<String> gender = query(
                "SELECT binding_strength || ' ' || binding_valueset"
                + " FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.gender'", PATIENT);
        assertEquals(1, gender.size(), "the gender element is not held");
        assertTrue(gender.get(0).startsWith("required ")
                        && gender.get(0).contains("administrative-gender"),
                "what a coded element is bound to did not come with it: " + gender);

        // Bringing up again reads. The definitions are where they were, so
        // nothing is taken apart a second time — which is the difference
        // between a cost paid once on arrival and one paid at every boot.
        long before = elements();
        manager.scanOnce();
        assertEquals(before, elements(), "a second bring-up expanded the version again");
    }

    @Test
    @DisplayName("an element nothing can locate is held as unenforceable, never as absent")
    @Proving(DboPromises.VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME)
    void whatCannotBeLocatedSaysSo() throws Exception {
        // R4's lipid profile slices its results by resolving each reference
        // and reading the code of what it points at. That is a join, and no
        // path reaches it — so the row is held saying exactly that, rather
        // than being dropped into a checker that would then pass anything.
        List<String> unenforceable = query(
                "SELECT element_id || ' | ' || unenforceable FROM definitions.definition_element"
                + " WHERE canonical = ? AND unenforceable IS NOT NULL ORDER BY ordinal",
                "http://hl7.org/fhir/StructureDefinition/lipidprofile");
        assertFalse(unenforceable.isEmpty(),
                "the slices that follow a reference were dropped rather than held as "
                        + "unenforceable, so nothing knows they are not being checked");
        assertTrue(unenforceable.stream().allMatch(row -> row.contains("follows a reference")),
                "something other than a followed reference cannot be located: " + unenforceable);
        assertTrue(query("SELECT element_id FROM definitions.definition_element"
                        + " WHERE canonical = ? AND unenforceable IS NOT NULL"
                        + " AND cardinality(steps) > 0",
                "http://hl7.org/fhir/StructureDefinition/lipidprofile").isEmpty(),
                "an element that cannot be located is located anyway");

        // And it is a small, stated part of the whole rather than a habit.
        assertTrue(unlocatable() < 20,
                "the version holds " + unlocatable() + " elements nothing can check");
    }

    @Test
    @DisplayName("a profile that states only what it changes is expanded whole, with what it "
            + "inherits and not only what it mentions")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void aProfileThatStatesOnlyItsChangesIsExpandedWhole() throws Exception {
        String canonical = "https://ee.ee/StructureDefinition/nimeline-patsient";
        manager.runtime(CLINIC).orElseThrow().store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"NimelinePatsient","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.name","path":"Patient.name","min":1}]}}"""
                .formatted(canonical));
        manager.runtime(CLINIC).orElseThrow().store().shapesChanged();

        // What it says: a name is now required.
        assertEquals(List.of("1"), queryOf(CLINIC,
                "SELECT min_occurs::text FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.name'", canonical),
                "the profile's own change is not held");

        // What it inherits and never mentions: the rest of Patient, located
        // and bound exactly as the base states it. A differential names four
        // lines; a checker reading only those would enforce four lines and
        // pass everything else in silence.
        List<String> birthDate = queryOf(CLINIC, 
                "SELECT array_to_string(steps, '|') FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.birthDate'", canonical);
        assertEquals(List.of("$.\"birthDate\"[*]"), birthDate,
                "an element the profile never mentions was not inherited");
        // Everything, in fact: the profile's elements are the version's own
        // Patient elements, because that is what deriving from it means. The
        // root holds that version expanded, so the two sets are comparable
        // and this says "whole" without a number nobody can check.
        assertEquals(
                query("SELECT element_id FROM definitions.definition_element"
                        + " WHERE canonical = ? ORDER BY element_id", PATIENT),
                queryOf(CLINIC, "SELECT element_id FROM definitions.definition_element"
                        + " WHERE canonical = ? ORDER BY element_id", canonical),
                "a profile derived from Patient does not hold Patient's elements");

        List<String> gender = queryOf(CLINIC, 
                "SELECT binding_strength FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Patient.gender'", canonical);
        assertEquals(List.of("required"), gender,
                "an inherited binding did not come with the element that carries it");
    }

    @Test
    @DisplayName("the version's own differential profiles are expanded too, from the same "
            + "snapshot their face makes for them")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void theVersionsOwnDifferentialsAreExpandedToo() throws Exception {
        // R4 publishes two profiles with no snapshot of their own, both
        // constraining Composition. They are the carried case of exactly what
        // a tenant authors, and until the face snapshotted them they were
        // counted and skipped.
        String canonical = "http://hl7.org/fhir/StructureDefinition/example-composition";
        assertTrue(Long.parseLong(query("SELECT count(*)::text FROM definitions.definition_element"
                        + " WHERE canonical = ?", canonical).get(0)) > 20,
                "a carried differential profile is still not expanded");
        assertEquals(List.of("$.\"status\"[*]"), query(
                "SELECT array_to_string(steps, '|') FROM definitions.definition_element"
                + " WHERE canonical = ? AND element_id = 'Composition.status'", canonical),
                "an element it inherits from Composition is not held");
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

    @Test
    @DisplayName("the rules a definition carries are held as rows, compiled where they can be "
            + "and named where they cannot")
    @Proving({DboPromises.VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES,
            DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME})
    void theRulesAreHeldAsRows() throws Exception {
        List<String> patient = query(
                "SELECT key || ' ' || severity || ' ' || coalesce(path, '-')"
                + " FROM definitions.definition_invariant WHERE canonical = ? AND element_id = 'Patient'"
                + " ORDER BY key", PATIENT);
        assertTrue(patient.size() > 4, "Patient's own rules are not held: " + patient);
        assertTrue(patient.stream().anyMatch(rule -> rule.startsWith("dom-2 error !exists(")),
                "the rule about contained resources did not compile: " + patient);
        assertTrue(patient.stream().anyMatch(rule -> rule.contains("warning")),
                "a warning-severity rule was dropped: " + patient);

        // Held whole: the version's rules are thousands, and what cannot be
        // compiled is a row saying why rather than an absence.
        long all = Long.parseLong(query(
                "SELECT count(*)::text FROM definitions.definition_invariant").get(0));
        long named = Long.parseLong(query("SELECT count(*)::text FROM definitions.definition_invariant"
                + " WHERE unenforceable IS NOT NULL").get(0));
        long compiled = Long.parseLong(query("SELECT count(*)::text FROM definitions.definition_invariant"
                + " WHERE path IS NOT NULL").get(0));
        assertTrue(all > 1000, "the version carries more rules than " + all);
        assertEquals(all, named + compiled,
                "a rule is neither compiled nor named, which is the silence this refuses");
        assertTrue(query("SELECT key FROM definitions.definition_invariant"
                + " WHERE unenforceable IS NOT NULL AND path IS NOT NULL").isEmpty(),
                "a rule that could not be compiled carries a path anyway");
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

    private Connection tenantConnection(String tenant) throws Exception {
        String url = SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + tenant.replace('-', '_'));
        return java.sql.DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
    }
}
