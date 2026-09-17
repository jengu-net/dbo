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
        // Two steps add a tenant by writing its spec into the world and remove
        // it by deleting the file again. If one of them fails in between, the
        // file is left in a checked-in directory — so the teardown takes it
        // away whatever happened, rather than leaving the repository dirty for
        // whoever runs next.
        java.nio.file.Files.deleteIfExists(
                Path.of("..", "docs", "guide", "world", "tenants", "stmungos.json"));
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


    @Test
    @Order(22)
    @DisplayName("a tenant appears when its spec does")
    void aTenantAppearsWhenItsSpecDoes() throws Exception {
        assertEquals(0, snippets.run("add-tenant").status(), "the spec was not written");
        assertTrue(waitUntilServed("stmungos", 60), "stmungos never came up");
        assertEquals("200", snippets.run("new-tenant-serves").lastLine());
    }

    @Test
    @Order(23)
    @DisplayName("changing the declaration rebuilds the tenant where it stands")
    void changingTheDeclarationRebuildsInPlace() throws Exception {
        assertEquals(0, snippets.run("change-in-place").status(), "the spec was not narrowed");
        // A rebuild is not instant and it is not a restart either: the tenant
        // keeps answering throughout, so the only way to see it land is to
        // watch what it says it holds.
        for (int attempt = 0; attempt < 60; attempt++) {
            if ("True False".equals(snippets.run("change-took").text())) {
                break;
            }
            TimeUnit.SECONDS.sleep(3);
        }
        assertEquals("True False", snippets.run("change-took").text(),
                "the narrowed declaration did not take where it stood");
    }

    @Test
    @Order(24)
    @DisplayName("and stops when its spec goes")
    void andStopsWhenItsSpecGoes() throws Exception {
        assertEquals(0, snippets.run("remove-tenant").status());
        for (int attempt = 0; attempt < 40; attempt++) {
            if (!served("stmungos")) {
                break;
            }
            TimeUnit.SECONDS.sleep(2);
        }
        assertTrue(!served("stmungos"), "stmungos was still served after its spec was removed");
    }

    @Test
    @Order(25)
    @DisplayName("the hospital still has its own records")
    void theHospitalStillHasItsOwnRecords() throws Exception {
        // A neighbour arriving and leaving is the loudest thing that happens to
        // this world, and the tenant beside it should not have noticed.
        assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                "the hospital lost a record when a neighbour went away");
    }

    @Test
    @Order(26)
    @DisplayName("what this tenant says it can be asked")
    void whatThisTenantSaysItCanBeAsked() throws Exception {
        String declared = snippets.run("capability-search").text();
        assertTrue(declared.lines().count() > 10,
                "the capability statement declares too few Patient parameters: " + declared);
        assertTrue(declared.lines().anyMatch("family"::equals),
                "a parameter the guide goes on to use is not declared: " + declared);
    }

    @Test
    @Order(27)
    @DisplayName("a modifier the parameter does not have is refused by name")
    void anUnknownModifierIsRefusedByName() throws Exception {
        String refused = snippets.run("unknown-modifier").text();
        assertTrue(refused.contains("family:nosuch"),
                "the refusal should name what it refused: " + refused);
    }

    @Test
    @Order(28)
    @DisplayName("counting without fetching")
    void countingWithoutFetching() throws Exception {
        String counted = snippets.run("count").text();
        assertTrue(counted.contains("\"total\""), "a count answered without a total: " + counted);
        assertEquals(0, entries(counted), "a count carried the records it was asked not to fetch");
    }

    @Test
    @Order(29)
    @DisplayName("a page, and the cursor that follows it")
    void aPageAndTheCursorThatFollowsIt() throws Exception {
        for (String family : java.util.List.of(
                "Granger", "Longbottom", "Lovegood", "Malfoy", "Diggory")) {
            Snippets.Ran made = snippets.sh("""
                    curl -sf -o /dev/null -X POST -H "Authorization: Bearer $HOSPITAL" \
                        "$HOGWARTS/Patient" -H 'Content-Type: application/fhir+json' \
                        -d '{"resourceType":"Patient",
                             "identifier":[{"system":"urn:rl:nid","value":"RL-90-%s"}],
                             "name":[{"family":"%s"}]}'
                    """.formatted(family, family));
            assertEquals(0, made.status(), "could not create " + family + ": " + made.err());
        }

        String page = snippets.run("first-page").text();
        assertEquals(2, entries(page), "a page of two did not carry two: " + page);
        // The url follows the relation inside each link, so the text AFTER the
        // marker is the one wanted. Reading backwards from it found the self
        // link instead — which still looked like a plausible url, and said so
        // by carrying no cursor.
        String next = page.split("\"relation\":\"next\"", 2)[1];
        next = next.substring(next.indexOf("\"url\":\"") + 7);
        next = next.substring(0, next.indexOf('"'));
        assertTrue(next.contains("_cursor="), "the next link carries no cursor: " + next);
        // The following snippet reads it by name, the way the chapter's prose
        // says to: the link the page handed you, not one you construct.
        snippets.remember("next", next);
        snippets.remember("first", idsIn(page));
    }

    @Test
    @Order(30)
    @DisplayName("a write lands between the pages, and the next page does not repeat")
    void aWriteLandsBetweenThePages() throws Exception {
        Snippets.Ran between = snippets.sh("""
                curl -sf -o /dev/null -X POST -H "Authorization: Bearer $HOSPITAL" \
                    "$HOGWARTS/Patient" -H 'Content-Type: application/fhir+json' \
                    -d '{"resourceType":"Patient",
                         "identifier":[{"system":"urn:rl:nid","value":"RL-90-Between"}],
                         "name":[{"family":"Aaaa"}]}'
                """);
        assertEquals(0, between.status(),
                "the write that lands between the pages did not: " + between.err());

        String page = snippets.run("next-page").text();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>(
                java.util.List.of(snippets.recall("first").split(" ")));
        seen.retainAll(java.util.List.of(idsIn(page).split(" ")));
        assertTrue(seen.isEmpty(),
                "a page repeated what the previous page already returned: " + seen);
    }

    @Test
    @Order(31)
    @DisplayName("an unsupported search parameter is refused, not ignored")
    void anUnsupportedSearchParameterIsRefused() throws Exception {
        assertEquals("400", snippets.run("strict-search").lastLine());
    }


    /** The ids a searchset carried, in the order it carried them. */
    private static String idsIn(String bundle) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String entry : bundle.split("\"fullUrl\"")) {
            int at = entry.indexOf("\"id\":\"");
            if (at >= 0) {
                ids.add(entry.substring(at + 6, entry.indexOf('"', at + 6)));
            }
        }
        return String.join(" ", ids);
    }

    private boolean waitUntilServed(String tenant, int attempts) throws Exception {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (served(tenant)) {
                return true;
            }
            TimeUnit.SECONDS.sleep(5);
        }
        return false;
    }


    @Test
    @Order(32)
    @DisplayName("an element the face does not define is refused, not quietly kept")
    void anUndefinedElementIsRefused() throws Exception {
        String refused = snippets.run("unknown-element").text();
        assertTrue(refused.contains("favouriteColour"),
                "the refusal should name the element: " + refused);
        assertTrue(refused.contains("extension"),
                "the refusal should point at the sanctioned way to carry it: " + refused);
        assertEquals(422, postCode("HOSPITAL", "/Patient",
                """
                {"resourceType":"Patient",
                 "identifier":[{"system":"urn:rl:nid","value":"RL-0006"}],
                 "favouriteColour":"blue"}"""));
        assertEquals(0, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0006")),
                "a refused write left a record behind");
    }

    @Test
    @Order(33)
    @DisplayName("a code outside a required binding is refused, and the refusal names the element")
    void aCodeOutsideARequiredBindingIsRefused() throws Exception {
        String refused = snippets.run("validate-binding").text();
        assertEquals("422", refused.lines().findFirst().orElse(""),
                "a code outside a required binding was not refused: " + refused);
        assertTrue(refused.contains("Patient.gender"),
                "the refusal should name the element: " + refused);
        assertEquals(0, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0002")),
                "a refused write left a record behind");
    }

    @Test
    @Order(34)
    @DisplayName("a malformed value and a missing required element are refused the same way")
    void aMalformedValueAndAMissingElementAreRefusedTheSameWay() throws Exception {
        // One is a value that cannot be read as what it claims to be and the
        // other is an absence, and the point of the step is that the store does
        // not grade them differently.
        assertEquals("422\n422", snippets.run("validate-shape").text());
    }

    @Test
    @Order(35)
    @DisplayName("the same mistake at both faces, each naming the version it validated against")
    void theSameMistakeAtBothFaces() throws Exception {
        assertEquals("administrative-gender|5.0.0\nadministrative-gender|4.0.1",
                snippets.run("validate-versions").text(),
                "each face should validate against its own release");
    }

    @Test
    @Order(36)
    @DisplayName("asking for the verdict without writing")
    void askingForTheVerdictWithoutWriting() throws Exception {
        String verdict = snippets.run("validate-ahead").text();
        assertTrue(verdict.contains("Observation.status"),
                "the verdict should name the missing element: " + verdict);
        assertTrue(verdict.contains("warning"),
                "the verdict should carry advice as well as errors: " + verdict);
        // A verdict was reached, which is what the operation was asked for —
        // so it answers 200 even though the verdict is that this would fail.
        assertEquals(200, postCode("HOSPITAL", "/Observation/$validate",
                """
                {"resourceType":"Observation","code":{"text":"house points"}}"""));
    }

    @Test
    @Order(37)
    @DisplayName("the hospital declared the zone, so it answers the zone's codes as its own")
    void theZonesCodesReachTheHospital() throws Exception {
        // The first sync from a zone runs some minutes after a tenant comes up;
        // once the stream is running a change propagates in a second or two.
        // The wait is for the first one, and it is the reason this step sits
        // where it does rather than beside the zone's own chapter.
        assertTrue(waitFor(120, () ->
                        entries(ask("HOSPITAL", "/CodeSystem?url=urn:rl:wards")) == 1),
                "the zone's terminology never reached the hospital");
        assertTrue(snippets.run("zone-reaches-hospital").text().contains("Spell Damage"),
                "the hospital cannot answer a code it holds from its zone");
    }

    @Test
    @Order(38)
    @DisplayName("the insurer declared the code systems and not the value sets, and that is what it has")
    void theInsurerTookOnlyWhatItDeclared() throws Exception {
        // The insurer's copy travels further than the hospital's: the zone
        // speaks R5 and the insurer R4, so it arrives through the projection.
        // Waiting for the hospital was not waiting for this.
        assertTrue(waitFor(120, () ->
                        entries(ask("INSURER", "/CodeSystem?url=urn:rl:wards")) == 1),
                "the zone's terminology never reached the insurer");
        String held = snippets.run("zone-partial-at-insurer").text();
        assertTrue(held.contains("Spell Damage"),
                "the insurer declared CodeSystem from the zone and did not get it: " + held);
        assertTrue(held.contains("0 value sets"),
                "the insurer took a type it never declared: " + held);
    }

    @Test
    @Order(39)
    @DisplayName("and the standard's own terminology is there by the same mechanism")
    void theStandardsTerminologyIsThereTheSameWay() throws Exception {
        assertTrue(snippets.run("core-terminology").text().contains("Female"),
                "the core code system is not answerable");
    }

    @Test
    @Order(40)
    @DisplayName("a face root is a tenant, and its definitions are records")
    void aFaceRootIsATenant() throws Exception {
        snippets.remember("FACE_R5", credentialFor("fhir-r5", "r5-secret"));
        snippets.remember("FACE_R4", credentialFor("fhir-r4", "r4-secret"));
        String held = snippets.run("face-roots").text();
        for (String line : held.lines().toList()) {
            String[] said = line.split(" ");
            assertTrue(Integer.parseInt(said[1]) > 300,
                    said[0] + " should hold the version's definitions, got " + held);
        }
        assertEquals(2, held.lines().count(), "both face roots should answer: " + held);
    }

    @Test
    @Order(41)
    @DisplayName("the zone runs the ceremony its members federate to")
    void theZoneRunsItsOwnCeremony() throws Exception {
        assertEquals("200", snippets.run("zone-ceremony").lastLine());
    }

    @Test
    @Order(42)
    @DisplayName("a projection converts the zone once, for the face that needs it")
    void aProjectionConvertsTheZoneOnce() throws Exception {
        assertTrue(waitUntilServed("rl-on-r4", 90), "the projection never came up");
        assertEquals("4.0.1\n5.0.0", snippets.run("projection").text(),
                "the projection should speak the face it serves while the zone keeps its own");
    }

    @Test
    @Order(43)
    @DisplayName("the version that deleted a record is gone, not missing")
    void theVersionThatDeletedARecordIsGone() throws Exception {
        // Three answers: what the delete said, then the version that performed
        // it, then the one before. 410 rather than 404 is the whole point —
        // the store knows what was there and will not serve it.
        String answers = snippets.run("vread-gone").text();
        java.util.List<String> said = answers.lines().toList();
        assertEquals(3, said.size(), "the snippet did not answer three times: " + answers);
        assertEquals("410", said.get(1),
                "the version that deleted it should be gone, not missing: " + answers);
        assertEquals("200", said.get(2),
                "deleting took the history with it: " + answers);
    }


    /** Posts a body and reports only the status, for the steps about refusals. */
    private int postCode(String tenant, String path, String body) throws Exception {
        Process curl = new ProcessBuilder("curl", "-s", "-o", "/dev/null",
                "-w", "%{http_code}", "-X", "POST", "-H",
                "Authorization: Bearer " + snippets.recall(tenant),
                "-H", "Content-Type: application/fhir+json", "-d", body,
                "http://localhost:8090/t/" + tenantOf(tenant) + "/fhir" + path).start();
        String code = new String(curl.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
        curl.waitFor();
        return Integer.parseInt(code);
    }

    /**
     * A credential for a tenant the chapters do not hand one out for.
     *
     * <p>It sources the published token snippet and calls the function that
     * snippet defines, rather than writing the request again here: a second
     * spelling of the token exchange is a second thing to keep true.
     */
    private String credentialFor(String tenant, String secret) throws Exception {
        Snippets.Ran got = snippets.sh(
                "source docs/guide/examples/snippets/token.sh; token " + tenant + " " + secret);
        assertEquals(0, got.status(), "no credential for " + tenant + ": " + got.err());
        return got.lastLine();
    }

    /** Polls a condition the guide waits on, rather than sleeping a guessed amount. */
    private boolean waitFor(int attempts, Waiting condition) throws Exception {
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                if (condition.met()) {
                    return true;
                }
            } catch (RuntimeException notYet) {
                // A tenant still building answers in shapes this cannot read.
            }
            TimeUnit.SECONDS.sleep(5);
        }
        return false;
    }

    @FunctionalInterface
    private interface Waiting {
        boolean met() throws Exception;
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
