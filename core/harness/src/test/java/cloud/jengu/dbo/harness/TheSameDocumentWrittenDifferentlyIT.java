package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.DocumentEquivalence;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.maintenance.TenantImport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether two documents are the same object is the domain's question, and the
 * engine's own answer is wrong in a way nobody notices.
 *
 * <p>Comparing bytes says a resource changed because a tool wrote its fields in
 * another order — so a re-import rewrites every object, and a tenant gets a
 * version bump for a no-op. Comparing through the domain's canonical form says
 * what it means: same object, said differently.
 */
@Tag("integration")
class TheSameDocumentWrittenDifferentlyIT {

    private static final DocumentEquivalence EQUIVALENCE =
            R4FhirVersion.INSTANCE.face().require(DocumentEquivalence.class);

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("field order, indentation and line breaks are not a change")
    void theSameObjectWrittenTwoWays() {
        byte[] asWritten = utf8("""
                {"resourceType":"Patient","id":"x","active":true,
                 "name":[{"family":"Kask","given":["Mari"]}]}""");
        byte[] asAnotherToolWroteIt = utf8(
                "{ \"name\" : [ { \"given\": [\"Mari\"], \"family\": \"Kask\" } ],"
                        + "\"active\": true, \"id\":\"x\", \"resourceType\":\"Patient\" }");

        assertTrue(EQUIVALENCE.same(asWritten, asAnotherToolWroteIt),
                "the same resource, and comparing bytes would have called it a change");
        assertFalse(java.util.Arrays.equals(asWritten, asAnotherToolWroteIt),
                "the bytes really do differ, which is the whole point");
        assertFalse(TenantImport.comparingBytes().same(asWritten, asAnotherToolWroteIt),
                "and the answer a caller with no face gets is the honest, worse one");
    }

    @Test
    @DisplayName("array order is a change, because in FHIR it means something")
    void arrayOrderIsMeaningful() {
        byte[] mariThenJuhan = utf8(
                "{\"resourceType\":\"Patient\",\"given\":[\"Mari\",\"Juhan\"]}");
        byte[] juhanThenMari = utf8(
                "{\"resourceType\":\"Patient\",\"given\":[\"Juhan\",\"Mari\"]}");

        assertFalse(EQUIVALENCE.same(mariThenJuhan, juhanThenMari),
                "sorting arrays would call two different resources equal, and that is the "
                        + "failure nobody notices afterwards");
    }

    @Test
    @DisplayName("a decimal's trailing zero is precision, not noise")
    void trailingZerosAreNotNoise() {
        assertFalse(EQUIVALENCE.same(
                        utf8("{\"resourceType\":\"Observation\",\"valueQuantity\":{\"value\":1.50}}"),
                        utf8("{\"resourceType\":\"Observation\",\"valueQuantity\":{\"value\":1.5}}")),
                "1.50 and 1.5 are different measurements in FHIR");
    }

    @Test
    @DisplayName("an element the version has never heard of survives canonicalisation")
    void nothingIsInterpreted() {
        byte[] withAnUnknownField = utf8(
                "{\"resourceType\":\"Patient\",\"somethingR9WillAdd\":{\"deep\":[1,2]}}");

        assertTrue(new String(EQUIVALENCE.canonical(withAnUnknownField), StandardCharsets.UTF_8)
                        .contains("somethingR9WillAdd"),
                "canonicalising through the resource model would drop what the version does "
                        + "not define, and then call two documents equal because it had thrown "
                        + "away the field they differ in");
        assertArrayEquals(EQUIVALENCE.canonical(withAnUnknownField),
                EQUIVALENCE.canonical(utf8(
                        "{ \"somethingR9WillAdd\" : {\"deep\":[1,2]}, \"resourceType\":\"Patient\"}")),
                "and it still sorts, so the unknown field compares like any other");
    }

    @Test
    @DisplayName("bytes that are not a document are the caller's mistake, said as one")
    void notADocument() {
        assertThrows(IllegalArgumentException.class, () -> EQUIVALENCE.canonical(utf8("{ not json")));
    }
}
