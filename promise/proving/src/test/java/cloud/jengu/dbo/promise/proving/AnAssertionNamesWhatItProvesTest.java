package cloud.jengu.dbo.promise.proving;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Cites;
import cloud.jengu.dbo.promise.Promise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
 *
 * <p><b>The catalogue here is this test's own</b>, declared in four lines the
 * way {@link Cites} says an adopter declares theirs. Not decoration: an
 * assertion library that had to name a product's promises to test itself would
 * be one that could only serve that product.
 */
class AnAssertionNamesWhatItProvesTest {

    /** A product's catalogue, as small as one can be. */
    @Catalogue(namespace = "TEST")
    enum SomeonesPromises implements Promise {

        A_REPORT_NAMES_THE_PROMISE("a failure says what the product stopped promising"),
        SOMETHING_ELSE_ENTIRELY("a promise this test does not declare");

        private final String text;

        SomeonesPromises(String text) {
            this.text = text;
        }

        @Override
        public String text() {
            return text;
        }
    }

    /** And a product's citation, typed to it. */
    @Cites
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Proving {

        SomeonesPromises[] value();
    }

    private static final SomeonesPromises DECLARED = SomeonesPromises.A_REPORT_NAMES_THE_PROMISE;

    @Test
    @DisplayName("a failure leads with the promise's code and the sentence the catalogue holds")
    @Proving(SomeonesPromises.A_REPORT_NAMES_THE_PROMISE)
    void aFailureNamesThePromise() {
        AssertionError broke = assertThrows(AssertionError.class,
                () -> Proves.that(DECLARED, false, "the index answered nothing"));

        assertTrue(broke.getMessage().contains(DECLARED.code()),
                "a report that does not name the promise is the report this replaces: "
                        + broke.getMessage());
        assertTrue(broke.getMessage().contains(DECLARED.text()),
                "the sentence the catalogue holds is what makes the code readable by somebody "
                        + "who does not have the catalogue open: " + broke.getMessage());
        assertTrue(broke.getMessage().contains("the index answered nothing"),
                "the caller's own message was lost: " + broke.getMessage());
    }

    @Test
    @DisplayName("a promise this test did not declare is refused, because the catalogue reads "
            + "the declaration and not the assertion")
    @Proving(SomeonesPromises.A_REPORT_NAMES_THE_PROMISE)
    void anUndeclaredPromiseIsRefused() {
        AssertionError refused = assertThrows(AssertionError.class,
                () -> Proves.that(SomeonesPromises.SOMETHING_ELSE_ENTIRELY, true,
                        "true, and not mine"));

        assertTrue(refused.getMessage().contains(SomeonesPromises.SOMETHING_ELSE_ENTIRELY.code()),
                "the refusal should name what was claimed: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("reads as unproven"),
                "the refusal should say what goes wrong rather than that a check failed: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("what holds passes quietly, so a proved promise costs a test nothing to name")
    @Proving(SomeonesPromises.A_REPORT_NAMES_THE_PROMISE)
    void whatHoldsIsSilent() {
        Proves.that(DECLARED, true, "held");
        Proves.all(DECLARED,
                () -> assertEquals(2, 1 + 1),
                () -> assertTrue(true));
    }
}
