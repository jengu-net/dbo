package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One envelope, from either set of parameters.
 *
 * <p>The envelope a definition type is indexed by is built from expressions
 * written out in the face, because a definition arriving at a tenant is what
 * the tenant does not have yet: the bootstrap cannot read rows the document
 * itself is about to create. Every other type is indexed from the parameters
 * the cut COMPILED, which need no evaluator of the face's own.
 *
 * <p>Two front ends, and one set of typed rules under them — a token is three
 * questions and not one, a string is held lowercased and again exactly, a
 * date is the moment its span opens. Stated once rather than once per front
 * end is the whole of the generalisation, and this is what holds the two to
 * the same answer: over the definition types BOTH can index, because the face
 * writes expressions for them and the cut compiles parameters for them too.
 *
 * <p>Without this the generalisation is an assertion. A second front end that
 * quietly indexed less would lose keys, and a search by a lost key finds
 * nothing while looking exactly like an answer.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneEnvelopeFromEitherSetOfParametersIT {

    /** The types the face writes expressions for and the cut compiles too. */
    private static final Set<String> BOTH = new TreeSet<>(Set.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"));

    static SharedTenants.Tenant tenant;
    static List<DefinitionRows.Parameter> compiled;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        compiled = DefinitionRows.parametersFor(source(), BOTH);
    }

    @Test
    @DisplayName("over every definition the face carries, the compiled parameters index what "
            + "the written-out expressions index")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void bothSetsOfParametersIndexTheSame() {
        assertTrue(compiled.size() > 40,
                "too few compiled parameters to mean anything: " + compiled.size());

        List<String> divergences = new ArrayList<>();
        List<String> declined = new ArrayList<>();
        int compared = 0;
        int keys = 0;
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4", BOTH)) {
            if (compared >= 300) {
                break;
            }
            compared++;
            Envelope written = cloud.jengu.dbo.fhir.element.DefinitionEnvelopeProbe.fromTheFace(
                    document.typeName(), document.document());
            Envelope fromRows = cloud.jengu.dbo.fhir.element.DefinitionEnvelopeProbe.fromTheRows(
                    compiled, document.typeName(), document.document(), declined);
            keys += written.paths().size();
            String difference = differing(written, fromRows);
            if (difference != null) {
                divergences.add(document.typeName() + " " + document.url() + ": " + difference);
            }
        }

        System.out.printf("%n=== one envelope from either set of parameters ===%n"
                + "documents %d, keys built by the written-out expressions %d%n"
                + "parameters the compiled reader declined: %s%n"
                + "divergences %d%n", compared, keys, declined, divergences.size());
        divergences.stream().limit(8).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 100, "too few documents compared: " + compared);
        assertTrue(keys > 500, "almost no keys were built, so agreeing means nothing: " + keys);
        assertEquals(List.of(), divergences,
                "the two sets of parameters index a document differently, which is a key "
                        + "present on one side and a search that finds nothing on the other");
    }

    /** What the two envelopes disagree about, or null. */
    private static String differing(Envelope written, Envelope fromRows) {
        Map<String, List<String>> left = shown(written);
        Map<String, List<String>> right = shown(fromRows);
        if (!left.keySet().equals(right.keySet())) {
            Set<String> missing = new TreeSet<>(left.keySet());
            missing.removeAll(right.keySet());
            Set<String> extra = new TreeSet<>(right.keySet());
            extra.removeAll(left.keySet());
            return "the compiled set misses " + missing + " and adds " + extra;
        }
        for (String key : left.keySet()) {
            if (!left.get(key).equals(right.get(key))) {
                return key + ": written " + left.get(key) + ", compiled " + right.get(key);
            }
        }
        return null;
    }

    /** An envelope as comparable text, values sorted because order is not the claim. */
    private static Map<String, List<String>> shown(Envelope envelope) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, List<EnvelopeValue>> entry : envelope.paths().entrySet()) {
            List<String> values = new ArrayList<>();
            for (EnvelopeValue value : entry.getValue()) {
                values.add(String.valueOf(value));
            }
            java.util.Collections.sort(values);
            out.put(entry.getKey(), values);
        }
        return out;
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
