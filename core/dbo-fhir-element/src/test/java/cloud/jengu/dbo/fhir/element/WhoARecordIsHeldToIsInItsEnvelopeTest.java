package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.HeldTo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The engine leaves a held record out of a query by what its envelope says,
 * so a label the envelope does not carry is a record every search answers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhoARecordIsHeldToIsInItsEnvelopeTest {

    private final ElementVersion version = ElementVersion.of("r5");

    @Test
    @DisplayName("the organisation a record is held to is lifted from its security labels, "
            + "and no other label is")
    void theHeldLabelIsLifted() {
        Envelope envelope = version.extractor("Patient").extract("Patient", """
                {"resourceType":"Patient",
                 "meta":{"security":[
                   {"system":"%s","code":"urn:example:org|lab"},
                   {"system":"http://terminology.hl7.org/CodeSystem/v3-Confidentiality",
                    "code":"R"}]},
                 "name":[{"family":"Lovegood"}]}""".formatted(HeldTo.SYSTEM)
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(EnvelopeValue.of("urn:example:org|lab")),
                envelope.paths().get(HeldTo.PATH),
                "the envelope does not say who the record is held to: " + envelope.paths());
    }

    @Test
    @DisplayName("a record no label holds has nothing at the held path")
    void aRecordNoLabelHoldsHasNone() {
        Envelope envelope = version.extractor("Patient").extract("Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Longbottom\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        assertFalse(envelope.paths().containsKey(HeldTo.PATH), envelope.paths().toString());
    }
}
