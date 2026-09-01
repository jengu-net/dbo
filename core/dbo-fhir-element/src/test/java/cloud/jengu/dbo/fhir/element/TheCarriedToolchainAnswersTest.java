package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.face.Payloads;
import org.hl7.fhir.r5.model.SearchParameter;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every version this face carries can still be read, checked and searched —
 * the gate on the toolchain underneath it.
 *
 * <p>This is not a test of a library. A version arrives as a package and a
 * version code inside it, and the stack either knows that code or refuses the
 * definitions outright: the core we shipped before 6.10.2 carried the R6 loader
 * and stopped at ballot3, so it looked capable and was not. A jar downgrade,
 * a package bumped without its jar, a definition set that stopped loading — all
 * of it lands here rather than in a runtime, days later, as a tenant that will
 * not come up.
 *
 * <p>Held per carried version, and the same instance for all of them, because
 * one implementation serving three versions is the claim being made.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheCarriedToolchainAnswersTest {

    /** Valid in every version this face carries — the same bytes, three readers. */
    private static final byte[] PATIENT = """
            {"resourceType":"Patient",
             "identifier":[{"system":"https://ee.ee/eid","value":"38001010001"}],
             "name":[{"family":"Aiakas","given":["Kass"]}],
             "gender":"female","birthDate":"1980-01-01"}""".getBytes(StandardCharsets.UTF_8);

    static List<String> carriedVersions() {
        return List.copyOf(CarriedDefinitions.versions());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("carriedVersions")
    @DisplayName("the definitions load, and say which version they are")
    void theDefinitionsLoad(String code) {
        ElementVersion version = ElementVersion.of(code);

        assertEquals(CarriedDefinitions.forVersion(code).get(0).version(),
                version.payloadVersion(),
                "the context reports a version other than the package it was built from");
        assertTrue(version.context().fetchResourcesByType(StructureDefinition.class).size() > 200,
                "the shapes this version validates against are missing");
        assertTrue(version.context().fetchResourcesByType(SearchParameter.class).size() > 500,
                "the parameters an envelope is extracted from are missing");
        assertTrue(version.context().getResourceNames().size() > 100,
                "a version that names no resources cannot serve one");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("carriedVersions")
    @DisplayName("a resource is read, checked, written back and extracted from")
    void aResourceMakesItThroughTheWholePath(String code) {
        ElementVersion version = ElementVersion.of(code);
        @SuppressWarnings("unchecked")
        Payloads<Object> payloads = (Payloads<Object>) version.face().require(Payloads.class);

        Object document = payloads.read(null, PATIENT);
        assertEquals("Patient", payloads.typeOf(document));
        assertEquals(List.of(), payloads.validate("Patient", document),
                "a resource conformant to this version's own definitions must be accepted");
        assertTrue(new String(payloads.write(document), StandardCharsets.UTF_8)
                        .contains("Aiakas"),
                "what was read must survive being written");

        Envelope envelope = version.extractor("Patient").extract("Patient", PATIENT);
        assertTrue(envelope.paths().containsKey("identifier"),
                "the parameters this version defines are not being evaluated: "
                        + envelope.paths().keySet());
        assertTrue(envelope.paths().containsKey("family"));
        assertTrue(envelope.paths().containsKey("birthdate"));
    }
}
