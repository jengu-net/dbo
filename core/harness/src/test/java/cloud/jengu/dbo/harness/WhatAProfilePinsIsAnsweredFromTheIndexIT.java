package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.fhir.validate.ElementChecks;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a profile pins, answered from the index, and held to what the database
 * answers from the same rows.
 *
 * <p>Cardinality was the check that could be compared over base definitions,
 * because a version states minima and maxima everywhere. It states almost no
 * fixed or pattern values at all — there are zero in the closure the form was
 * measured over — so a checker proven only against what HL7 publishes would
 * never have run this code. Where fixed and pattern actually live is a
 * tenant's OWN profiles, and those arrive as rows, which is why this waited
 * for the index to be read from rows rather than packages.
 *
 * <p><b>The tenant is the one that authors profiles</b>, and the profiles are
 * the ones the database's own side of this is already proven against — a
 * pinned identifier system and a pinned marital status, in
 * {@code TheFaceSqlShipsWithTheReleaseIT}. Two answerers over one fixture is
 * the comparison; two fixtures would be two specifications.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatAProfilePinsIsAnsweredFromTheIndexIT {

    private static final String PINNED = "https://ee.ee/StructureDefinition/IndeksIkPatsient";
    private static final String SYSTEM = "https://ee.ee/ik";
    private static final String MARITAL =
            "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus";

    static SharedTenants.Tenant tenant;
    static DefinitionIndex index;

    @BeforeAll
    void up() throws Exception {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_PROFILED);
        // A profile of this class's own, under a canonical of its own: the
        // shape is shared and a second class writing to the same canonical
        // would be two classes editing one record.
        tenant.store().create("""
                {"resourceType":"StructureDefinition",
                 "url":"%s","name":"IndeksIkPatsient","status":"active","kind":"resource",
                 "abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Patient.identifier.system","path":"Patient.identifier.system",
                    "fixedUri":"%s"},
                   {"id":"Patient.maritalStatus","path":"Patient.maritalStatus",
                    "patternCodeableConcept":{"coding":[{"system":"%s","code":"M"}]}}]}}"""
                .formatted(PINNED, SYSTEM, MARITAL));
        tenant.store().shapesChanged();

        Set<String> seeds = new LinkedHashSet<>(Set.of(PINNED));
        index = DefinitionRows.over(source(), DefinitionRows.closureOf(source(), seeds));
    }

    @Test
    @DisplayName("the index carries what a profile pins, which a version's own definitions "
            + "almost never state")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theIndexCarriesWhatTheProfilePinned() {
        assertTrue(index.holds(PINNED), "the profile's own rows are not in the index");

        String fixed = null;
        String pattern = null;
        for (int element : index.elementsOf(PINNED)) {
            if ("Patient.identifier.system".equals(index.pathOf(element))) {
                fixed = index.fixedOf(element);
            }
            if ("Patient.maritalStatus".equals(index.pathOf(element))) {
                pattern = index.patternOf(element);
            }
        }
        assertTrue(fixed != null && fixed.contains(SYSTEM),
                "the fixed value did not reach the index: " + fixed);
        assertTrue(pattern != null && pattern.contains(MARITAL),
                "the pattern did not reach the index: " + pattern);

        // And the base definitions it was measured over state none, which is
        // why this could not have been proven one step earlier.
        int pinnedInTheBase = 0;
        for (String canonical : index.held()) {
            if (canonical.equals(PINNED)) {
                continue;
            }
            for (int element : index.elementsOf(canonical)) {
                if (index.fixedOf(element) != null || index.patternOf(element) != null) {
                    pinnedInTheBase++;
                }
            }
        }
        System.out.printf("%n=== what is pinned, over %s's closure of one profile ===%n"
                + "the profile pins 2; the %d base structures under it pin %d%n",
                tenant.code(), index.structures() - 1, pinnedInTheBase);
    }

    @Test
    @DisplayName("on what an element must equal and must contain, the index and the database "
            + "name the same elements")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void bothAnswerWhatIsPinned() {
        // Exactly what the profile pins, carrying more beside it. A pattern is
        // containment, so the text beside the coding is allowed, and neither
        // answerer may speak.
        bothSay("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://ee.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":"%s","code":"M"}],"text":"Abielus"}}"""
                .formatted(MARITAL), Set.of());

        // The wrong system, which is equality and is refused.
        bothSay("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://vale.ee/ik","value":"1"}]}""",
                Set.of("Patient.identifier.system"));

        // A marital status the profile does not state.
        bothSay("""
                {"resourceType":"Patient",
                 "maritalStatus":{"coding":[{"system":"%s","code":"U"}]}}"""
                .formatted(MARITAL), Set.of("Patient.maritalStatus"));

        // Both at once, in one document, because a walk that stopped at the
        // first finding would pass the two above and fail nobody.
        bothSay("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"https://vale.ee/ik","value":"1"}],
                 "maritalStatus":{"coding":[{"system":"%s","code":"U"}]}}"""
                .formatted(MARITAL),
                Set.of("Patient.identifier.system", "Patient.maritalStatus"));

        // AND PER OCCURRENCE. Two identifiers, one right and one wrong: the
        // pinned value is checked where it occurs, not once for the element.
        bothSay("""
                {"resourceType":"Patient","identifier":[
                   {"system":"https://ee.ee/ik","value":"1"},
                   {"system":"https://vale.ee/ik","value":"2"}]}""",
                Set.of("Patient.identifier.system"));
    }

    /**
     * Both answerers, over one document, against what should be said.
     *
     * <p>The expectation is stated as well as the agreement: two answerers
     * can agree by both being silent, and three of these five documents are
     * ones where silence would be wrong.
     */
    private void bothSay(String document, Set<String> expected) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Set<String> ours = new TreeSet<>();
        for (Finding one : ElementChecks.over(index, PINNED, bytes).findings()) {
            if ("fixed".equals(one.key()) || "pattern".equals(one.key())) {
                ours.add(one.path().replaceAll("\\[\\d+\\]", ""));
            }
        }
        assertEquals(new TreeSet<>(expected), ours, "the index checker: " + document);
        assertEquals(new TreeSet<>(expected), valuePaths(bytes), "the database: " + document);
    }

    /** What dbo.value_issues faults, as the definition paths it names. */
    private static Set<String> valuePaths(byte[] document) {
        Set<String> paths = new TreeSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path FROM dbo.value_issues(?::jsonb, ?)")) {
            ps.setString(1, new String(document, StandardCharsets.UTF_8));
            ps.setString(2, PINNED);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("asking the database about pinned values failed", e);
        }
        return paths;
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
