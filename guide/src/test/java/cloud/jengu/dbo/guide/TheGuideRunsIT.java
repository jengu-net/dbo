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

    @Test
    @Order(18)
    @DisplayName("what belongs to a record is found by the reference, and one pointing out is kept")
    void referencesAreFoundAndKept() throws Exception {
        snippets.remember("bones", ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0009")
                .split("\"id\":\"")[1].split("\"")[0]);
        assertEquals(0, snippets.run("reference-search").status());
        assertEquals(1, entries(ask("HOSPITAL",
                        "/Observation?subject=Patient/" + snippets.recall("bones"))),
                "the reference did not find what belongs to the record");
        // A reference this store does not hold is kept rather than refused,
        // because a store that insisted otherwise would be claiming to be the
        // whole world.
        assertEquals("201", snippets.run("reference-unheld").lastLine());
    }

    // NOT PORTED: "a write made against a version that has moved is refused".
    //
    // check.sh still covers it, so nothing is uncovered — but it does not
    // belong here until it is understood. Run by hand, the sequence behaves
    // exactly as the chapter says: create gives W/"1", an update gives W/"2",
    // and a PUT carrying If-Match W/"1" is refused with 412. Run through this
    // class, with the record verifiably at W/"2" first, the same snippet
    // answers 200 twice.
    //
    // Six attempts went into that gap and three of them were spent on my own
    // mistakes rather than on the difference: an unchecked setup that PUT to
    // an empty id, an assertion written from a guess about the snippet's
    // output, and a guard I added and then deleted in a later edit of the same
    // block. The step is left out deliberately rather than papered over with
    // an assertion loose enough to pass, which is the failure this whole port
    // is most able to cause.

    @Test
    @Order(20)
    @DisplayName("a definition is identified by its url, so writing it twice replaces it")
    void aDefinitionIsIdentifiedByItsUrl() throws Exception {
        assertEquals("201\n201", snippets.run("canonical").text());
        String held = ask("JURISDICTION", "/CodeSystem?url=urn:rl:houses");
        assertEquals(1, entries(held), "two creates of one canonical left two records");
        assertTrue(held.contains("\"versionId\":\"2\""),
                "the second write did not replace the first: " + held);
    }

    @Test
    @Order(21)
    @DisplayName("a type the tenant never declared is refused")
    void anUndeclaredTypeIsRefused() throws Exception {
        // The hospital declared Observation and the insurer did not, so the
        // same request is answered differently by each.
        assertEquals("404", snippets.run("undeclared-type").lastLine());
    }

    /**
     * Asks the store something the chapter does not print.
     *
     * <p>Several steps prove a side effect rather than an answer — a
     * transaction landed its patient, a refused one left nothing behind — and
     * the question that establishes it is not part of the published command.
     * Keeping it here rather than in the snippet is the point: a chapter shows
     * what a reader would run, and the checking belongs to the test.
     */
    private String ask(String tenant, String pathAndQuery) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "curl", "-sf", "-G", "-H",
                "Authorization: Bearer " + snippets.recall(tenant)));
        int q = pathAndQuery.indexOf('?');
        if (q < 0) {
            command.add("http://localhost:8090/t/" + tenantOf(tenant) + "/fhir" + pathAndQuery);
        } else {
            command.add("http://localhost:8090/t/" + tenantOf(tenant) + "/fhir"
                    + pathAndQuery.substring(0, q));
            for (String pair : pathAndQuery.substring(q + 1).split("&")) {
                command.add("--data-urlencode");
                command.add(pair);
            }
        }
        Process curl = new ProcessBuilder(command).start();
        String body = new String(curl.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        curl.waitFor();
        return body;
    }

    /** The status alone, for the steps whose answer is a refusal. */
    private int status(String tenant, String path) throws Exception {
        Process curl = new ProcessBuilder("curl", "-s", "-o", "/dev/null",
                "-w", "%{http_code}", "-H",
                "Authorization: Bearer " + snippets.recall(tenant),
                "http://localhost:8090/t/" + tenantOf(tenant) + "/fhir" + path).start();
        String code = new String(curl.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
        curl.waitFor();
        return Integer.parseInt(code);
    }

    /** One response header, for the validator a version read carries. */
    private String header(String tenant, String path, String name) throws Exception {
        Process curl = new ProcessBuilder("curl", "-s", "-o", "/dev/null", "-D", "-", "-H",
                "Authorization: Bearer " + snippets.recall(tenant),
                "http://localhost:8090/t/" + tenantOf(tenant) + "/fhir" + path).start();
        String headers = new String(curl.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        curl.waitFor();
        return headers.lines()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith(name + ":"))
                .findFirst().orElse("");
    }

    private static String tenantOf(String credential) {
        return switch (credential) {
            case "HOSPITAL" -> "hogwarts";
            case "INSURER" -> "gringotts";
            case "JURISDICTION" -> "rl";
            default -> throw new IllegalArgumentException("no tenant for " + credential);
        };
    }

    /** How many entries a searchset carried, without pretending a bundle is typed. */
    private static int entries(String bundle) {
        return bundle.split("\"fullUrl\"", -1).length - 1;
    }

    @Test
    @Order(9)
    @DisplayName("the same person at the insurer, a release behind, and its refusal names the version")
    void theSamePersonAtTheInsurer() throws Exception {
        assertTrue(snippets.run("insurer").text().contains("RL-0001"),
                "the person is not at the insurer");
        String outcome = snippets.run("version-refusal").text();
        assertTrue(outcome.contains("OperationOutcome"), outcome);
        assertTrue(outcome.contains("4.0.1"),
                "a refusal must name the version it validated against: " + outcome);
    }

    @Test
    @Order(10)
    @DisplayName("a credential is for one tenant, and an id means nothing in another")
    void aCredentialIsForOneTenant() throws Exception {
        // 401 then 404: not a valid credential refused, but no credential —
        // and then an id that simply is not a thing here.
        assertEquals("401\n404", snippets.run("isolation").text());
    }

    @Test
    @Order(11)
    @DisplayName("the same person twice is refused, not duplicated")
    void theSamePersonTwiceIsRefused() throws Exception {
        assertEquals("409", snippets.run("duplicate-identity").lastLine());
        assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                "one identity ended up with more than one record");
    }

    @Test
    @Order(12)
    @DisplayName("updating by identity rather than by id adds nothing")
    void updatingByIdentity() throws Exception {
        assertEquals("200", snippets.run("conditional-update").lastLine());
        assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                "a conditional update added a record instead of changing one");
    }

    @Test
    @Order(13)
    @DisplayName("reading one version by number, and the validator it carries")
    void readingOneVersionByNumber() throws Exception {
        String first = snippets.run("vread").text();
        assertTrue(first.contains("\"given\":[\"Harry\"]"),
                "version one did not come back as it was written: " + first);
        assertTrue(first.contains("\"versionId\":\"1\""),
                "the document does not say which version it is: " + first);
        // Both of these were in the script and are easy to lose in a port:
        // a version that never existed, and the validator a version read
        // carries. A port that quietly drops them is how cover evaporates.
        assertEquals(404, status("HOSPITAL", "/Patient/" + snippets.recall("id") + "/_history/99"),
                "a version that never existed did not answer 404");
        assertTrue(header("HOSPITAL", "/Patient/" + snippets.recall("id") + "/_history/1", "etag")
                        .contains("W/\"1\""),
                "a version read must carry THAT version's validator");
    }

    @Test
    @Order(14)
    @DisplayName("a type declared replicated is not writable here, and the refusal names the rule")
    void aReplicatedTypeIsNotWritableHere() throws Exception {
        String refused = snippets.run("replicated-refused").text();
        assertTrue(refused.contains("read-only-here"),
                "the refusal should name the rule: " + refused);
    }

    @Test
    @Order(15)
    @DisplayName("several writes as one act, and the reference between them resolved")
    void severalWritesAsOneAct() throws Exception {
        assertEquals(0, snippets.run("transaction").status());
        String landed = ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0009");
        assertEquals(1, entries(landed), "the transaction did not land its patient");

        assertEquals(0, snippets.run("transaction-reference").status());
        assertTrue(ask("HOSPITAL", "/Observation?_count=50").contains("height"),
                "the observation the transaction wrote is not there");
    }

    @Test
    @Order(16)
    @DisplayName("one bad entry takes the whole transaction with it")
    void oneBadEntryTakesTheWholeTransaction() throws Exception {
        snippets.run("transaction-refused");
        assertEquals(0, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0010")),
                "a refused transaction left a record behind");
    }

    @Test
    @Order(17)
    @DisplayName("a batch answers for each entry separately")
    void aBatchAnswersForEachEntry() throws Exception {
        snippets.run("batch");
        assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0011")),
                "a batch's good entry did not land beside its bad one");
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
