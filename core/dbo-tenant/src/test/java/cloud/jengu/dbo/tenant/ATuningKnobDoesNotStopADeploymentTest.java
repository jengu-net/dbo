package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many tenants a node brings up at once is the deployment's to say, and
 * saying it badly must not be how a deployment stops serving.
 *
 * <p>A number this store cannot find out for itself: what bounds a node is how
 * many validators it can hold, which is heap, which is the deployment's.
 */
class ATuningKnobDoesNotStopADeploymentTest {

    @Test
    @Proving(DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER)
    @DisplayName("a deployment that says how many, gets how many")
    void whatTheDeploymentDeclaredIsWhatItGets() {
        assertEquals(8, Activator.broughtUpTogether("8", 2));
        assertEquals(8, Activator.broughtUpTogether("  8  ", 2),
                "a value with the whitespace a config file gives it");
        assertEquals(1, Activator.broughtUpTogether("1", 2),
                "one at a time is a legitimate answer: it is what this did before");
    }

    @Test
    @Proving(DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER)
    @DisplayName("and a deployment that says nothing, or nonsense, keeps serving")
    void nonsenseIsIgnoredRatherThanFatal() {
        assertEquals(2, Activator.broughtUpTogether(null, 2), "nothing said");
        assertEquals(2, Activator.broughtUpTogether("", 2));
        assertEquals(2, Activator.broughtUpTogether("   ", 2));
        // The cases that would be worse than the default if honoured, and
        // worse still if they stopped the deployment: a node that brings up
        // no tenants at a time serves nothing, and neither does one that
        // refused to boot over a typo in a number whose whole job is to make
        // tenants come up faster.
        assertEquals(2, Activator.broughtUpTogether("0", 2));
        assertEquals(2, Activator.broughtUpTogether("-4", 2));
        assertEquals(2, Activator.broughtUpTogether("lots", 2));
        assertEquals(2, Activator.broughtUpTogether("8 tenants", 2));
    }

    /**
     * The two bounds are read the same way and mean different things: a
     * bring-up holds a validator, a stream holds a database connection, and
     * tuning one for the other's constraint is how streams came to be capped
     * at the number heap allows for bring-ups.
     */
    @Test
    @Proving(DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER)
    @DisplayName("a deployment tunes streams and bring-ups separately")
    void eachBoundIsItsOwn() {
        assertEquals(16, Activator.atOnce("16", Activator.DEFAULT_STREAMS_TOGETHER,
                Activator.STREAMS_TOGETHER));
        assertEquals(Activator.DEFAULT_STREAMS_TOGETHER,
                Activator.atOnce(null, Activator.DEFAULT_STREAMS_TOGETHER,
                        Activator.STREAMS_TOGETHER));
        assertEquals(Activator.DEFAULT_STREAMS_TOGETHER,
                Activator.atOnce("none", Activator.DEFAULT_STREAMS_TOGETHER,
                        Activator.STREAMS_TOGETHER));
        assertTrue(Activator.DEFAULT_STREAMS_TOGETHER > Activator.DEFAULT_BROUGHT_UP_TOGETHER,
                "streams are bounded by the database and bring-ups by heap: tying them "
                        + "together is what this separates");
    }
}
