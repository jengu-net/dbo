package cloud.jengu.dbo.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam's own behaviour, with no runtime around it.
 *
 * <p>Everything here is about the case that is easy to get wrong precisely
 * because it is the boring one: a node with nothing collecting. That is every
 * developer's machine and every test run, so it has to be the default rather
 * than a path that only exists when configuration is missing.
 */
class TheSeamDiscardsByDefaultTest {

    @Test
    @DisplayName("with no exporter installed the seam resolves to the discarding one, not "
            + "to null and not to an error")
    void theDefaultIsAnImplementation() {
        assertTrue(Telemetry.installed() instanceof Telemetry.Discarding);
        assertSame(Telemetry.none(), Telemetry.none(), "and it is not allocated per call");
    }

    @Test
    @DisplayName("discarding accepts every verb, because the emitting code path has to be "
            + "the same one a deployment runs")
    void discardingIsTotal() {
        Telemetry none = Telemetry.none();
        Labels labels = Labels.of(Label.TENANT, "hogwarts").and(Label.STEP, "validate");

        none.counted("dbo.run.reported", 1, labels);
        none.observed("dbo.run.duration", Duration.ofMillis(3), labels);
        none.level("dbo.lanes.attached", 2, labels);
    }

    @Test
    @DisplayName("labels carry what a Label names and nothing else, and a blank value is "
            + "left off rather than sent as an empty dimension")
    void labelsAreClosedAndTidy() {
        Labels labels = Labels.of(Label.TENANT, "hogwarts")
                .and(Label.EXECUTOR, "  ")
                .and(Label.STEP, null)
                .and(Label.OUTCOME, "closed");

        assertEquals(java.util.Set.of(Label.TENANT, Label.OUTCOME), labels.asMap().keySet());
        assertEquals("hogwarts", labels.asMap().get(Label.TENANT));
    }

    @Test
    @DisplayName("a caller cannot add a dimension of its own — the map handed out is not "
            + "a way back in")
    void theSetCannotBeWidenedThroughTheMap() {
        Labels labels = Labels.of(Label.TENANT, "hogwarts");

        assertThrows(UnsupportedOperationException.class,
                () -> labels.asMap().put(Label.STEP, "validate"),
                "the closed set is the point; a mutable view would be a second door to it");
    }

    @Test
    @DisplayName("every label's wire name is lower case and stable, because a collector's "
            + "dimension name is a rename nobody can do afterwards")
    void wireNamesAreStable() {
        for (Label label : Label.values()) {
            assertEquals(label.wire().toLowerCase(java.util.Locale.ROOT), label.wire(),
                    label + " would arrive at a collector in mixed case");
            assertTrue(label.wire().matches("[a-z][a-z0-9_]*"),
                    label + " is not a plain dimension name: " + label.wire());
        }
    }
}
