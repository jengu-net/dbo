package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A given object, read back from the run that recorded it.
 *
 * <p>A run's slots are read out of its record and each given object is
 * written out again for the performer, so this reader is the second place a
 * decimal can lose the precision it was sent with: read as a double,
 * {@code 37.40} comes back as {@code 37.4}, which FHIR reads as a less precise
 * measurement.
 */
class ARecordedGivenObjectKeepsItsDecimalsTest {

    @Test
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    @DisplayName("a given object read back from its run is written on as it was sent")
    void theRunHandsOnWhatItWasGiven() {
        String visit = "{\"resourceType\":\"Observation\","
                + "\"valueQuantity\":{\"value\":37.40},"
                + "\"component\":[{\"valueQuantity\":{\"value\":0.0000001}},"
                + "{\"valueQuantity\":{\"value\":-12}}]}";

        assertEquals(visit, Json.render(Json.parse(visit)));
    }
}
