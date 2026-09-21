package cloud.jengu.dbo.subscriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dispatch that keeps failing says so, and does not say it every second.
 *
 * <p>The defect this guards was silence. Every failure in the poll loop was
 * caught identically with no log and no counter, so a tenant polling a domain
 * that does not exist and a tenant whose feed had genuinely broken looked
 * exactly alike — and the only trace of either was the database logging a
 * failed statement once a second, on the order of three hundred thousand a
 * day for four of the sample world's seven tenants.
 *
 * <p>What is held here is the cadence rather than the emission. Both extremes
 * are defects: silence is what this replaces, and a line per pass is a line a
 * second per tenant, which is the shape of logging this store does not do.
 */
class AFailingDispatchSaysSoTest {

    @Test
    @DisplayName("the first failure is said at once, because that is when somebody could act")
    void theFirstOneIsSaid() {
        assertTrue(SubscriptionEngine.worthSaying(1));
    }

    @Test
    @DisplayName("the ones straight after it are not, because they are the same trouble")
    void theNextFewAreNot() {
        for (int pass = 2; pass < 60; pass++) {
            assertFalse(SubscriptionEngine.worthSaying(pass),
                    "pass " + pass + " would have been a second line about one trouble");
        }
    }

    @Test
    @DisplayName("and it is said again while it is still going, so a stuck feed is not "
            + "one line an hour old")
    void itIsSaidAgainWhileItLasts() {
        assertTrue(SubscriptionEngine.worthSaying(60));
        assertTrue(SubscriptionEngine.worthSaying(120));
    }
}
