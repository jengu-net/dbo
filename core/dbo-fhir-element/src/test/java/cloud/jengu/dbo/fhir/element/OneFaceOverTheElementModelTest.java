package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One face, over definitions rather than a generated model (#58).
 *
 * <p>Everything here is asked of the version's own package: what a payload
 * parses into, what is valid, what a search parameter means. Nothing in the
 * face is written for R6 — it is the version that has no generated Java model,
 * which is exactly why it is the one that proves the point.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneFaceOverTheElementModelTest {

    private static final String PATIENT = """
            {"resourceType":"Patient",
             "identifier":[{"system":"https://ee.ee/eid","value":"38001010001"}],
             "name":[{"family":"Aiakas","given":["Kass"]}],
             "gender":"female",
             "birthDate":"1980-01-01",
             "active":true}""";

    private final ElementVersion version = ElementVersion.of("r6");

    @SuppressWarnings("unchecked")
    private Payloads<Object> payloads() {
        return (Payloads<Object>) version.face().require(Payloads.class);
    }

    @Test
    @DisplayName("a version says what it serves, and says the ballot exactly")
    void theVersionSaysWhatItIs() {
        assertEquals("r6", version.code());
        assertEquals("6.0.0-ballot5", version.payloadVersion(),
                "a ballot is recorded by its own code, never the release it anticipates");
        assertEquals("fhir-r6", version.face().name());
    }

    @Test
    @DisplayName("a payload is read, named and written back")
    void aPayloadIsReadNamedAndWritten() {
        Payloads<Object> payloads = payloads();

        Object document = payloads.read(null, PATIENT.getBytes(StandardCharsets.UTF_8));

        assertEquals("Patient", payloads.typeOf(document));
        String written = new String(payloads.write(document), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"family\":\"Aiakas\""),
                "what was read must survive being written: " + written);
        assertTrue(written.contains("38001010001"));
    }

    @Test
    @DisplayName("a conformant resource is accepted, and a malformed one is not")
    void validationAnswersFromTheDefinitions() {
        Payloads<Object> payloads = payloads();

        Object conformant = payloads.read(null, PATIENT.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), payloads.validate("Patient", conformant),
                "a resource conformant to the version's own definitions must be accepted");

        Object wrong = payloads.read(null, """
                {"resourceType":"Patient","gender":"unicorn"}"""
                .getBytes(StandardCharsets.UTF_8));
        List<String> issues = payloads.validate("Patient", wrong);
        assertFalse(issues.isEmpty(), "a code outside the definition's value set must be refused");
        assertTrue(issues.get(0).contains("Patient"),
                "an issue must say where it is: " + issues.get(0));
    }

    @Test
    @DisplayName("the envelope comes from the version's own search parameters")
    void theEnvelopeComesFromTheDefinitions() {
        Envelope envelope = version.extractor("Patient")
                .extract("Patient", PATIENT.getBytes(StandardCharsets.UTF_8));

        assertTrue(envelope.paths().containsKey("identifier"),
                "the parameters this version defines are not being evaluated: "
                        + envelope.paths().keySet());
        // All three shapes a token search can ask for, because the index
        // answers by containment and a shape it does not carry is a search
        // that silently finds nothing (#81): sys|code, sys| — anything in that
        // system — and the bare code in whatever system it is in.
        assertEquals(List.of(new EnvelopeValue.Token("https://ee.ee/eid", "38001010001"),
                        new EnvelopeValue.Token("https://ee.ee/eid", null),
                        new EnvelopeValue.Token(null, "38001010001")),
                envelope.paths().get("identifier"));
        assertEquals(List.of(new cloud.jengu.dbo.core.api.Identifier(
                        "https://ee.ee/eid", "38001010001")), envelope.identifiers(),
                "an identifier is what an object is found by, not only indexed under");
        assertTrue(envelope.paths().get("family").contains(EnvelopeValue.of("aiakas")),
                "string search is case-insensitive, so the base path is folded");
        assertTrue(envelope.paths().get("family_xct").contains(EnvelopeValue.of("Aiakas")),
                ":exact matches what was written");
        assertNotNull(envelope.paths().get("birthdate"), "a date parameter must be typed as one");
        assertTrue(envelope.paths().get("birthdate").get(0) instanceof EnvelopeValue.Date,
                "a date indexed as a string sorts wrongly and ranges not at all");
    }

    @Test
    @DisplayName("a set is framed around its members, and the ancestors are put back")
    void aSetIsFramedAroundItsMembers() throws Exception {
        PayloadFraming framing = version.face().require(PayloadFraming.class);
        PayloadFraming.Frame frame = framing.frame("searchset",
                new PayloadFraming.Facts(2L, "http://dbo.test/Patient", null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(frame.prologue());
        framing.member(new PayloadFraming.Member("Patient", "one", 3,
                PATIENT.getBytes(StandardCharsets.UTF_8),
                "http://dbo.test/Patient/one", PayloadFraming.Member.MATCHED), out);
        out.write(frame.separator());
        framing.member(new PayloadFraming.Member("Patient", "two", 1,
                PATIENT.getBytes(StandardCharsets.UTF_8),
                "http://dbo.test/Patient/two", PayloadFraming.Member.INCLUDED), out);
        out.write(frame.epilogue());

        String document = out.toString(StandardCharsets.UTF_8);
        assertTrue(document.startsWith("{\"resourceType\":\"Bundle\",\"type\":\"searchset\""),
                document.substring(0, Math.min(80, document.length())));
        assertTrue(document.contains("\"total\":2"));
        assertTrue(document.contains("\"id\":\"one\""),
                "a reader must receive an id the stored bytes do not carry");
        assertTrue(document.contains("\"versionId\":\"3\""));
        assertTrue(document.contains("\"mode\":\"include\""), "an included member says so");
        assertTrue(document.endsWith("]}"));
    }

    @Test
    @DisplayName("and the face declares no converter, because there is none to declare")
    void noConverterIsDeclared() {
        assertTrue(version.face().capability(
                        cloud.jengu.dbo.core.api.PayloadConverter.class).isEmpty(),
                "a released HL7 core carries no R6 converters; declaring one would be a stub, "
                        + "and a stub is a lie the engine cannot tell apart from a broken one");
    }
}
