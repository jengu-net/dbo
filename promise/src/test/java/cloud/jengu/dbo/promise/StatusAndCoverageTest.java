package cloud.jengu.dbo.promise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusAndCoverageTest {

    private static Registry.Model model() {
        return Registry.of(TestPromises.class, TestFeatures.class, TestStories.class)
                .model(Proofs.of(Map.of(
                        "fixture.SomeTest#provesThePlainThing",
                        List.of(TestPromises.PLAIN_PROMISE))));
    }

    @Test
    @DisplayName("status is derived: cited proven, uncited planned, assured declared, gap a gap")
    void statusIsDerived() {
        Registry.Model model = model();
        assertEquals(PromiseStatus.PROVEN, model.statusOf(TestPromises.PLAIN_PROMISE));
        assertEquals(PromiseStatus.PLANNED, model.statusOf(TestPromises.QUIET_PROMISE));
        assertEquals(PromiseStatus.ASSURED, model.statusOf(TestPromises.REVIEWED_PROMISE));
        assertEquals(PromiseStatus.GAP,
                model.statusOf(TestFeatures.COVERED_GROUND.promises().get(3)));
        assertEquals(java.util.Set.of("fixture.SomeTest#provesThePlainThing"),
                model.citing(TestPromises.PLAIN_PROMISE));
    }

    @Test
    @DisplayName("coverage is a fold over the declared promises, gaps included")
    void coverageIsAFold() {
        Map<PromiseStatus, Long> coverage = model().coverage(TestFeatures.COVERED_GROUND);
        assertEquals(1L, coverage.get(PromiseStatus.PROVEN));
        assertEquals(1L, coverage.get(PromiseStatus.ASSURED));
        assertEquals(1L, coverage.get(PromiseStatus.PLANNED));
        assertEquals(1L, coverage.get(PromiseStatus.GAP));
    }

    @Test
    @DisplayName("the rendered report carries codes, statuses, sites and the gap text")
    void reportRenders() {
        String report = Report.render(model());
        assertTrue(report.contains("FEAT-TEST-COVERED-GROUND"), report);
        assertTrue(report.contains("REQ-TEST-PLAIN-PROMISE | PROVEN"), report);
        assertTrue(report.contains("fixture.SomeTest#provesThePlainThing"), report);
        assertTrue(report.contains("the corner nobody has stated"), report);
        assertTrue(report.contains("REQ-TEST-QUIET-PROMISE | PLANNED"), report);
    }
}
