package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index is a projection of the rows, and the rows say what the packages
 * say.
 *
 * <p>The spike that measured this form built it by scanning the carried
 * packages, which was right for a measurement and wrong for anything else:
 * a package cannot supply a tenant's own profiles, cannot supply what a face
 * image carried, and cannot be narrowed to what a tenant declared. The rows
 * can, because they are what arrived rather than what HL7 published. So the
 * index is built from {@code definitions.definition_element} — and the thing
 * that has to be proven about moving the source is that the answer did not
 * move with it.
 *
 * <p><b>Both directions, because one of them alone proves nothing.</b> That
 * the two agree where they overlap is worth nothing if the overlap is four
 * structures, so the overlap is counted and asserted to be most of the
 * closure. And an index that held no elements would agree with anything.
 *
 * <p><b>Shared, and this is not a bring-up.</b> The subject is a face root
 * because it is the tenant that holds a whole version's definitions as rows;
 * what is asked is what those rows say, not that the tenant comes up.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnIndexBuiltFromTheRowsSaysWhatThePackagesSayIT {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /** What {@link SharedTenants.Shape#R4_FACE_ROOT} declares it operates on. */
    private static final Set<String> DECLARED = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Patient", "Observation"));

    private static final ObjectMapper JSON = new ObjectMapper();

    static SharedTenants.Tenant tenant;

    @BeforeAll
    void up() throws Exception {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
    }

    @Test
    @DisplayName("the closure of what a tenant declared is walked over the rows, and is a "
            + "fraction of what the version holds")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theClosureIsWalkedOverTheRows() {
        Set<String> closure = DefinitionRows.closureOf(source(), seeds());

        // Every seed reaches itself, and the kernel a resource is made of is
        // reached by all of them. A closure that had only the seeds in it
        // would be a walk that did not walk.
        for (String seed : seeds()) {
            assertTrue(closure.contains(seed), "a declared type is not in its own closure: " + seed);
        }
        assertTrue(closure.contains(PREFIX + "HumanName"),
                "Patient.name is a HumanName and the closure did not reach it");
        assertTrue(closure.contains(PREFIX + "CodeableConcept"),
                "the closure did not reach the kernel every resource type is made of");

        // Reference is followed for the Reference type itself and not through
        // it: Observation.subject may point at a Device, and a tenant that
        // does not declare Device does not hold its definitions.
        assertTrue(closure.contains(PREFIX + "Reference"),
                "the closure did not reach Reference, which every resource carries");
        assertTrue(!closure.contains(PREFIX + "Device"),
                "the closure followed a reference target into a type nobody declared");

        // The narrowing, which is the whole reason for a closure. A face root
        // holds the version, so what it could hold and what a tenant needs
        // are both countable here and the ratio is the claim.
        int held = canonicalsWithRows();
        System.out.printf("%n=== the closure of %d declared types, over the rows ===%n"
                + "reached %d structures of the %d this tenant holds rows for (%.1f%%)%n",
                seeds().size(), closure.size(), held, 100.0 * closure.size() / held);
        assertTrue(closure.size() * 5 < held,
                "the closure narrowed nothing: " + closure.size() + " of " + held);
    }

    /** How many structures this tenant holds any rows for at all. */
    private static int canonicalsWithRows() {
        try (java.sql.Connection c = source().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT count(DISTINCT canonical) FROM definitions.definition_element")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("counting what the tenant holds failed", e);
        }
    }

    @Test
    @DisplayName("element for element, an index built from the rows says what one built from "
            + "the packages says")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theProjectionSaysWhatItWasProjectedFrom() throws Exception {
        Set<String> closure = DefinitionRows.closureOf(source(), seeds());
        DefinitionIndex rows = DefinitionRows.over(source(), closure);
        Map<String, List<Element>> packages = fromThePackages(closure);

        List<String> divergences = new ArrayList<>();
        int compared = 0;
        int elements = 0;
        for (String canonical : closure) {
            List<Element> published = packages.get(canonical);
            if (published == null || !rows.holds(canonical)) {
                continue;
            }
            compared++;
            List<Integer> held = rows.elementsOf(canonical);
            elements += held.size();
            if (held.size() != published.size()) {
                divergences.add(canonical + ": " + held.size() + " elements in the rows, "
                        + published.size() + " in the package");
                continue;
            }
            for (int i = 0; i < held.size(); i++) {
                Element theirs = published.get(i);
                Element ours = new Element(rows.pathOf(held.get(i)), rows.minOf(held.get(i)),
                        rows.maxOf(held.get(i)), rows.typesOf(held.get(i)));
                if (!ours.equals(theirs)) {
                    divergences.add(canonical + ": " + ours + " against " + theirs);
                }
            }
        }

        // Every string the index can be asked for, before interning.
        int occurrences = 0;
        for (int element = 0; element < rows.elements(); element++) {
            occurrences += 1 + rows.typesOf(element).size()
                    + 3 * rows.invariantsOf(element).size()
                    + (rows.bindingValueSetOf(element) == null ? 0 : 1);
        }

        System.out.printf("%n=== the rows against the packages, over %s's closure ===%n"
                + "structures in both %d of %d in the closure%n"
                + "elements compared    %d%n"
                + "the index holds      %d elements, %d structures, %d distinct words "
                + "for %d strings%n"
                + "divergences          %d%n",
                tenant.code(), compared, closure.size(), elements,
                rows.elements(), rows.structures(), rows.words(), occurrences,
                divergences.size());
        divergences.stream().limit(10).forEach(one -> System.out.println("  " + one));

        // An index that held nothing would agree with anything, and an
        // overlap of four structures would prove nothing about the rest. What
        // is asserted is not a threshold somebody chose: every structure the
        // closure reaches is held on both sides, so the comparison covers the
        // closure rather than whatever part of it happened to be in both.
        assertEquals(closure.size(), compared,
                "a structure in the closure was missing from one of the two sides");
        assertTrue(elements > 500, "too few elements compared to mean anything: " + elements);
        // The dictionary is the form, so it is counted rather than trusted:
        // every string the index can be asked for against the number it
        // actually holds. A path is nearly unique and pays nothing; ele-1 is
        // inherited onto almost every element there is and pays for the rest.
        assertTrue(rows.words() * 2 < occurrences,
                "the dictionary is not sharing anything: " + rows.words() + " words for "
                        + occurrences + " strings the index answers with");
        assertEquals(List.of(), divergences,
                "the projection says something its source does not");
    }

    /** One element, reduced to what both sides can be asked for. */
    private record Element(String path, int min, int max, List<String> types) {
    }

    /**
     * The same structures read out of the packages, which is what the spike
     * did and what this has to agree with.
     *
     * <p>No toolchain: a token walk over the snapshot, because a comparison
     * that needed a worker context to state one of its sides would be
     * measuring the context.
     */
    private static Map<String, List<Element>> fromThePackages(Set<String> wanted)
            throws Exception {
        Map<String, List<Element>> out = new LinkedHashMap<>();
        for (FaceRootPackages.Definition one
                : FaceRootPackages.definitionsFor("r4", Set.of("StructureDefinition"))) {
            if (one.url() == null || !wanted.contains(one.url())) {
                continue;
            }
            JsonNode snapshot = JSON.readTree(one.document()).path("snapshot").path("element");
            if (!snapshot.isArray() || snapshot.isEmpty()) {
                continue;
            }
            List<Element> elements = new ArrayList<>();
            for (JsonNode element : snapshot) {
                List<String> types = new ArrayList<>();
                for (JsonNode type : element.path("type")) {
                    if (type.hasNonNull("code")) {
                        types.add(type.get("code").asText());
                    }
                }
                String max = element.path("max").asText("");
                elements.add(new Element(element.path("path").asText(),
                        element.path("min").asInt(0),
                        "*".equals(max) ? DefinitionIndex.UNBOUNDED : safe(max),
                        types));
            }
            out.put(one.url(), elements);
        }
        return out;
    }

    private static int safe(String max) {
        try {
            return Integer.parseInt(max);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static Set<String> seeds() {
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : DECLARED) {
            seeds.add(PREFIX + type);
        }
        return seeds;
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
