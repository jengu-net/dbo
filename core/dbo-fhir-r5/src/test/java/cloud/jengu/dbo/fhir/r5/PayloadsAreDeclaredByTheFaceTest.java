package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading and writing a payload is what only a version knows, so it is the
 * version that declares it — asked for by type, the way the inward contract
 * says a capability is asked for.
 */
class PayloadsAreDeclaredByTheFaceTest {

    private static final byte[] PATIENT =
            "{\"resourceType\":\"Patient\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

    private static Payloads<Object> payloads() {
        DomainFace face = R5Version.face();
        @SuppressWarnings("unchecked")
        Payloads<Object> found = (Payloads<Object>) face.require(Payloads.class);
        return found;
    }

    @Test
    @DisplayName("the face answers for reading and writing a payload")
    void theFaceProvidesPayloads() {
        assertTrue(R5Version.face().capabilities().contains(Payloads.class),
                "a face that cannot read its own payloads is not serving a version");
    }

    @Test
    @DisplayName("one face for the version, not one per caller that asks")
    void theFaceIsTheVersions() {
        assertSame(R5Version.face(), R5Version.face());
    }

    @Test
    @DisplayName("a payload read and written again is the same resource")
    void aPayloadRoundTrips() {
        Payloads<Object> payloads = payloads();
        Object document = payloads.read("Patient", PATIENT);
        String written = new String(payloads.write(document), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"resourceType\":\"Patient\""), written);
        assertTrue(written.contains("\"active\":true"), written);
    }

    @Test
    @DisplayName("a resource with nothing wrong with it says nothing")
    void aValidResourceHasNoIssues() {
        Payloads<Object> payloads = payloads();
        List<String> issues = payloads.validate("Patient", payloads.read("Patient", PATIENT));
        assertEquals(List.of(), issues, "a plain Patient was reported as invalid");
    }

    @Test
    @DisplayName("bytes that are not this face's format are refused, not half-read")
    void unparseableBytesAreRefused() {
        Payloads<Object> payloads = payloads();
        assertFalse(assertThrowsMessage(() -> payloads.read("Patient", "not json".getBytes(
                StandardCharsets.UTF_8))).isEmpty());
    }

    private static String assertThrowsMessage(Runnable body) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return String.valueOf(e.getMessage());
        }
        throw new AssertionError("unparseable bytes were accepted");
    }
}
