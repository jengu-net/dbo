package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request reads a payload once, however many questions it asks of it.
 *
 * <p>The write path used to ask the personality for the type and then for the
 * verdict, each from the bytes, and the engine's envelope extraction read them
 * again — three reads of one document to accept one write. Counting is the only
 * way to see that: the output of two reads and of one is identical, which is
 * why it went unnoticed.
 */
class OneReadPerRequestTest {

    private static final byte[] PATIENT =
            "{\"resourceType\":\"Patient\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

    @SuppressWarnings("unchecked")
    private static Payloads<Object> payloads() {
        return (Payloads<Object>) R4Version.face().require(Payloads.class);
    }

    @Test
    @DisplayName("the type and the verdict come from one read")
    void theTypeAndTheVerdictShareOneRead() {
        Payloads<Object> payloads = payloads();
        long before = R4Version.READS.get();

        Object document = payloads.read(null, PATIENT);
        String type = payloads.typeOf(document);
        List<String> issues = payloads.validate(type, document);

        assertEquals(1, R4Version.READS.get() - before,
                "the write path is reading the same bytes more than once again");
        assertEquals("Patient", type);
        assertEquals(List.of(), issues);
    }

    @Test
    @DisplayName("and asking the same document twice does not read it again")
    void askingTwiceDoesNotReadTwice() {
        Payloads<Object> payloads = payloads();
        Object document = payloads.read(null, PATIENT);
        long after = R4Version.READS.get();

        payloads.typeOf(document);
        payloads.validate(payloads.typeOf(document), document);

        assertEquals(after, R4Version.READS.get(),
                "a question about a document read the bytes behind it");
    }

    @Test
    @DisplayName("and the engine's envelope extraction is that same read")
    void theEnvelopeComesFromTheSameRead() {
        Payloads<Object> payloads = payloads();
        byte[] payload = "{\"resourceType\":\"Patient\",\"active\":true}"
                .getBytes(StandardCharsets.UTF_8);
        long before = R4Version.READS.get();

        // what accepting a write does: the face reads and answers both
        // questions, then the engine — which is handed bytes, not a document —
        // extracts the envelope from the very array that will be stored
        Object document = payloads.read(null, payload);
        payloads.validate(payloads.typeOf(document), document);
        extractor().extract("Patient", payload);

        assertEquals(1, R4Version.READS.get() - before,
                "the engine is reading a payload the face has already read");
    }

    @Test
    @DisplayName("but bytes a decorator rewrote are read as they now stand")
    void rewrittenBytesAreReadAgain() {
        Payloads<Object> payloads = payloads();
        byte[] asAuthored = "{\"resourceType\":\"Patient\",\"active\":true}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] asStored = "{\"resourceType\":\"Patient\",\"active\":false}"
                .getBytes(StandardCharsets.UTF_8);
        long before = R4Version.READS.get();

        // the isolation decorator rewrites a person's payload on the way in, so
        // the document the face is holding is not the document being stored —
        // an envelope built from it would index what the payload no longer says
        payloads.read(null, asAuthored);
        extractor().extract("Patient", asStored);

        assertEquals(2, R4Version.READS.get() - before,
                "the engine extracted an envelope from bytes it was not given");
    }

    /** The extractor the engine is handed for a configured type. */
    private static EnvelopeExtractor extractor() {
        return new R4Personality(List.of(
                new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER, Set.of("urn:t"),
                        Handling.operational())))
                .registrations().get(0).extractor();
    }
}
