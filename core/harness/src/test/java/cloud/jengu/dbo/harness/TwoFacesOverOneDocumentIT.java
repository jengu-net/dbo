package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.fhir.validate.IndexPayloads;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload contract, answered without a worker context.
 *
 * <p>{@code IndexPayloads} is the same {@link Payloads} interface the element
 * face implements, over the definition index instead of a populated context.
 * It is the thing item 025 has been building towards: every defect this item
 * found looked like a face quietly answering less than it claimed, so what is
 * asked here is the whole contract — reading, the type, writing back, the
 * checks, and the shape stamp — rather than the parts that were easy.
 *
 * <p><b>The round trip is the part a checker never needed.</b> A checker may
 * read a document as a tree of text and never ask whether {@code 1.5} arrived
 * quoted. A face that has to give the document BACK must know, because
 * writing a number as a string corrupts every document it touches — so the
 * reader keeps a literal distinct from a string, and this holds it to that
 * over everything the version publishes.
 *
 * <p>What this does NOT do is serve. Nothing selects this face for a tenant
 * yet, and the reach ledger says so by name. What it settles is that the
 * contract can be met at all without the element model.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoFacesOverOneDocumentIT {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    private static final Set<String> TYPES = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem",
            "Patient", "Observation"));

    static SharedTenants.Tenant tenant;
    static IndexPayloads face;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : TYPES) {
            seeds.add(PREFIX + type);
        }
        DefinitionIndex index = DefinitionRows.over(source(),
                DefinitionRows.closureOf(source(), seeds));
        face = new IndexPayloads(index, BoundCodes.over(source(), index));
    }

    @Test
    @DisplayName("a document read and written back is the document that arrived, over "
            + "everything the version publishes")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void whatIsReadIsWhatIsWritten() {
        int compared = 0;
        List<String> altered = new ArrayList<>();
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4",
                Set.of("StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"))) {
            if (compared >= 400) {
                break;
            }
            compared++;
            IndexPayloads.Document read = face.read(document.typeName(), document.document());
            byte[] written = face.write(read);
            // Read again rather than compared byte for byte: what has to
            // survive is the document, not the whitespace somebody's composer
            // happened to emit. A value that changed type, a repeat that lost
            // an entry or a decimal that lost a digit all fail this.
            if (!face.read(document.typeName(), written).tree().equals(read.tree())) {
                altered.add(document.typeName() + " " + document.url());
            }
        }
        System.out.printf("%n=== read and written back, over r4 ===%n"
                + "documents %d, altered %d%n", compared, altered.size());
        altered.stream().limit(5).forEach(one -> System.out.println("  " + one));

        assertTrue(compared > 200, "too few documents to mean anything: " + compared);
        assertEquals(List.of(), altered, "a document did not survive being read and written");
    }

    @Test
    @DisplayName("a literal is not a string: what arrived unquoted is written unquoted, and a "
            + "decimal keeps the precision its author gave it")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aLiteralIsNotAString() {
        // The failure this guards is silent and total: a face that wrote
        // numbers as strings would corrupt every document it stored, and a
        // tree-to-tree comparison above would not catch it on its own because
        // both sides would be equally wrong.
        String written = """
                {"resourceType":"Observation","status":"final","valueQuantity":{"value":1.500},\
                "issued":"2020-01-01T00:00:00Z","_status":{"id":"x"},\
                "component":[{"valueBoolean":true},{"valueInteger":0}]}""";
        IndexPayloads.Document read = face.read("Observation",
                written.getBytes(StandardCharsets.UTF_8));
        String back = new String(face.write(read), StandardCharsets.UTF_8);

        assertTrue(back.contains("\"value\":1.500"),
                "a decimal lost the precision its author wrote, which FHIR makes "
                        + "significant: " + back);
        assertTrue(back.contains("\"valueBoolean\":true") && !back.contains("\"true\""),
                "a boolean came back as a string: " + back);
        assertTrue(back.contains("\"valueInteger\":0") && !back.contains("\"0\""),
                "a number came back as a string: " + back);
        assertTrue(back.contains("\"status\":\"final\""),
                "a string came back unquoted: " + back);
        assertTrue(back.contains("\"_status\""),
                "a primitive's own extension did not survive: " + back);
    }

    @Test
    @DisplayName("the type, the shape stamp and a body that is not FHIR JSON are answered the "
            + "way the contract says")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void theRestOfTheContract() {
        IndexPayloads.Document patient = face.read("Patient",
                "{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("Patient", face.typeOf(patient), "the document does not say what it is");

        // A malformed body is the caller's fault, not the server's.
        assertThrows(IllegalArgumentException.class,
                () -> face.read("Patient", "not json".getBytes(StandardCharsets.UTF_8)),
                "a body that is not FHIR JSON was accepted");

        // A stamp names the version that did the judging. The base Patient is
        // a shape this tenant holds, so a document claiming it is stamped;
        // one claiming a profile nobody holds is not, because a stamp would
        // be naming rules that never ran.
        IndexPayloads.Document claimed = face.read("Patient", ("""
                {"resourceType":"Patient","meta":{"profile":["%sPatient",\
                "https://ee.ee/StructureDefinition/EiOle"]}}""".formatted(PREFIX))
                .getBytes(StandardCharsets.UTF_8));
        List<String> stamps = face.writtenUnder(claimed);
        System.out.println("stamps: " + stamps);
        assertEquals(1, stamps.size(),
                "a profile nobody holds was stamped, or one everybody holds was not: " + stamps);
        assertTrue(stamps.get(0).startsWith(PREFIX + "Patient|"),
                "the stamp does not name the shape and its version: " + stamps);
    }

    @Test
    @DisplayName("what it refuses and what it accepts is what the database says about the same "
            + "document")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void itRefusesWhatTheDatabaseRefuses() {
        // Clean.
        assertEquals(List.of(), face.validate("Patient",
                face.read("Patient", "{\"resourceType\":\"Patient\",\"gender\":\"female\"}"
                        .getBytes(StandardCharsets.UTF_8))),
                "a correct document was refused");

        // A required element absent, an element sent twice where one is
        // allowed, and a code outside a required binding: one of each of the
        // three kinds this face answers.
        assertTrue(!face.validate("Observation", face.read("Observation",
                        "{\"resourceType\":\"Observation\"}".getBytes(StandardCharsets.UTF_8)))
                        .isEmpty(),
                "a document missing a required element was accepted");
        assertTrue(!face.validate("Patient", face.read("Patient",
                        "{\"resourceType\":\"Patient\",\"gender\":[\"female\",\"male\"]}"
                                .getBytes(StandardCharsets.UTF_8))).isEmpty(),
                "an element allowed once and sent twice was accepted — which is the defect "
                        + "this item found the toolchain committing in silence");
        assertTrue(!face.validate("Patient", face.read("Patient",
                        "{\"resourceType\":\"Patient\",\"gender\":\"kass\"}"
                                .getBytes(StandardCharsets.UTF_8))).isEmpty(),
                "a code outside a required binding was accepted");

        // And a type this tenant holds no shape for is not a document that is
        // wrong: unresolvable is not invalid.
        assertEquals(List.of(), face.validate("Appointment", face.read("Appointment",
                        "{\"resourceType\":\"Appointment\"}".getBytes(StandardCharsets.UTF_8))),
                "a shape the tenant does not hold was treated as a refusal");
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }
}
