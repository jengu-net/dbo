package cloud.jengu.dbo.proving;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an assertion says when it breaks, and what it refuses to claim.
 *
 * <p>Two things are worth proving about a thing whose whole job is to report
 * well: that the report names the promise, and that the promise cannot be one
 * the test never declared. The second is the one that matters — without it
 * there are two places saying what proves what, and the catalogue reads only
 * one of them.
 */
class AnAssertionNamesWhatItProvesTest {

    private static final DboPromises DECLARED = DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX;

    @Test
    @DisplayName("a failure leads with the promise's code and the sentence the catalogue holds")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void aFailureNamesThePromise() {
        AssertionError broke = assertThrows(AssertionError.class,
                () -> Proves.that(DECLARED, false, "the index answered nothing"));

        assertTrue(broke.getMessage().contains(DECLARED.code()),
                "a report that does not name the promise is the report this replaces: "
                        + broke.getMessage());
        assertTrue(broke.getMessage().contains(DECLARED.text().substring(0, 24)),
                "the sentence the catalogue holds is what makes the code readable by somebody "
                        + "who does not have the catalogue open: " + broke.getMessage());
        assertTrue(broke.getMessage().contains("the index answered nothing"),
                "the caller's own message was lost: " + broke.getMessage());
    }

    @Test
    @DisplayName("a promise this test did not declare is refused, because the catalogue reads "
            + "the declaration and not the assertion")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void anUndeclaredPromiseIsRefused() {
        AssertionError refused = assertThrows(AssertionError.class,
                () -> Proves.that(DboPromises.SYNC_DECLARED_ONLY, true, "true, and not mine"));

        assertTrue(refused.getMessage().contains(DboPromises.SYNC_DECLARED_ONLY.code()),
                "the refusal should name what was claimed: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("reads as unproven"),
                "the refusal should say what goes wrong rather than that a check failed: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("what holds passes quietly, so a proved promise costs a test nothing to name")
    @Proving(DboPromises.VAL_A_THIRD_ANSWERER_READS_THE_INDEX)
    void whatHoldsIsSilent() {
        Proves.that(DECLARED, true, "held");
        Proves.all(DECLARED,
                () -> assertEquals(2, 1 + 1),
                () -> assertTrue(true));
    }
}
