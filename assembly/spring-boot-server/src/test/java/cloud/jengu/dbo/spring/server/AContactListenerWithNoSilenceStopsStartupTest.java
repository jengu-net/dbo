package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.work.ContactListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A contact listener bean that declares no silence stops the context, naming
 * the bean, before anything is registered: the refusal is made where the
 * author is looking.
 */
class AContactListenerWithNoSilenceStopsStartupTest {

    /** A listener somebody forgot to give a threshold. */
    static final class Forgetful implements ContactListener {

        @Override
        public String step() {
            return "clinic.visits.record";
        }

        @Override
        public Duration silence() {
            return null;
        }
    }

    @Test
    @DisplayName("a contact listener with no silence is refused at refresh, by its class name, "
            + "and nothing reaches the container")
    void aListenerWithNoSilenceIsRefusedByName() {
        // No runtime: the refusal is made before anything is registered, so
        // a context that got as far as the container would fail here on the
        // null rather than on the refusal.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new DboExtensions(null, List.of(), List.of(), List.of(),
                        List.of(new Forgetful())));

        assertTrue(refused.getMessage().contains(Forgetful.class.getName()),
                "the refusal names the bean: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("no default"), refused.getMessage());
    }
}
