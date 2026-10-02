package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A given object, read at the step door and written into the run.
 *
 * <p>The door reads a run's inputs with this module's own reader and writes
 * each given object back out with the wire's writer, so whatever the reader
 * makes of a number is what the performer is handed. A FHIR decimal carries
 * its precision in how it is written: {@code 37.40} says the thermometer read
 * to a hundredth, and {@code 37.4} does not.
 */
class AGivenObjectKeepsItsDecimalsTest {

    private static final String VISIT = "{\"resourceType\":\"Observation\","
            + "\"valueQuantity\":{\"value\":37.40,\"unit\":\"Cel\"},"
            + "\"referenceRange\":[{\"low\":{\"value\":0.0000001},\"high\":{\"value\":-12}}]}";

    @Test
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    @DisplayName("a given object with a decimal in it is read, and written on as it was sent")
    void aTemperatureIsNotAWholeNumber() {
        Object read = Json.parse(VISIT);

        assertEquals(VISIT, RecordWire.write(read),
                "the door's reader and the wire's writer between them changed the object");
    }

    @Test
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    @DisplayName("a whole number stays whole and one past a long is still exact")
    void wholeNumbersAreNotDecimals() {
        String counted = "{\"count\":3,\"big\":123456789012345678901234567890}";

        assertEquals(counted, RecordWire.write(Json.parse(counted)));
    }

    @Test
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    @DisplayName("an escaped carriage return in a given note is a carriage return, not an r")
    void aNoteKeepsItsLineBreaks() {
        Object read = Json.parse("{\"text\":\"line one\\r\\nline two\\f\\b\"}");

        assertEquals("line one\r\nline two\f\b", Json.strOpt(read, "text"));
    }
}
