package cloud.jengu.dbo.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every piece of a host reaches the one container it has.
 *
 * <p>A host assembled from two pieces used to lose one of them. Each built the
 * container from its own configuration under a condition that it did not
 * already exist, so the piece that lost the race had its properties read,
 * converted and dropped — observed as a worker polling at the default two
 * seconds beside a configuration that said 500 milliseconds. Nothing failed,
 * which is the part worth a test: a dial nobody can trust without timing it
 * reads exactly like a dial that works.
 */
class OneHostSaysOneThingToItsContainerTest {

    @Test
    @DisplayName("what every piece of a host says reaches the container, not what one of them said")
    void everyPieceIsApplied() {
        Map<String, String> all = FrameworkContribution.merged(List.of(
                () -> Map.of("dbo.tenant.dir", "/world", "dbo.tenant.http.shared", "true"),
                () -> Map.of("dbo.runner.poll.millis", "500")));

        assertEquals("500", all.get("dbo.runner.poll.millis"),
                "the performing half's dial did not reach the container, which is the defect "
                        + "this collects properties to prevent: " + all);
        assertEquals("/world", all.get("dbo.tenant.dir"),
                "the serving half's world did not reach the container: " + all);
    }

    @Test
    @DisplayName("a host that sets one property to two values is refused, rather than given "
            + "whichever piece was applied last")
    void disagreementIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> FrameworkContribution.merged(List.of(
                        () -> Map.of("dbo.runner.poll.millis", "500"),
                        () -> Map.of("dbo.runner.poll.millis", "2000"))));

        assertTrue(refused.getMessage().contains("dbo.runner.poll.millis"),
                "the refusal does not name the property, so whoever reads it has to go and "
                        + "find out: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("500") && refused.getMessage().contains("2000"),
                "a refusal that names neither value leaves the reader to guess which piece said "
                        + "what: " + refused.getMessage());
    }

    @Test
    @DisplayName("agreeing twice is not a disagreement, because two pieces of one deployment "
            + "naming the same substrate is ordinary")
    void agreementIsNotAConflict() {
        Map<String, String> all = FrameworkContribution.merged(List.of(
                () -> Map.of("dbo.substrate.url", "jdbc:postgresql://db/dbo"),
                () -> Map.of("dbo.substrate.url", "jdbc:postgresql://db/dbo")));

        assertEquals("jdbc:postgresql://db/dbo", all.get("dbo.substrate.url"));
    }
}
