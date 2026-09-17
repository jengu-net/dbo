package cloud.jengu.dbo.guide;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's chapters, executed in the order a reader meets them.
 *
 * <p>One world, many assertions — which is the arrangement the shell harness
 * already had and the rest of the suite does not: eighty-six integration tests
 * each build a world of their own, paying thirty-four seconds of provisioning
 * to run about two seconds of test.
 *
 * <p>What moving it here buys, beyond the arithmetic. A failure names the step
 * that failed instead of ending a script at line nine hundred. One step can be
 * run on its own. And a step is a Java test, so it can cite the promise it
 * demonstrates — which is the whole reason this migration comes before the
 * promises move.
 *
 * <p><b>It is ordered on purpose.</b> The chapters build on each other: a
 * patient is written before it is searched, a token is obtained before it is
 * presented. That is a property of the documentation rather than a weakness of
 * the test — the guide is arranged so nothing is used before it is explained,
 * and the suite follows the same line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheGuideRunsIT {

    private static final Path COMPOSE =
            Path.of("..", "docs", "guide", "examples", "compose.yaml");

    private final Snippets snippets = new Snippets();

    @BeforeAll
    void theWorldComesUp() throws Exception {
        compose("up", "-d");
        // Six databases from nothing, with a terminology baseline each. Minutes
        // on a cold start, and paid once for the whole chapter set rather than
        // once per assertion.
        for (int attempt = 0; attempt < 240; attempt++) {
            if (served("hogwarts") && served("gringotts")) {
                return;
            }
            TimeUnit.SECONDS.sleep(2);
        }
        compose("logs", "--tail", "40", "dbo");
        throw new IllegalStateException("the world never came up");
    }

    @AfterAll
    void theWorldGoesDown() throws Exception {
        compose("down", "-v", "--remove-orphans");
    }

    @Test
    @Order(1)
    @DisplayName("the bases a reader keeps, and the credentials the world requires")
    void aCredentialBecauseTheWorldIsGuarded() throws Exception {
        assertEquals(0, snippets.run("bases").status());
        assertEquals(0, snippets.run("token").status());
        assertTrue(snippets.recall("HOSPITAL").startsWith("ey"),
                "the hospital's credential is not a token");
        assertTrue(snippets.recall("HOGWARTS").endsWith("/t/hogwarts/fhir"));
    }

    @Test
    @Order(2)
    @DisplayName("and without one, the store says no")
    void withoutOneTheStoreSaysNo() throws Exception {
        assertEquals("401", snippets.run("no-token").lastLine(),
                "the store answered a request that carried no credential");
    }

    @Test
    @Order(3)
    @DisplayName("the zone publishes terminology of its own, and answers about it")
    void theZonePublishesTerminology() throws Exception {
        assertEquals(0, snippets.run("zone-publishes").status());
        assertTrue(snippets.run("zone-lookup").text().contains("Dai Llewellyn"),
                "the zone cannot look up its own code");
        assertTrue(snippets.run("zone-expand").text().startsWith("4 concepts"),
                "the value set did not expand to its four concepts");
    }

    @Test
    @Order(4)
    @DisplayName("each tenant says which version it speaks")
    void eachTenantSaysWhichVersionItSpeaks() throws Exception {
        // The insurer is a release behind on purpose, which is what makes
        // conversion and a version-named refusal things a reader can run.
        assertEquals("5.0.0\n4.0.1", snippets.run("versions").text());
    }

    @Test
    @Order(5)
    @DisplayName("admitting a patient")
    void admittingAPatient() throws Exception {
        Snippets.Ran ran = snippets.run("create");
        assertEquals(0, ran.status(), ran.err());
        // The snippet names it, so the runner has it: the id is not scraped
        // out of the output here and threaded on by hand.
        assertTrue(snippets.recall("id").matches("[0-9a-f-]{36}"),
                "the store did not assign a uuid: " + snippets.recall("id"));
    }

    @Test
    @Order(6)
    @DisplayName("a readable id is refused")
    void aReadableIdIsRefused() throws Exception {
        Snippets.Ran refused = snippets.run("readable-id");
        assertTrue(refused.text().contains("OperationOutcome"),
                "an id the caller chose was accepted: " + refused.text());
    }

    @Test
    @Order(7)
    @DisplayName("finding him by the identifier the zone declares")
    void findingHimByIdentifier() throws Exception {
        Snippets.Ran found = snippets.run("search");
        assertEquals(0, found.status(), found.err());
        assertTrue(found.text().contains("RL-0001"),
                "the patient was not found by the identifier the zone declares");
    }

    @Test
    @Order(8)
    @DisplayName("changing him, and reading what he was")
    void changingHimAndReadingWhatHeWas() throws Exception {
        assertEquals(0, snippets.run("history").status());
    }

    private static boolean served(String tenant) throws Exception {
        Process probe = new ProcessBuilder("curl", "-sf", "-o", "/dev/null",
                "http://localhost:8090/t/" + tenant + "/fhir/metadata").start();
        return probe.waitFor() == 0;
    }

    private static void compose(String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(
                java.util.List.of("docker", "compose", "-f", COMPOSE.toString()));
        command.addAll(java.util.List.of(arguments));
        new ProcessBuilder(command).inheritIO().start().waitFor();
    }
}
