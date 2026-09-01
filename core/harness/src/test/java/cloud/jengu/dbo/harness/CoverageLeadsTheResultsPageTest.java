package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a CI run means leads its results page, and the step that puts it there
 * is asserted rather than assumed.
 *
 * <p>This promise lived only in a workflow file, which is the one kind of
 * production code nothing here runs: a step can be renamed, reordered or
 * dropped in a routine edit and every test still passes. The report would
 * simply stop appearing, and the first person to notice would be somebody
 * wondering why a run no longer says what it proved.
 *
 * <p>So it is the fifth of the framework's unproven promises and the only one
 * that was not blocked by anything — no cycle stands in the way of reading a
 * file. It was grouped with the other four because they shared a status, which
 * is exactly the confusion a status that means two things produces.
 */
class CoverageLeadsTheResultsPageTest {

    private static final Path WORKFLOW = Path.of(System.getProperty("dbo.build.workflow",
            "../../.github/workflows/build.yml"));

    @Test
    @DisplayName("the build workflow renders the promise report into the run's summary")
    @Proving(DboPromises.PRM_COVERAGE_ON_THE_RESULTS_PAGE)
    void theReportReachesTheSummary() throws Exception {
        assertTrue(Files.exists(WORKFLOW),
                "no workflow at " + WORKFLOW.toAbsolutePath().normalize()
                        + " — this test guards a file it cannot find");
        String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

        assertTrue(workflow.contains("promiseReport"),
                "the workflow no longer renders the promise report, so a run's results "
                        + "page has stopped saying what the build proved");
        assertTrue(workflow.contains("GITHUB_STEP_SUMMARY"),
                "the report is rendered and not written to the run summary, which leaves "
                        + "it in the log where the CI-parity rule says it does not count");

        // Rendering and publishing in the same step is what makes the pair
        // hard to half-break: a report generated into a file nobody appends is
        // the failure this asserts against.
        int rendered = workflow.indexOf("promiseReport");
        int published = workflow.indexOf("GITHUB_STEP_SUMMARY", rendered);
        assertTrue(published > rendered && published - rendered < 400,
                "the report is rendered and the summary written far apart, so one can "
                        + "be removed without the other and nothing would say so");
    }
}
