package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reindex that did not finish has to be brought back by something.
 *
 * <p>The feed's events are acknowledged before the rebuild runs — deliberately,
 * so a broken profile is not re-read forever — which left a reindex that failed
 * with nothing to trigger it again. The index stayed stale behind one warning,
 * and a stale envelope does not make a search slow: it makes it miss, which
 * reads as nobody here.
 *
 * <p>What is proven here is the remembering, not the wiring. Proving the whole
 * round needs a way to make a tenant's reindex fail on demand, which the
 * runtime does not offer; that is recorded against the issue rather than
 * implied by a test that does not do it.
 */
class AStaleIndexComesBackTest {

    @Test
    @Proving(DboPromises.TEN_A_STALE_INDEX_IS_REMEMBERED_UNTIL_IT_IS_REBUILT)
    @DisplayName("a tenant whose reindex failed is rebuilt again although nothing new arrived")
    void theEventsAreGoneSoTheTenantIsRemembered() {
        StaleIndexes stale = new StaleIndexes();

        // Nothing outstanding: a rebuild happens because something arrived.
        assertFalse(stale.needsRebuild("hogwarts", false),
                "a tenant with nothing outstanding and nothing new was rebuilt anyway");
        assertTrue(stale.needsRebuild("hogwarts", true));

        stale.note("hogwarts");

        // The events that would have triggered it are acked and gone, so this
        // is the whole of what brings the rebuild back.
        assertTrue(stale.needsRebuild("hogwarts", false),
                "the reindex failed and nothing brings it back, which is how an index stays "
                        + "stale behind a single warning");
        assertFalse(stale.needsRebuild("gringotts", false),
                "one tenant's failure put another in line for a rebuild it does not need");
    }

    @Test
    @Proving(DboPromises.TEN_A_STALE_INDEX_IS_REMEMBERED_UNTIL_IT_IS_REBUILT)
    @DisplayName("the warning is said once, and the recovery is said by the round that achieves it")
    void aLogThatRepeatsItselfStopsBeingRead() {
        StaleIndexes stale = new StaleIndexes();

        assertTrue(stale.note("hogwarts"), "the first failure said nothing");
        assertFalse(stale.note("hogwarts"),
                "the same failure is announced every round, which is how a log stops being "
                        + "read at all");

        assertTrue(stale.cleared("hogwarts"),
                "the round that finished the reindex cannot say so");
        assertFalse(stale.cleared("hogwarts"),
                "a tenant that was never behind reports a recovery");

        // And once it is clear, it is clear.
        assertFalse(stale.needsRebuild("hogwarts", false));
        assertTrue(stale.outstanding().isEmpty());
    }

    @Test
    @Proving(DboPromises.TEN_A_STALE_INDEX_IS_REMEMBERED_UNTIL_IT_IS_REBUILT)
    @DisplayName("what is outstanding can be read, because a deployment is asked")
    void aDeploymentCanBeAskedWhichIndexesAreBehind() {
        StaleIndexes stale = new StaleIndexes();
        stale.note("hogwarts");
        stale.note("gringotts");
        assertTrue(stale.outstanding().containsAll(java.util.Set.of("hogwarts", "gringotts")));
        stale.cleared("hogwarts");
        assertTrue(stale.outstanding().contains("gringotts"));
        assertFalse(stale.outstanding().contains("hogwarts"));
    }
}
