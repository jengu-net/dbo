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
 * <p>What leads the page is the promise toolset's ledger, drawn by its Gradle
 * plugin at the root and kept with the run; the site serves main's latest.
 */
class CoverageLeadsTheResultsPageTest {

    private static final Path WORKFLOW = Path.of(System.getProperty("dbo.build.workflow",
            "../../.github/workflows/build.yml"));

    /** The root build, which applies the promise toolset's plugin. */
    private static final Path ROOT_BUILD = Path.of(System.getProperty("dbo.root.build",
            "../../build.gradle.kts"));

    @Test
    @DisplayName("the build workflow draws the promise ledger after the suite, whatever it came "
            + "to, and keeps the page with the run; the plugin drawing it adds its summary to "
            + "the run's page")
    @Proving(DboPromises.PRM_COVERAGE_ON_THE_RESULTS_PAGE)
    void theLedgerLeadsTheResultsPage() throws Exception {
        assertTrue(Files.exists(WORKFLOW),
                "no workflow at " + WORKFLOW.toAbsolutePath().normalize()
                        + " — this test guards a file it cannot find");
        String workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

        int drawn = workflow.indexOf("./gradlew :promiseReport");
        assertTrue(drawn > 0, "the workflow no longer draws the promise ledger, so a run's "
                + "results page has stopped saying what the build proved");
        int step = workflow.lastIndexOf("- name:", drawn);
        assertTrue(workflow.substring(step, drawn).contains("if: always()"),
                "the ledger is drawn only when the suite passes, so the run most worth "
                        + "reading — a red one — is the one whose page says nothing");
        assertTrue(workflow.indexOf("build/reports/promise/", drawn) > drawn,
                "the ledger is drawn and not kept with the run, so its page is lost with "
                        + "the runner");

        // The summary is the plugin's to write: the toolset proves it adds
        // what it drew to $GITHUB_STEP_SUMMARY (REQ-PROMISE-THE-PLUGIN-DRAWS-THE-LEDGER).
        // What is the store's to keep is that the root applies it.
        String root = Files.readString(ROOT_BUILD, StandardCharsets.UTF_8);
        assertTrue(root.contains("id(\"cloud.jengu.promise\")")
                        && !root.contains("id(\"cloud.jengu.promise\") apply false"),
                "the root build no longer applies the promise plugin, so :promiseReport "
                        + "draws nothing");
    }
}
