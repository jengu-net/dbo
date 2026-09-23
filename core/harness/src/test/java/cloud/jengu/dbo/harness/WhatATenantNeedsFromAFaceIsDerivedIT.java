package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.index.DefinitionRows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tenant needs from a face is derivable from what it declared, and is
 * computed where the definitions are.
 *
 * <p>A tenant's spec says which types it operates on and, separately, which
 * types it takes from an upstream. The second follows from the first: what a
 * tenant needs from a face is the closure of what it declared, so the
 * definitional dependency is derived rather than written — and a declaration
 * derived from another cannot disagree with it.
 *
 * <p><b>A filter is not a predicate that travels.</b> It is a set of names,
 * computed once. The dependent says which types it operates on; this
 * upstream answers with the canonicals; the upstream then selects by name and
 * nothing is executed on anybody's behalf. It has to be computed here because
 * a tenant cannot compute the closure of definitions it does not hold, which
 * is the same reason it is derivable at all.
 *
 * <p><b>And it closes over grains.</b> Terminology's grain is a code system
 * together with the value sets that draw on it, and that promise is already
 * proven. A manifest naming half a grain would produce a stream that breaks
 * on arrival, so the grain is closed where the manifest is computed rather
 * than discovered where it lands.
 *
 * <p>What this does NOT do is send it: the sync path still streams by type.
 * What is proven here is the derivation and its grain, which is the piece
 * that had no way to exist before the closure could be computed from rows.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatATenantNeedsFromAFaceIsDerivedIT {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /** A dependent of a realistic shape: the types a small clinic operates on. */
    private static final List<String> DECLARED = List.of(
            "Patient", "Observation", "Organization", "Practitioner");

    static SharedTenants.Tenant face;

    @BeforeAll
    void up() {
        // The upstream, because it is the one that holds the version. That is
        // the point rather than a convenience: the dependent cannot compute
        // this, which is why the upstream does.
        face = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
    }

    @Test
    @DisplayName("the manifest a dependent needs is computed from the types it declared, and is "
            + "a fraction of what the face holds")
    void theManifestIsDerivedFromTheDeclaration() {
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : DECLARED) {
            seeds.add(PREFIX + type);
        }
        DefinitionRows.Manifest manifest = DefinitionRows.manifestFor(source(), seeds);

        int structuresHeld = scalar(
                "SELECT count(DISTINCT canonical) FROM definitions.definition_element");
        int valueSetsHeld = scalar("SELECT count(*) FROM definitions.term_valueset");
        int systemsHeld = scalar("SELECT count(*) FROM definitions.term_system");

        System.out.printf("%n=== what %d declared types need from %s ===%n"
                + "structures   %5d of %5d held%n"
                + "value sets   %5d of %5d held%n"
                + "code systems %5d of %5d held%n"
                + "the manifest is %d names against %d the face holds%n",
                DECLARED.size(), face.code(),
                manifest.structures().size(), structuresHeld,
                manifest.valueSets().size(), valueSetsHeld,
                manifest.codeSystems().size(), systemsHeld,
                manifest.size(), structuresHeld + valueSetsHeld + systemsHeld);

        // A derivation that named everything would be no derivation.
        assertTrue(manifest.structures().size() * 4 < structuresHeld,
                "the derived manifest narrowed nothing: " + manifest.structures().size()
                        + " of " + structuresHeld);
        // And one that named nothing would be a dependent that gets no face.
        assertTrue(manifest.structures().contains(PREFIX + "Patient"),
                "a declared type is not in its own manifest");
        assertTrue(!manifest.valueSets().isEmpty(),
                "a manifest with no terminology cannot answer a required binding");
    }

    @Test
    @DisplayName("the manifest names whole grains: every value set in it brings the systems it "
            + "is built from, and every system brings the value sets that draw on it")
    void theManifestClosesOverGrains() {
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : DECLARED) {
            seeds.add(PREFIX + type);
        }
        DefinitionRows.Manifest manifest = DefinitionRows.manifestFor(source(), seeds);

        // Half a grain is what this must not be able to name. Asked of the
        // rows rather than of the code that built it: every system any named
        // value set is built from is named, and every value set drawing on any
        // named system is named.
        Set<String> systemsBehind = new TreeSet<>(query("""
                SELECT DISTINCT part ->> 'system'
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE vs.url = ANY(?) AND part ->> 'system' IS NOT NULL""",
                manifest.valueSets()));
        systemsBehind.removeAll(manifest.codeSystems());
        assertEquals(Set.of(), systemsBehind,
                "a value set in the manifest is built from a system the manifest does not name, "
                        + "which is half a grain and a stream that breaks on arrival");

        Set<String> setsDrawing = new TreeSet<>(query("""
                SELECT DISTINCT vs.url
                  FROM definitions.term_valueset vs
                 CROSS JOIN LATERAL jsonb_array_elements(
                        coalesce(vs.compose -> 'includes', '[]'::jsonb)) AS part
                 WHERE part ->> 'system' = ANY(?)""", manifest.codeSystems()));
        setsDrawing.removeAll(manifest.valueSets());
        assertEquals(Set.of(), setsDrawing,
                "a system in the manifest is drawn on by a value set the manifest does not name");
    }

    private static Set<String> query(String sql, Set<String> over) {
        Set<String> out = new TreeSet<>();
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", over.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1) != null) {
                        out.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the grain failed", e);
        }
        return out;
    }

    private static int scalar(String sql) {
        try (Connection c = source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("counting what the face holds failed", e);
        }
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(face.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
