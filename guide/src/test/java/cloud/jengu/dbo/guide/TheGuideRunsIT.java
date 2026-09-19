package cloud.jengu.dbo.guide;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestClassOrder;
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
 *
 * <p><b>The stories are the unit.</b> Each nested class is one, and its
 * paragraph says what it is about, what state it leaves for the stories after
 * it, and what kind of promise belongs in it. That last part is what the
 * grouping is for: a promise coming out of a test that built a world of its
 * own has to land somewhere, and a flat list of steps gives no answer.
 *
 * <p>Two rules this world imposes, both learnt by breaking them.
 *
 * <p><b>A step reads the state it depends on; it does not count the writes
 * above it.</b> Harry is on his third version by the time the version story
 * reads him, because stories in between moved him on. A step that hard-codes
 * a number is a step that breaks when a story it never heard of gains a write.
 *
 * <p><b>A step belongs in the story that creates what it reads.</b> Grouping
 * by subject rather than by dependency put the reference step before the
 * transaction that writes the record it looks for.
 *
 * <p>What earns a citation is worth stating too, because the cheap version of
 * this migration is annotating what is already here. A step that acknowledges
 * an answer does not prove a promise about what comes back afterwards — so the
 * verification joins the action rather than becoming a story of its own, which
 * is also what keeps one world enough.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class TheGuideRunsIT {

    /** Written into the working directory by the archive chapter, and taken away again. */
    private static final Path ARCHIVE = Path.of("..", "hogwarts.archive");

    /**
     * The world to run against — the published one, or a tree-built one.
     *
     * <p>The published compose file names the PINNED image, which is what
     * makes this suite a guard against the guide rotting. It also means a step
     * asserting behaviour newer than the pin fails for a reason that has
     * nothing to do with the step, which is the whole difficulty of moving a
     * test here from the harness.
     *
     * <p>So the same door check.sh has: {@code DBO_GUIDE_COMPOSE} names a
     * world built from this tree instead. Unset, nothing changes.
     */
    private static final Path COMPOSE = Path.of(
            System.getenv().getOrDefault("DBO_GUIDE_COMPOSE",
                    Path.of("..", "docs", "guide", "examples", "compose.yaml").toString()));

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
        java.nio.file.Files.deleteIfExists(ARCHIVE);
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


    /** The id of the one record a search was expected to match. */
    private static String onlyIdIn(String bundle) {
        String ids = idsIn(bundle);
        if (ids.isBlank() || ids.contains(" ")) {
            throw new IllegalStateException(
                    "expected exactly one record, and the search matched: '" + ids + "'");
        }
        return ids;
    }

    /** What follows a marker, up to the quote that ends it. */
    private static String after(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            throw new IllegalStateException("nothing said " + marker + " in: " + text);
        }
        String rest = text.substring(at + marker.length());
        return rest.substring(0, rest.indexOf('"'));
    }


    /**
     * Where every other story starts: the addresses a reader keeps, the
     * credentials this world requires, and the refusal that comes without one.
     *
     * <p>It leaves behind the tokens the rest of the suite acts with — the
     * hospital's, the insurer's, the zone's — so a promise about what a
     * credential IS belongs here, and a promise about what one may DO belongs
     * wherever that doing happens.
     */
    @Nested
    @Order(1)
    @DisplayName("getting in")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GettingIn {

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
        @Proving(DboPromises.TERM_EVERY_TENANT_ANSWERS)
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

    }

    /**
     * One person, written once and then lived with: found by the identifier
     * their jurisdiction declares, changed, read back at a version, and pointed
     * at by something else.
     *
     * <p>This is the store's object engine seen from outside, and it is the
     * story most new promises about writing, reading, identity and history
     * belong in. It leaves Harry behind under {@code id}, which later stories
     * read rather than re-create.
     */
    @Nested
    @Order(2)
    @DisplayName("a patient, admitted and changed")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class APatientAdmittedAndChanged {

        @Test
        @Order(1)
        @DisplayName("admitting a patient, and reading back what was written")
        @Proving({DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_READ_YOUR_WRITES})
        void admittingAPatient() throws Exception {
            Snippets.Ran ran = snippets.run("create");
            assertEquals(0, ran.status(), ran.err());
            // The snippet names it, so the runner has it: the id is not scraped
            // out of the output here and threaded on by hand.
            assertTrue(snippets.recall("id").matches("[0-9a-f-]{36}"),
                    "the store did not assign a uuid: " + snippets.recall("id"));

            // The chapter stops at the acknowledgement; the promise is about
            // what comes back afterwards. A store that reassembled the record
            // from columns would answer this read with something plausible and
            // not with what was written, so the read is part of the same step
            // rather than a story of its own.
            String held = ask("HOSPITAL", "/Patient/" + snippets.recall("id"));
            assertTrue(held.contains("\"given\":[\"Harry\"]"),
                    "the name did not come back as it was written: " + held);
            assertTrue(held.contains("\"system\":\"urn:rl:nid\",\"value\":\"RL-0001\""),
                    "the identifier did not come back as one object: " + held);
            // NOT element order, and the reason is worth keeping: this tenant
            // is behind the membrane, so the identifying elements are sealed
            // out of the payload and put back on the way out. Their order is
            // not the author's to keep, and an assertion on it passed on one
            // run and failed on the next before that was understood.
            //
            // What the promise does hold to is that nothing was added and
            // nothing silently dropped.
            assertTrue(!held.contains("\"text\""),
                    "the store added a narrative the author did not write: " + held);
            assertEquals(2, held.split("\"system\"", -1).length - 1,
                    "the record came back with more or fewer systems than were written: "
                            + held);
        }

        @Test
        @Order(2)
        @DisplayName("a readable id is refused")
        void aReadableIdIsRefused() throws Exception {
            Snippets.Ran refused = snippets.run("readable-id");
            assertTrue(refused.text().contains("OperationOutcome"),
                    "an id the caller chose was accepted: " + refused.text());
        }

        @Test
        @Order(3)
        @DisplayName("finding him by the identifier the zone declares")
        @Proving(DboPromises.CORE_EXTERNAL_IDENTIFIERS)
        void findingHimByIdentifier() throws Exception {
            Snippets.Ran found = snippets.run("search");
            assertEquals(0, found.status(), found.err());
            assertTrue(found.text().contains("RL-0001"),
                    "the patient was not found by the identifier the zone declares");
        }

        @Test
        @Order(4)
        @DisplayName("changing him, and reading what he was")
        @Proving(DboPromises.CORE_VERSIONED_HISTORY)
        void changingHimAndReadingWhatHeWas() throws Exception {
            assertEquals(0, snippets.run("history").status());
        }

        @Test
        @Order(5)
        @DisplayName("the same person twice is refused, not duplicated")
        @Proving({DboPromises.CORE_NO_IMPLICIT_MERGE,
                DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS})
        void theSamePersonTwiceIsRefused() throws Exception {
            assertEquals("409", snippets.run("duplicate-identity").lastLine());
            assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                    "one identity ended up with more than one record");
        }

        @Test
        @Order(6)
        @DisplayName("updating by identity rather than by id adds nothing")
        @Proving({DboPromises.CORE_CONDITIONAL_UPSERT,
                DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS})
        void updatingByIdentity() throws Exception {
            assertEquals("200", snippets.run("conditional-update").lastLine());
            assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                    "a conditional update added a record instead of changing one");
        }

        @Test
        @Order(7)
        @DisplayName("reading one version by number, and the validator it carries")
        @Proving(DboPromises.CORE_VERSIONED_HISTORY)
        void readingOneVersionByNumber() throws Exception {
            // Advertised before it is used: a surface that answers a version
            // read while its statement denies it is a client's problem to
            // discover at runtime.
            assertTrue(ask("HOSPITAL", "/metadata").contains("\"vread\""),
                    "the statement does not advertise the version read it answers");
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

            // The current version is a version like any other, so a client
            // holding a number never has to decide whether to drop it. Which
            // number that is comes from the record rather than from counting
            // the writes above it — earlier stories move Harry on, and a test
            // that assumed a number would break when one of them changed.
            String current = ask("HOSPITAL", "/Patient/" + snippets.recall("id"));
            String at = after(current, "\"versionId\":\"");
            assertEquals(current,
                    ask("HOSPITAL", "/Patient/" + snippets.recall("id") + "/_history/" + at),
                    "the current version read differently by number than by id");
            // And a version that is not a number is not found rather than a
            // fault: it is a caller's mistake about an address.
            assertEquals(404,
                    status("HOSPITAL", "/Patient/" + snippets.recall("id") + "/_history/two"),
                    "a version that is not a number was not answered as not found");
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

    }

    /**
     * The same human being, held by a second tenant that speaks an older
     * release — and the things that do not carry across: an id, a credential, a
     * type this tenant only mirrors.
     *
     * <p>Anything about isolation between tenants, or about two faces
     * disagreeing on purpose, belongs here rather than in the story above.
     */
    @Nested
    @Order(3)
    @DisplayName("the same person at another tenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TheSamePersonAtAnotherTenant {

        @Test
        @Order(1)
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
        @Order(2)
        @DisplayName("a credential is for one tenant, and an id means nothing in another")
        void aCredentialIsForOneTenant() throws Exception {
            // 401 then 404: not a valid credential refused, but no credential —
            // and then an id that simply is not a thing here.
            assertEquals("401\n404", snippets.run("isolation").text());
        }

        @Test
        @Order(3)
        @DisplayName("a type declared replicated is not writable here, and the refusal names the rule")
        @Proving(DboPromises.SYNC_PROVENANCE_COPIES)
        void aReplicatedTypeIsNotWritableHere() throws Exception {
            String refused = snippets.run("replicated-refused").text();
            assertTrue(refused.contains("read-only-here"),
                    "the refusal should name the rule: " + refused);
        }

    }

    /**
     * A transaction that lands or does not, a batch that answers per entry, a
     * canonical that replaces itself, and a type this tenant never declared.
     *
     * <p>The promises here are about the unit of work rather than about any one
     * record.
     */
    @Nested
    @Order(4)
    @DisplayName("several writes as one act")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SeveralWritesAsOneAct {

        @Test
        @Order(1)
        @DisplayName("several writes as one act, and the reference between them resolved")
        @Proving({DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE,
                DboPromises.CORE_CONDITIONAL_REFERENCES})
        void severalWritesAsOneAct() throws Exception {
            assertEquals(0, snippets.run("transaction").status());
            String landed = ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0009");
            assertEquals(1, entries(landed), "the transaction did not land its patient");

            assertEquals(0, snippets.run("transaction-reference").status());
            assertTrue(ask("HOSPITAL", "/Observation?_count=50").contains("height"),
                    "the observation the transaction wrote is not there");
        }

        @Test
        @Order(2)
        @DisplayName("one bad entry takes the whole transaction with it")
        @Proving(DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE)
        void oneBadEntryTakesTheWholeTransaction() throws Exception {
            snippets.run("transaction-refused");
            assertEquals(0, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0010")),
                    "a refused transaction left a record behind");
        }

        @Test
        @Order(3)
        @DisplayName("a batch answers for each entry separately")
        @Proving(DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY)
        void aBatchAnswersForEachEntry() throws Exception {
            snippets.run("batch");
            assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0011")),
                    "a batch's good entry did not land beside its bad one");
        }

        // It reads the patient the transaction above wrote, which is why it
        // sits in this story rather than beside the other reads: grouped with
        // the other reads it ran before its own precondition existed, and the
        // ordering said so rather than passing on a record somebody else left.
        @Test
        @Order(4)
        @DisplayName("what belongs to a record is found by the reference, and one pointing out is kept")
        @Proving(DboPromises.CORE_REFERENCE_EDGES)
        void referencesAreFoundAndKept() throws Exception {
            snippets.remember("bones",
                    onlyIdIn(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0009")));
            assertEquals(0, snippets.run("reference-search").status());
            assertEquals(1, entries(ask("HOSPITAL",
                            "/Observation?subject=Patient/" + snippets.recall("bones"))),
                    "the reference did not find what belongs to the record");
            // A reference this store does not hold is kept rather than refused,
            // because a store that insisted otherwise would be claiming to be the
            // whole world.
            assertEquals("201", snippets.run("reference-unheld").lastLine());
        }

        @Test
        @Order(5)
        @DisplayName("a definition is identified by its url, so writing it twice replaces it")
        void aDefinitionIsIdentifiedByItsUrl() throws Exception {
            assertEquals("201\n201", snippets.run("canonical").text());
            String held = ask("JURISDICTION", "/CodeSystem?url=urn:rl:houses");
            assertEquals(1, entries(held), "two creates of one canonical left two records");
            assertTrue(held.contains("\"versionId\":\"2\""),
                    "the second write did not replace the first: " + held);
        }

        @Test
        @Order(6)
        @DisplayName("a type the tenant never declared is refused")
        void anUndeclaredTypeIsRefused() throws Exception {
            // The hospital declared Observation and the insurer did not, so the
            // same request is answered differently by each.
            assertEquals("404", snippets.run("undeclared-type").lastLine());
        }

    }

    /**
     * A tenant appears because a spec file did, narrows while it stands, and
     * stops when the file goes — with the tenant beside it untouched throughout.
     *
     * <p>This story is the only one that changes the world's shape, so it cleans
     * up after itself and the step that follows checks the neighbour. A promise
     * about provisioning, rebuilds or isolation-under-change belongs here.
     */
    @Nested
    @Order(5)
    @DisplayName("a tenant's life, while its neighbour keeps working")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ATenantsLife {

        @Test
        @Order(1)
        @DisplayName("a tenant appears when its spec does")
        void aTenantAppearsWhenItsSpecDoes() throws Exception {
            assertEquals(0, snippets.run("add-tenant").status(), "the spec was not written");
            assertTrue(waitUntilServed("stmungos", 60), "stmungos never came up");
            assertEquals("200", snippets.run("new-tenant-serves").lastLine());
        }

        @Test
        @Order(2)
        @DisplayName("changing the declaration rebuilds the tenant where it stands")
        @Proving(DboPromises.TEN_A_CHANGE_IS_NOT_A_RETRACTION)
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
        @Order(3)
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
        @Order(4)
        @DisplayName("the hospital still has its own records")
        void theHospitalStillHasItsOwnRecords() throws Exception {
            // A neighbour arriving and leaving is the loudest thing that happens to
            // this world, and the tenant beside it should not have noticed.
            assertEquals(1, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001")),
                    "the hospital lost a record when a neighbour went away");
        }

    }

    /**
     * What the capability statement declares, and then search itself: a
     * refusal that names the modifier it did not have, a count that fetches
     * nothing, and a page whose cursor survives a write landing behind it.
     *
     * <p>It writes five more patients, which every later count in this suite is
     * downstream of. Search promises belong here.
     */
    @Nested
    @Order(6)
    @DisplayName("asking a tenant what it can be asked, and then asking it")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AskingTheStore {

        @Test
        @Order(1)
        @DisplayName("what this tenant says it can be asked")
        @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
        void whatThisTenantSaysItCanBeAsked() throws Exception {
            String declared = snippets.run("capability-search").text();
            assertTrue(declared.lines().count() > 10,
                    "the capability statement declares too few Patient parameters: " + declared);
            assertTrue(declared.lines().anyMatch("family"::equals),
                    "a parameter the guide goes on to use is not declared: " + declared);
        }

        @Test
        @Order(2)
        @DisplayName("a modifier the parameter does not have is refused by name")
        @Proving(DboPromises.SRCH_STRICT_BY_DEFAULT)
        void anUnknownModifierIsRefusedByName() throws Exception {
            String refused = snippets.run("unknown-modifier").text();
            assertTrue(refused.contains("family:nosuch"),
                    "the refusal should name what it refused: " + refused);
        }

        @Test
        @Order(3)
        @DisplayName("counting without fetching")
        void countingWithoutFetching() throws Exception {
            String counted = snippets.run("count").text();
            assertTrue(counted.contains("\"total\""), "a count answered without a total: " + counted);
            assertEquals(0, entries(counted), "a count carried the records it was asked not to fetch");
        }

        @Test
        @Order(4)
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
        @Order(5)
        @DisplayName("a write lands between the pages, and the next page does not repeat")
        @Proving(DboPromises.FEED_KEYSET_CURSORS)
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
        @Order(6)
        @DisplayName("an unsupported search parameter is refused, not ignored")
        @Proving(DboPromises.SRCH_STRICT_BY_DEFAULT)
        void anUnsupportedSearchParameterIsRefused() throws Exception {
            assertEquals("400", snippets.run("strict-search").lastLine());
        }

    }

    /**
     * Four refusals and a verdict: an element the face does not define, a code
     * outside a required binding, a malformed value, a missing required element,
     * and the same question asked without writing.
     *
     * <p>Validation promises belong here. So does anything about a refusal
     * naming what was wrong — which is the property that separates this store's
     * answer from a 400.
     */
    @Nested
    @Order(7)
    @DisplayName("what the store will not store")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WhatTheStoreWillNotStore {

        @Test
        @Order(1)
        @DisplayName("an element the face does not define is refused, not quietly kept")
        @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
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

        // Not a published command: the chapter makes its point with one
        // invented element, and the promise is that the rule is the parser's
        // rather than a list of known field names. Proving that needs a second
        // type and a nested position, which would be repetition in prose and
        // is the whole of the claim in a test.
        @Test
        @Order(2)
        @DisplayName("nested, and on another type, because the rule is the parser's rather "
                + "than a list of field names")
        @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
        void theRuleIsTheParsersRatherThanAList() throws Exception {
            assertEquals(422, postCode("HOSPITAL", "/Observation",
                    """
                    {"resourceType":"Observation","status":"final",
                     "code":{"text":"house points"},"housePoints":50}"""),
                    "an invented element on another type was accepted");

            String nested = snippets.sh("""
                    curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
                        -H 'Content-Type: application/fhir+json' \
                        -d '{"resourceType":"Patient",
                             "identifier":[{"system":"urn:rl:nid","value":"RL-0016"}],
                             "name":[{"family":"Ambrose","housePoints":50}]}'
                    """).text();
            assertTrue(nested.contains("housePoints"),
                    "an invented element inside a nested element was accepted, or the "
                            + "refusal did not name it: " + nested);
        }

        // The other half of the same rule, and the reason it is not a rule
        // about extra data: FHIR has a way to carry what a resource does not
        // define, and refusing that too would be a different promise.
        @Test
        @Order(3)
        @DisplayName("and the sanctioned way to carry the same fact is accepted")
        @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
        void anExtensionCarriesItInstead() throws Exception {
            String accepted = snippets.sh("""
                    curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
                        -H 'Content-Type: application/fhir+json' \
                        -d '{"resourceType":"Patient",
                             "identifier":[{"system":"urn:rl:nid","value":"RL-0017"}],
                             "extension":[{"url":"https://hogwarts.example/colour",
                                           "valueString":"blue"}]}'
                    """).text();
            assertTrue(accepted.contains("hogwarts.example/colour"),
                    "an extension is how FHIR carries what a resource does not define, and "
                            + "refusing it would make this a rule about extra data: " + accepted);
        }

        @Test
        @Order(4)
        @DisplayName("a code outside a required binding is refused, and the refusal names the element")
        @Proving(DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER)
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
        @Order(5)
        @DisplayName("a malformed value and a missing required element are refused the same way")
        @Proving(DboPromises.VER_SPECIFIED_VALIDATION)
        void aMalformedValueAndAMissingElementAreRefusedTheSameWay() throws Exception {
            // One is a value that cannot be read as what it claims to be and the
            // other is an absence, and the point of the step is that the store does
            // not grade them differently.
            assertEquals("422\n422", snippets.run("validate-shape").text());
        }

        @Test
        @Order(6)
        @DisplayName("the same mistake at both faces, each naming the version it validated against")
        @Proving({DboPromises.VER_CONCURRENT_VERSIONS, DboPromises.VER_SPECIFIED_VALIDATION})
        void theSameMistakeAtBothFaces() throws Exception {
            assertEquals("administrative-gender|5.0.0\nadministrative-gender|4.0.1",
                    snippets.run("validate-versions").text(),
                    "each face should validate against its own release");
        }

        @Test
        @Order(7)
        @DisplayName("asking for the verdict without writing, and the write agreeing with it")
        @Proving({DboPromises.VER_VALIDATION_WITHOUT_WRITING,
                DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED})
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

            // The verdict is worth nothing unless the write agrees with it. A
            // writer asks first precisely so they do not have to find out by
            // writing, and the two answers parting company is the failure that
            // makes asking pointless.
            assertEquals(422, postCode("HOSPITAL", "/Observation",
                    """
                    {"resourceType":"Observation","code":{"text":"house points"}}"""),
                    "the write accepted what $validate said it would refuse");
        }

        @Test
        @Order(8)
        @DisplayName("and what it accepts, a write accepts — with nothing written by asking")
        @Proving(DboPromises.VER_VALIDATION_WITHOUT_WRITING)
        void whatItAcceptsAWriteAcceptsAndNothingIsWritten() throws Exception {
            String sound = """
                    {"resourceType":"Patient",
                     "identifier":[{"system":"urn:rl:nid","value":"RL-0018"}],
                     "name":[{"family":"Vector"}]}""";
            // The operation's name is a literal dollar in the path, not a shell
            // variable. Unescaped, bash expanded it to nothing under set -u,
            // the request never happened, and "no error in the verdict" was
            // satisfied by there being no verdict at all — which is why the
            // assertions below insist on an outcome before reading into it.
            Snippets.Ran asked = snippets.sh("""
                    curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
                        "$HOGWARTS/Patient/\\$validate" \
                        -H 'Content-Type: application/fhir+json' -d '%s'
                    """.formatted(sound.replace("\n", " ")));
            assertEquals(0, asked.status(), "asking for a verdict failed: " + asked.err());
            String verdict = asked.text();
            assertTrue(verdict.contains("OperationOutcome"),
                    "no verdict came back at all: '" + verdict + "'");
            assertTrue(!verdict.contains("\"severity\":\"error\""),
                    "a sound document was reported as an error: " + verdict);
            // Asking is not writing, which is the half of this promise a
            // verdict alone cannot show.
            assertEquals(0, entries(ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0018")),
                    "asking for a verdict wrote the record");
            assertEquals(201, postCode("HOSPITAL", "/Patient", sound),
                    "the write refused what $validate accepted");
        }

        @Test
        @Order(9)
        @DisplayName("an unsupported mode is refused, and a type the tenant does not serve is "
                + "not found rather than quietly valid")
        @Proving(DboPromises.VER_VALIDATION_WITHOUT_WRITING)
        void anUnsupportedModeAndAnUnknownType() throws Exception {
            // Both are the same mistake seen twice: an answer that looks like
            // approval because the question was not understood.
            Snippets.Ran refusal = snippets.sh("""
                    curl -s -o /dev/null -w '%{http_code}' -X POST \
                        -H "Authorization: Bearer $HOSPITAL" \
                        "$HOGWARTS/Patient/\\$validate?mode=nonsense" \
                        -H 'Content-Type: application/fhir+json' \
                        -d '{"resourceType":"Patient"}'
                    """);
            assertEquals(0, refusal.status(), "the request was never made: " + refusal.err());
            assertEquals("400", refusal.lastLine(),
                    "an unsupported validation mode was not refused");

            assertEquals(404, postCode("HOSPITAL", "/Nonexistent/$validate",
                    """
                    {"resourceType":"Nonexistent"}"""),
                    "a type the tenant does not serve answered a verdict instead of saying "
                            + "it does not serve it");
        }

        @Test
        @Order(10)
        @DisplayName("and the operation the statement declares is the operation that answers")
        @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
        void declaredAndRoutableAreTheSameSet() throws Exception {
            // A statement that advertised an operation nothing answered would
            // be a promise the tenant cannot keep, and it is the kind that is
            // found in production rather than here.
            assertTrue(ask("HOSPITAL", "/metadata").contains("\"name\":\"validate\""),
                    "the statement does not declare the operation it answers");
        }

    }

    /**
     * The zone's own codes reaching the hospital directly and the insurer
     * through a projection, and the standard's terminology present by the same
     * mechanism rather than a special case.
     *
     * <p>The waits here are for a first sync, which is why this story sits after
     * the ones that do not need it.
     */
    @Nested
    @Order(8)
    @DisplayName("terminology, and the routes it arrives by")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TerminologyAndHowItArrives {

        @Test
        @Order(1)
        @DisplayName("the hospital declared the zone, so it answers the zone's codes as its own")
        @Proving(DboPromises.SYNC_TERMINOLOGY_GRAIN_SURVIVES)
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
        @Order(2)
        @DisplayName("the insurer declared the code systems and not the value sets, and that is what it has")
        @Proving(DboPromises.SYNC_DECLARED_ONLY)
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
        @Order(3)
        @DisplayName("and the standard's own terminology is there by the same mechanism")
        void theStandardsTerminologyIsThereTheSameWay() throws Exception {
            assertTrue(snippets.run("core-terminology").text().contains("Female"),
                    "the core code system is not answerable");
        }

        @Test
        @Order(4)
        @DisplayName("a copy names the upstream it came from and says it is not this "
                + "tenant's to change, while the tenant's own records say neither")
        @Proving(DboPromises.SYNC_PROVENANCE_COPIES)
        void aCopySaysWhoseItIs() throws Exception {
            // Both read with the hospital's own credential, so the only thing
            // that differs between them is where the record came from.
            String copied = ask("HOSPITAL", "/CodeSystem?url=urn:rl:wards");
            assertTrue(copied.contains("\"source\":\"urn:dbo:upstream:rl\"")
                            && copied.contains("\"code\":\"replicated\""),
                    "a copy must name its upstream and the class governing it: " + copied);

            String own = ask("HOSPITAL", "/Patient?identifier=urn:rl:nid|RL-0001");
            assertTrue(!own.contains("urn:dbo:upstream:")
                            && own.contains("\"code\":\"operational\""),
                    "a record this tenant authored claims an upstream, or does not say it "
                            + "is the tenant's own to change: " + own);
        }

    }

    /**
     * A face root holding its release as records, the zone's own ceremony, the
     * projection that converts it once for a face that needs it, and the version
     * that deleted a record answering gone rather than missing.
     */
    @Nested
    @Order(9)
    @DisplayName("faces, and what a zone federates")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FacesAndTheZone {

        @Test
        @Order(1)
        @DisplayName("a face root is a tenant, and its definitions are records")
        @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
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

            // Counted is not found. What the root holds has to be findable by
            // the canonical url it is known by, which is how a tenant taking
            // the version asks for one definition rather than all of them.
            Snippets.Ran byUrl = snippets.sh(
                    "curl -sf -G -H \"Authorization: Bearer $FACE_R5\""
                            + " \"http://localhost:8090/t/fhir-r5/fhir/StructureDefinition\""
                            + " --data-urlencode"
                            + " 'url=http://hl7.org/fhir/StructureDefinition/Patient'"
                            + " | python3 -c 'import sys,json;"
                            + "print(len(json.load(sys.stdin).get(\"entry\",[])))'");
            assertEquals(0, byUrl.status(), "the root answered nothing: " + byUrl.err());
            assertEquals("1", byUrl.lastLine(),
                    "the root holds the version's definitions and does not find one by the "
                            + "canonical url it is known by: " + byUrl.text());
        }

        @Test
        @Order(2)
        @DisplayName("the zone runs the ceremony its members federate to, because it names "
                + "no broker of its own")
        @Proving(DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER)
        void theZoneRunsItsOwnCeremony() throws Exception {
            assertEquals("200", snippets.run("zone-ceremony").lastLine());
            // The 200 is not the promise. A zone that named an identity broker
            // would federate to it; this one names none, so it is its own —
            // and what makes that true is the hub having keys of its own to
            // sign its assertions with.
            Snippets.Ran hub = snippets.sh(
                    "curl -sf http://localhost:8090/z/rl/hub/jwks.json");
            assertEquals(0, hub.status(), "the zone's hub answered nothing: " + hub.err());
            assertTrue(hub.text().contains("\"keys\""),
                    "the hub has no keys of its own, so nothing federates to it: " + hub.text());
            // And its members serve, which is the half that would be missing
            // if a brokerless zone simply held its tenants out of service.
            assertTrue(served("hogwarts") && served("gringotts"),
                    "a member of a brokerless zone is not being served");
        }

        @Test
        @Order(3)
        @DisplayName("a projection converts the zone once, for the face that needs it")
        @Proving(DboPromises.ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION)
        void aProjectionConvertsTheZoneOnce() throws Exception {
            assertTrue(waitUntilServed("rl-on-r4", 90), "the projection never came up");
            assertEquals("4.0.1\n5.0.0", snippets.run("projection").text(),
                    "the projection should speak the face it serves while the zone keeps its own");
        }

        @Test
        @Order(4)
        @DisplayName("the version that deleted a record is gone, not missing")
        @Proving(DboPromises.CORE_VERSIONED_HISTORY)
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
            // And the record itself is not found, which is the difference
            // between the address and the version at it: one is gone, the
            // other never had anything to serve.
            assertEquals(404, status("HOSPITAL", "/Patient/" + snippets.recall("doomed")),
                    "a deleted record still answers at its own address");
        }

    }

    /**
     * The organisation, the people in it, the role one of them holds, and what
     * that role is granted — all of it records, none of it a column.
     *
     * <p>It leaves {@code org}, {@code matron} and the grant behind, which the
     * story after this one signs in against.
     */
    @Nested
    @Order(10)
    @DisplayName("who may act here")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WhoMayActHere {

        @Test
        @Order(1)
        @DisplayName("the hospital is an organisation, with people and what they may do")
        void theHospitalIsAnOrganisation() throws Exception {
            assertEquals("201\n201", snippets.run("org-and-people").text());
            snippets.remember("org", onlyIdIn(ask("HOSPITAL",
                    "/Organization?identifier=urn:rl:org|hogwarts")));
            snippets.remember("matron", onlyIdIn(ask("HOSPITAL",
                    "/Practitioner?identifier=urn:rl:nid|RL-POMFREY")));
        }

        @Test
        @Order(2)
        @DisplayName("a role is a record, not a column")
        @Proving(DboPromises.AUTH_ORG_MODEL_IS_THE_AUTH_MODEL)
        void aRoleIsARecordNotAColumn() throws Exception {
            assertEquals("201", snippets.run("the-role").lastLine());
            // Counted from the entries rather than read from a total: a searchset
            // that matched nothing does not carry one, so reading it would turn an
            // assertion that should fail into an error that says nothing.
            assertTrue(entries(ask("HOSPITAL", "/PractitionerRole")) > 0, "the role did not land");
        }

        @Test
        @Order(3)
        @DisplayName("and what that role may do is declared, and readable")
        @Proving(DboPromises.AUTH_GRANTS_ARE_READABLE_TO_CONVERGE)
        void whatThatRoleMayDoIsReadable() throws Exception {
            String grants = snippets.run("role-grant").text();
            assertTrue(grants.contains("matron"),
                    "the tenant does not say what it grants: " + grants);
            assertTrue(grants.contains("user/Patient.read"),
                    "the grant does not say what it carries: " + grants);
            // And where it reaches. A role scoped to an organisation that came
            // back reaching the whole tenant would grant more than was asked
            // for, which is the failure nobody sees until somebody reads a
            // record they should not have.
            assertTrue(grants.contains("hogwarts"),
                    "the grant does not name the organisation it is at: " + grants);
        }

    }

    /**
     * A person signs in and the store resolves what she is here; a process
     * acts in her name and cannot widen past her; a delegation outlives the
     * token and then ends.
     *
     * <p>Authority promises belong here — and note what the story is careful to
     * keep separate: what a credential authenticates as, and what it is
     * authorised to do.
     */
    @Nested
    @Order(11)
    @DisplayName("acting for somebody, and stopping")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ActingForSomebody {

        @Test
        @Order(1)
        @DisplayName("a person signs in, and the store works out what she is here")
        void aPersonSignsIn() throws Exception {
            assertEquals("201", snippets.run("a-person-signs-in").lastLine());
            snippets.remember("person", onlyIdIn(ask("HOSPITAL",
                    "/Person?identifier=urn:rl:nid|RL-POMFREY")));
            // The admin endpoints answer 200, not the 201 the FHIR faces answer:
            // they are not creating records, they are stating what the tenant's
            // own authority holds.
            assertEquals("200\n200", snippets.run("her-credential").text(),
                    "her login and the console she signs into were not both created");

            assertEquals(0, snippets.run("sign-in").status(), "signing in failed");
            assertTrue(snippets.recall("HUMAN").startsWith("ey"),
                    "the code did not exchange for a token");

            // The credential binds to the person, and the capacity is resolved
            // from the person's own link — which is the distinction the chapter
            // stops on, and the one a test can actually hold.
            assertTrue(snippets.run("who-she-is").text()
                            .contains("Practitioner/" + snippets.recall("matron")),
                    "the token names the wrong capacity");
        }

        @Test
        @Order(2)
        @DisplayName("a process acts in her name, and carries both names")
        @Proving(DboPromises.AUTH_ON_BEHALF_OF)
        void aProcessActsInHerName() throws Exception {
            String acting = snippets.run("acting-for-her", "token-exchange", "who-she-is").text();
            assertTrue(acting.contains("night-ledger"),
                    "the delegated token does not name the actor: " + acting);
            assertTrue(acting.contains(snippets.recall("person")),
                    "the delegated token lost the person: " + acting);
        }

        @Test
        @Order(3)
        @DisplayName("and cannot acquire authority she never had")
        @Proving(DboPromises.AUTH_ON_BEHALF_OF)
        void andCannotAcquireAuthoritySheNeverHad() throws Exception {
            assertTrue(snippets.run("attenuation").text().contains("access_denied"),
                    "a delegated token widened past its subject");
        }

        @Test
        @Order(4)
        @DisplayName("work that outlives the token holds a delegation")
        @Proving(DboPromises.AUTH_ON_BEHALF_OF)
        void workThatOutlivesTheTokenHoldsADelegation() throws Exception {
            String granted = snippets.run("a-delegation").text();
            assertTrue(granted.contains("delegation_id"), "no delegation was recorded: " + granted);
            snippets.remember("delegation", after(granted, "\"delegation_id\":\""));

            assertTrue(snippets.run("exchange-a-delegation", "token-exchange", "who-she-is")
                            .text().contains(snippets.recall("person")),
                    "the delegation lost the person it was granted by");
        }

        @Test
        @Order(5)
        @DisplayName("and ending it stops the next exchange")
        void andEndingItStopsTheNextExchange() throws Exception {
            String ended = snippets.run("ending-a-delegation").text();
            assertTrue(ended.contains("invalid_grant"),
                    "an ended delegation still exchanged: " + ended);
        }

        @Test
        @Order(6)
        @DisplayName("every tenant issues its own tokens")
        @Proving(DboPromises.AUTH_TENANT_SCOPED_ISSUER)
        void everyTenantIssuesItsOwnTokens() throws Exception {
            // The issuer carries the address the node was told to bind to, and
            // this world binds to everything — so what matters is the tail, which
            // is the tenant's own path. The same thing shows in a paging link.
            java.util.List<String> issuers = snippets.run("issuer").text().lines().toList();
            assertEquals(2, issuers.size());
            assertTrue(issuers.get(0).endsWith("/t/hogwarts/oidc"),
                    "the hospital's issuer is not its own: " + issuers.get(0));
            assertTrue(issuers.get(1).endsWith("/t/gringotts/oidc"),
                    "the insurer's issuer is not its own: " + issuers.get(1));
        }

    }

    /**
     * What the acts above recorded, asked about by the record they name and by
     * who performed them.
     *
     * <p><b>This story is deliberately last of its group and deliberately
     * thin.</b> A promise that an action is audited is better proven beside that
     * action, where the act and its entry can be asserted together, than by a
     * story that goes looking for entries after the fact. What belongs here is
     * the trail's own behaviour: how it is searched, and what an entry
     * carries.
     */
    @Nested
    @Order(12)
    @DisplayName("the trail")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TheTrail {

        @Test
        @Order(1)
        @DisplayName("the trail records the act, and who did it")
        @Proving({DboPromises.POL_AUDIT_AS_RECORDS, DboPromises.POL_ACTOR_FROM_AUTHORITY,
                DboPromises.POL_FHIR_AUDIT_PROJECTION})
        void theTrailRecordsTheAct() throws Exception {
            // Asked by the REFERENCE, because that is the form the entry hands
            // back and the form a reader copies. Matched against the id alone it
            // returned an empty bundle, which reads as nothing having happened.
            String recorded = snippets.run("trail").text();
            assertTrue(recorded.contains("Patient/" + snippets.recall("id")),
                    "the trail cannot be asked about the record it names: " + recorded);
            assertTrue(recorded.contains("tenant-bootstrap"),
                    "the trail does not say who acted: " + recorded);

            // Pseudonymous, which is the part of "audit entries are records"
            // that is easiest to lose: an entry naming the patient would make
            // the trail a second copy of what the membrane exists to seal.
            String entry = ask("HOSPITAL", "/AuditEvent?entity=Patient/"
                    + snippets.recall("id") + "&action=C&_count=1");
            assertTrue(!entry.contains("Potter"),
                    "the trail carries the person it is about: " + entry);
            // And it is served as AuditEvent — a native record rendered per
            // face, not a log line with a resourceType glued on.
            assertTrue(entry.contains("\"resourceType\":\"AuditEvent\""),
                    "the trail is not projected as the face's own type: " + entry);
        }

        @Test
        @Order(2)
        @DisplayName("and an entry cannot be changed or taken back, whatever the tenant's "
                + "write discipline says")
        @Proving(DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY)
        void anEntryCannotBeChangedOrTakenBack() throws Exception {
            // The trail is the record of what happened, so a tenant that could
            // edit it holds nothing worth producing to anybody. This is not the
            // tenant's policy to set — it is true under every discipline.
            String recorded = ask("HOSPITAL", "/AuditEvent?_count=1");
            String entry = onlyIdIn(recorded);

            Snippets.Ran amended = snippets.sh("""
                    curl -s -o /dev/null -w '%%{http_code}' -X PUT \
                        -H "Authorization: Bearer $HOSPITAL" \
                        -H 'Content-Type: application/fhir+json' \
                        "$HOGWARTS/AuditEvent/%s" \
                        -d '{"resourceType":"AuditEvent","id":"%s"}'
                    """.formatted(entry, entry));
            assertEquals(0, amended.status(), "the request was never made: " + amended.err());
            assertTrue(!amended.lastLine().startsWith("2"),
                    "an audit entry was amended, and answered " + amended.lastLine());

            Snippets.Ran dropped = snippets.sh("""
                    curl -s -o /dev/null -w '%%{http_code}' -X DELETE \
                        -H "Authorization: Bearer $HOSPITAL" \
                        "$HOGWARTS/AuditEvent/%s"
                    """.formatted(entry));
            assertEquals(0, dropped.status(), "the request was never made: " + dropped.err());
            assertTrue(!dropped.lastLine().startsWith("2"),
                    "an audit entry was taken back, and answered " + dropped.lastLine());
        }

        @Test
        @Order(3)
        @DisplayName("and the trail is searched the way it is asked about")
        void theTrailIsSearchedTheWayItIsAskedAbout() throws Exception {
            String byAgent = snippets.run("trail-search").text();
            assertTrue(Integer.parseInt(byAgent.split(" ")[0]) > 0,
                    "the trail cannot be searched by who acted: " + byAgent);
        }

    }

    /**
     * The inventory, the stream per domain, and the consumers with how far
     * behind each one is.
     *
     * <p>It reports on everything the stories above wrote, so it sits after
     * them on purpose.
     */
    @Nested
    @Order(13)
    @DisplayName("what a tenant holds, and what is reading it")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WhatATenantHolds {

        @Test
        @Order(1)
        @DisplayName("what the tenant holds, before anything moves")
        void whatTheTenantHoldsBeforeAnythingMoves() throws Exception {
            String inventory = snippets.run("inventory").text();
            assertTrue(inventory.lines().count() > 3,
                    "the inventory should name what the tenant holds: " + inventory);
            assertTrue(inventory.contains("definitions:"),
                    "the inventory does not account for the definitions: " + inventory);
        }

        @Test
        @Order(2)
        @DisplayName("the streams a tenant carries, one per domain")
        void theStreamsATenantCarries() throws Exception {
            java.util.List<String> domains = snippets.run("feed-domains").text().lines().toList();
            // The trail and the work a tenant does are separate streams from its
            // records, which is what makes them separately readable.
            assertTrue(domains.contains("audit"), "the tenant reports no audit domain: " + domains);
            assertTrue(domains.contains("work"), "the tenant reports no work domain: " + domains);
        }

        @Test
        @Order(3)
        @DisplayName("and what is reading them, with how far behind it is")
        @Proving(DboPromises.FEED_NAMED_CONSUMERS)
        void andWhatIsReadingThem() throws Exception {
            java.util.List<String> reading = snippets.run("feed-consumers").text().lines().toList();
            assertTrue(!reading.isEmpty(), "the tenant does not say what is reading it");
            // A consumer is a name and a position. One reported without a position
            // is not something you can act on, so the step insists on both.
            for (String row : reading) {
                assertTrue(row.contains("lag "), "a consumer was named without a position: " + row);
            }
        }

    }

    /**
     * Bytes written, handed back as they were given, and refused to anyone
     * without the grant that covers them.
     */
    @Nested
    @Order(14)
    @DisplayName("content the tenant holds whole")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ContentHeldWhole {

        @Test
        @Order(1)
        @DisplayName("content a tenant holds whole")
        void contentATenantHoldsWhole() throws Exception {
            String written = snippets.run("blob-write").text();
            assertTrue(written.contains("\"key\""), "the blob was not written: " + written);
            assertTrue(snippets.recall("blob").matches("[0-9a-f-]{36}"),
                    "the key is not a store assignment: " + snippets.recall("blob"));
        }

        @Test
        @Order(2)
        @DisplayName("handed back as it was given")
        @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
        void handedBackAsItWasGiven() throws Exception {
            String headers = snippets.run("blob-read").text();
            assertTrue(headers.toLowerCase(java.util.Locale.ROOT).contains("application/pdf"),
                    "the media type the writer declared did not come back: " + headers);
            Snippets.Ran back = snippets.sh(
                    "curl -sf -H \"Authorization: Bearer $HOSPITAL\" \"$BLOBS/$blob\"");
            assertEquals("PDF-ish bytes", back.text(), "the bytes came back changed");
        }

        @Test
        @Order(3)
        @DisplayName("and it is guarded by the grant that covers binary content")
        void andItIsGuardedByTheGrant() throws Exception {
            // Unheld and absent, in that order: a blob is not public, and one that
            // was never written says so rather than saying nothing.
            assertEquals("401\n404", snippets.run("blob-unheld").text());
        }

    }

    /**
     * The whole tenant as one file, sealed under a key the store does not
     * hold, illegible to whoever stores it — and an import the store cannot
     * perform alone.
     *
     * <p>It writes an archive into the working directory and takes it away
     * again, which the teardown also guarantees.
     */
    @Nested
    @Order(15)
    @DisplayName("leaving, and the ceremony of coming back")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class LeavingAndComingBack {

        @Test
        @Order(1)
        @DisplayName("the archive is sealed under a key the store does not hold")
        @Proving(DboPromises.PDI_BLIND_OPERATIONS)
        void theArchiveIsSealedUnderAKeyTheStoreDoesNotHold() throws Exception {
            String refused = snippets.run("archive-no-key").text();
            assertTrue(refused.contains("does not hold"),
                    "the refusal should say why: " + refused);
        }

        @Test
        @Order(2)
        @DisplayName("the whole tenant leaves as one file, and whoever stores it can read nothing in it")
        void theWholeTenantLeavesAsOneFile() throws Exception {
            String taken = snippets.run("archive").text();
            assertTrue(taken.startsWith("200 "), "the archive was refused: " + taken);
            assertTrue(java.nio.file.Files.size(ARCHIVE) > 0, "the archive is empty");
            assertEquals("0", snippets.run("archive-opaque").text(),
                    "a name is legible in the archive, which is the one thing it must not be");
        }

        @Test
        @Order(3)
        @DisplayName("coming back is a ceremony, and the store cannot perform it alone")
        @Proving(DboPromises.MNT_IMPORT_REFUSES_UNATTESTED)
        void comingBackIsACeremony() throws Exception {
            String refused = snippets.run("import-needs-signatures").text();
            assertTrue(refused.contains("cannot sign for either of them"),
                    "the refusal should name what is missing: " + refused);
            java.nio.file.Files.deleteIfExists(ARCHIVE);
        }

    }

    /**
     * A credential that may act in work and not read a record, a run that
     * grants it exactly what the run was started over, and the record the run
     * itself is — displaying its subject rather than resolving it.
     *
     * <p>The order matters more here than anywhere else: the refusal comes
     * before the run, because a credential that could read the record directly
     * would make everything after it a formality.
     */
    @Nested
    @Order(16)
    @DisplayName("work, and how far a run reaches")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WorkAndHowFarARunReaches {

        @Test
        @Order(1)
        @DisplayName("a credential that may act in work, and not read the tenant")
        void aCredentialThatMayActInWork() throws Exception {
            assertEquals("200", snippets.run("worker-credential", "token").text().lines()
                    .findFirst().orElse(""), "the porter's client was not created");
            assertTrue(snippets.recall("PORTER").startsWith("ey"), "no token for the porter");
        }

        @Test
        @Order(2)
        @DisplayName("and that credential cannot read a record directly")
        @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
        void andThatCredentialCannotReadARecordDirectly() throws Exception {
            String blocked = snippets.run("worker-cannot-read").lastLine();
            assertTrue(blocked.equals("403") || blocked.equals("401"),
                    "the work credential read a record directly, got " + blocked);
        }

        @Test
        @Order(3)
        @DisplayName("a run of the step the hospital offers, over one patient — and a "
                + "document that breaks the step's rules refused by name on the same door")
        @Proving(DboPromises.PROC_WORK_IS_AUTHORED_ON_THE_SURFACE)
        void aRunOfTheStepTheHospitalOffers() throws Exception {
            String started = snippets.run("start-a-run").text();
            assertTrue(started.contains("\"context\""), "the run returned no context: " + started);
            assertTrue(snippets.recall("CONTEXT").endsWith("/fhir"),
                    "the context is not a FHIR base: " + snippets.recall("CONTEXT"));
            assertTrue(!snippets.recall("run").isBlank(), "the run returned no id");
            // A PATH, not an absolute url. What a node is bound to is not what
            // a caller reached it by, so a context that named a host would
            // hand out links that work nowhere; the caller resolves it against
            // the origin it already used, which is what the snippet does.
            assertTrue(started.contains("\"context\":\"/t/hogwarts/run/"),
                    "the context names a host rather than a path: " + started);
            assertTrue(snippets.recall("key").startsWith("hogwarts.admission.admit/"),
                    "the run came back without the name the rest of the work model knows it "
                            + "by: " + snippets.recall("key"));

            // Becoming a run is half of it. The other half is the same door
            // refusing a document that does not meet the step's rules, and
            // saying which rule — an author told only "no" has to guess
            // between a step nobody offers and a slot nobody declared.
            for (String[] wrong : java.util.List.of(
                    new String[] {"hogwarts.admission.nosuchstep",
                        "\\\"patient\\\":\\\"Patient/$id\\\"", "offers no step"},
                    new String[] {"hogwarts.admission.admit",
                        "\\\"patient\\\":\\\"Patient/$id\\\",\\\"ward\\\":\\\"Location/x\\\"",
                        "declares no slot"},
                    new String[] {"hogwarts.admission.admit", "", "is unfilled"})) {
                Snippets.Ran refused = snippets.sh(
                        "curl -s -X POST -H \"Authorization: Bearer $PORTER\""
                                + " -H 'Content-Type: application/json'"
                                + " http://localhost:8090/t/hogwarts/step/" + wrong[0]
                                + " -d \"{\\\"inputs\\\":{" + wrong[1] + "}}\"");
                assertEquals(0, refused.status(), "the request was never made: " + refused.err());
                assertTrue(refused.text().contains(wrong[2]),
                        "a document breaking '" + wrong[2] + "' was not refused by name: "
                                + refused.text());
            }
        }

        @Test
        @Order(4)
        @DisplayName("inside the run, the patient it was given")
        @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
        void insideTheRunThePatientItWasGiven() throws Exception {
            // The same credential that could not read this record a moment ago can
            // read it now, and nothing was granted to make that true.
            assertEquals("200", snippets.run("read-in-run").lastLine());
        }

        @Test
        @Order(5)
        @DisplayName("and nothing else, whatever its type")
        @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
        void andNothingElseWhateverItsType() throws Exception {
            assertEquals(0, snippets.run("another-patient").status());
            assertEquals("404\n404", snippets.run("read-outside-reach").text(),
                    "a run reached a patient it was never given");

            // The status alone is not the property. The asker already knows the
            // id it asked for; what it must not be able to learn is whether the
            // tenant holds it. So the two refusals are compared BODY to body —
            // a withheld record and an invented id — and any difference between
            // them is a way to enumerate what exists.
            Snippets.Ran refusals = snippets.sh("""
                    invented=01a00000-0000-7000-8000-00000000beef
                    curl -s -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$other"
                    printf '\\n'
                    curl -s -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$invented" \\
                        | sed "s/$invented/$other/"
                    """);
            assertEquals(0, refusals.status(), "the requests were never made: " + refusals.err());
            String[] both = refusals.text().split("\n");
            assertEquals(2, both.length, "two refusals were expected: " + refusals.text());
            assertEquals(both[0], both[1],
                    "a withheld record and an absent one answer differently, so asking is a "
                            + "way to find out which records exist");
        }

        @Test
        @Order(6)
        @DisplayName("the context says what it answers for")
        @Proving(DboPromises.PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS)
        void theContextSaysWhatItAnswersFor() throws Exception {
            assertEquals("Patient", snippets.run("run-metadata").text(),
                    "the context advertises more than the step declared");
        }

        @Test
        @Order(7)
        @DisplayName("the run is a record, and it says what it is over and who holds it")
        @Proving(DboPromises.PROC_RUN_HAS_A_RECORD)
        void theRunIsARecord() throws Exception {
            String record = snippets.run("run-as-a-record").text();
            // The process and the step are separate codings on the record, not the
            // joined identifier the endpoint is addressed by.
            assertTrue(record.contains("hogwarts.admission"),
                    "the run record does not name the process: " + record);
            assertTrue(record.contains("admit"),
                    "the run record does not name the step: " + record);
            assertTrue(record.contains("input"),
                    "the run record does not carry the slot it was filled with: " + record);
        }

        @Test
        @Order(8)
        @DisplayName("and the run envelope displays its subject rather than resolving it")
        @Proving(DboPromises.PROC_TASK_CARRIES_THE_INPUTS)
        void theRunEnvelopeDisplaysItsSubject() throws Exception {
            // The whole point of the envelope: a run says what state it is in
            // without disclosing its subject to whoever may read runs.
            String task = ask("HOSPITAL", "/Task/" + snippets.recall("run"));
            String slot = task.split("\"valueReference\"", 2)[1].split("}", 2)[0];
            assertTrue(slot.contains("\"display\""),
                    "the run envelope does not display its subject: " + slot);
            assertTrue(!slot.contains("\"reference\""),
                    "the run envelope resolved its subject instead of displaying it: " + slot);
        }

        @Test
        @Order(9)
        @DisplayName("the read through the run is on the record, naming the run that occasioned it")
        @Proving(DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES)
        void theReadThroughTheRunIsOnTheRecord() throws Exception {
            // Worth asserting HERE and not only that an entry exists: this
            // tenant keeps its trail at `writes`, so an ordinary read of the
            // same patient by the same credential leaves nothing. What makes
            // this one recorded is that a run occasioned it.
            String opened = snippets.run("run-trail").text();
            assertTrue(opened.contains("Patient/" + snippets.recall("id")),
                    "the trail cannot say what the run opened: " + opened);
            assertTrue(opened.contains("a-porter"),
                    "the trail does not say who read it: " + opened);

            // And from the other end, which is the half that matters to
            // somebody asking about a person rather than about work: the entry
            // is on the DOCUMENT, beside every other reading of it.
            String onTheRecord = ask("HOSPITAL", "/AuditEvent?entity=Patient/"
                    + snippets.recall("id") + "&action=R");
            assertTrue(onTheRecord.contains(snippets.recall("key")),
                    "the reading of the document does not name the run it was for: "
                            + onTheRecord);
        }

        @Test
        @Order(10)
        @DisplayName("the work ends, and the way in closes behind it")
        @Proving(DboPromises.PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN)
        void theWorkEndsAndTheWayInClosesBehindIt() throws Exception {
            assertEquals("holder nobody", snippets.run("end-a-run").text(),
                    "the run did not end");
            // The same request that answered 200 a few steps ago. Asserted as
            // the last thing this story does, because everything before it
            // needs the context open.
            assertEquals("404", snippets.run("read-after-run").lastLine(),
                    "the context still answers for a run that is over, which is a standing "
                            + "way in left behind by work nobody is doing");

            // And it is as absent as a run that never was. Asserted here
            // rather than as a step of its own, because it can only be asked
            // once this one has ended the run — and two steps sharing an
            // order are not two steps in an order.
            //
            // The refusal for a run that really happened, against the refusal
            // for an id nothing ever minted. A difference between them would
            // say which runs existed, which is the property the withheld
            // record is held to one level up.
            Snippets.Ran refusals = snippets.sh("""
                    invented=01a00000-0000-7000-8000-0000000000ff
                    curl -s -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$id"
                    printf '\\n'
                    curl -s -H "Authorization: Bearer $PORTER" \\
                        "http://localhost:8090/t/hogwarts/run/$invented/fhir/Patient/$id"
                    """);
            assertEquals(0, refusals.status(), "the requests were never made: " + refusals.err());
            String[] both = refusals.text().split("\n");
            assertEquals(2, both.length, "two refusals were expected: " + refusals.text());
            assertEquals(both[0], both[1],
                    "an ended run and one that never existed answer differently, so asking "
                            + "is a way to find out which runs happened");
        }

    }

    /**
     * A runner asking for work and being told what it may have, and a lane
     * refusing out loud rather than answering an unusable request with an empty
     * list.
     */
    @Nested
    @Order(17)
    @DisplayName("the lane a runner asks")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TheLane {

        @Test
        @Order(1)
        @DisplayName("a runner asks the lane for work, and is told what it may have")
        void aRunnerAsksTheLaneForWork() throws Exception {
            assertTrue(snippets.run("lane-poll").text().contains("result"),
                    "the lane did not answer a poll");
        }

        @Test
        @Order(2)
        @DisplayName("and a refusal on the lane says why, rather than going quiet")
        void aRefusalOnTheLaneSaysWhy() throws Exception {
            // A lane that answered an unusable request with an empty list would be
            // indistinguishable from one with no work, which is the failure mode
            // this step exists to prevent.
            for (String refusal : snippets.run("lane-refuses").text().lines().toList()) {
                assertTrue(refusal.contains("\"refused\":true"),
                        "the lane answered without refusing: " + refusal);
                assertTrue(refusal.contains("\"reason\""),
                        "the lane refused without saying why: " + refusal);
            }
        }

    }

    /**
     * What an operator holding the database sees where the identifying
     * elements would be, and the two refusals that keep it that way: a name
     * search, and an identifying lookup with no stated reason.
     *
     * <p>Every PDI promise belongs here, and the thing to preserve when adding
     * one is that a refusal is not an empty answer — an empty bundle would be a
     * different and false statement.
     */
    @Nested
    @Order(18)
    @DisplayName("the membrane")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TheMembrane {

        @Test
        @Order(1)
        @DisplayName("and what an operator with the database sees instead")
        @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
        void whatAnOperatorWithTheDatabaseSees() throws Exception {
            // The published snippet names the guide's own compose file, because
            // that is what a reader types. Against a tree-built world the
            // project is somewhere else entirely, and the snippet answers
            // nothing — which reads as a tenant storing no ciphertext at all.
            // check.sh has the same branch for the same reason.
            java.util.List<String> stored = (COMPOSE.toString().endsWith("examples/compose.yaml")
                    ? snippets.run("pdi-ciphertext")
                    : snippets.sh("docker compose -f " + COMPOSE + " exec -T db"
                            + " psql -U postgres -d tenant_hogwarts -tAc"
                            + " \"SELECT convert_from(payload,'UTF8') FROM state.r5_data"
                            + " WHERE type='Patient' LIMIT 1\""
                            + " | python3 -c \"import sys,json;"
                            + "print(*sorted(json.loads(sys.stdin.read())), sep='\\n')\""))
                    .text().lines().toList();
            assertTrue(!stored.contains("name") && !stored.contains("identifier"),
                    "the stored payload carries identifying elements: " + stored);
            assertTrue(stored.contains("__pdiEnc"),
                    "the stored payload carries no ciphertext: " + stored);
        }

        @Test
        @Order(2)
        @DisplayName("asking by name is refused, not answered empty")
        void askingByNameIsRefused() throws Exception {
            // An empty bundle would have said nobody is called that, which is a
            // different and false statement.
            assertEquals(403, status("HOSPITAL", "/Patient?family=Potter"));
            assertTrue(!snippets.run("pdi-name-search").text().isBlank(),
                    "the refusal did not say what it was");
        }

        @Test
        @Order(3)
        @DisplayName("and an identifying lookup without a stated reason is refused too")
        @Proving({DboPromises.PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL,
                DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST})
        void anIdentifyingLookupWithoutAReasonIsRefused() throws Exception {
            String refused = snippets.run("pdi-no-purpose", "token").text();
            assertTrue(refused.contains("purpose"),
                    "a person was resolved without a stated purpose: " + refused);
        }

        @Test
        @Order(4)
        @DisplayName("but reading the record is answered, with the person taken out of it")
        @Proving(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED)
        void aReadWithoutAReasonIsAnsweredWithoutThePerson() throws Exception {
            // The credential the step above minted: it may write every type
            // here and states no reason. Refusing would be the safe-looking
            // answer and the wrong one — what a recipient sees follows the
            // declaration rather than how much they could write, and work
            // that never needed the person still runs.
            //
            // The answer is established before anything is said to be missing.
            // The first version of this called a shell function an earlier
            // snippet defined, which is not in scope here: curl never ran, and
            // the absence of her name was the absence of an answer.
            Snippets.Ran read = snippets.sh("curl -sf -H \"Authorization: Bearer $NO_REASON\""
                    + " \"$HOGWARTS/Patient/" + snippets.recall("id") + "\"");
            assertEquals(0, read.status(), "the read never happened: " + read.err());
            String seen = read.text();
            assertTrue(seen.contains("\"resourceType\":\"Patient\""),
                    "the record was refused rather than answered: " + seen);
            assertTrue(!seen.contains("Potter") && !seen.contains("RL-0001"),
                    "a broad write grant read the person back: " + seen);
        }

    }

    /**
     * A directory provisioning a person, reaching no further into the store
     * than the door it was given, and not getting to say who is an administrator
     * here.
     */
    @Nested
    @Order(19)
    @DisplayName("the directory at the door")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TheDirectoryAtTheDoor {

        @Test
        @Order(1)
        @DisplayName("the directory provisions a person, and the capacity comes with them")
        @Proving(DboPromises.SCIM_USER_IS_THE_PERSON)
        void theDirectoryProvisionsAPerson() throws Exception {
            String provisioned = snippets.run("scim-create", "token").text();
            assertTrue(provisioned.contains("mmcgonagall"),
                    "the directory did not provision the person: " + provisioned);

            // A provisioned User is not a row in a directory table beside the
            // store — it IS a person here, which is the whole claim. So the
            // check is made on the store's own surface rather than on SCIM's.
            String person = ask("HOSPITAL",
                    "/Person?identifier=urn:rl:staff-directory|HOG-0042");
            assertEquals(1, entries(person),
                    "the User did not land as a Person claiming its externalId: " + person);
            assertTrue(person.contains("\"link\""),
                    "the person arrived without the capacity they act in: " + person);
        }

        @Test
        @Order(2)
        @DisplayName("reads and both filters answer, and a filter on anything else is refused")
        @Proving(DboPromises.SCIM_ENUMERATION_STAYS_INSIDE)
        void readsAndFiltersAnswerAndNothingElseDoes() throws Exception {
            String byName = scim("/Users?filter=" + query("userName eq \"mmcgonagall\""));
            assertTrue(byName.contains("\"totalResults\":1"),
                    "a filter on the userName the directory set answered nothing: " + byName);
            assertTrue(scim("/Users?filter=" + query("externalId eq \"HOG-0042\""))
                            .contains("\"totalResults\":1"),
                    "a filter on the externalId the directory set answered nothing");

            // The two the directory itself assigned are the two it may ask by.
            // Anything else would be a way to walk the tenant's people from
            // outside, one question at a time.
            String refused = scimCode("/Users?filter=" + query("name.familyName eq \"McGonagall\""));
            assertEquals("400", refused,
                    "the directory could filter by something it did not assign, got " + refused);
        }

        @Test
        @Order(3)
        @DisplayName("a second User claiming the same externalId is refused rather than "
                + "quietly making a second person")
        @Proving(DboPromises.SCIM_USER_IS_THE_PERSON)
        void aDuplicateExternalIdIsRefused() throws Exception {
            Snippets.Ran again = snippets.sh("""
                    curl -s -o /dev/null -w '%{http_code}' -X POST \
                        -H "Authorization: Bearer $DIRECTORY" \
                        -H 'Content-Type: application/scim+json' "$SCIM/Users" \
                        -d '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                             "externalId":"HOG-0042","userName":"mmcgonagall2",
                             "name":{"familyName":"McGonagall","givenName":"Minerva"},
                             "active":true}'
                    """);
            assertEquals(0, again.status(), "the request was never made: " + again.err());
            assertEquals("409", again.lastLine(),
                    "the same person was provisioned twice, and answered " + again.lastLine());
        }

        @Test
        @Order(4)
        @DisplayName("deprovisioning is a state the person keeps, not a deletion")
        @Proving(DboPromises.SCIM_DEPROVISION_IS_A_STATE)
        void deprovisioningIsAState() throws Exception {
            String held = scim("/Users?filter=" + query("userName eq \"mmcgonagall\""));
            String user = after(held, "\"id\":\"");

            Snippets.Ran gone = snippets.sh("""
                    curl -s -X PUT -H "Authorization: Bearer $DIRECTORY" \
                        -H 'Content-Type: application/scim+json' "$SCIM/Users/%s" \
                        -d '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                             "externalId":"HOG-0042","userName":"mmcgonagall",
                             "name":{"familyName":"McGonagall","givenName":"Minerva"},
                             "active":false}'
                    """.formatted(user));
            assertEquals(0, gone.status(), "the request was never made: " + gone.err());
            assertTrue(gone.text().contains("\"active\":false"),
                    "deprovisioning did not take: " + gone.text());

            // Still there, still the same person. A directory that deleted
            // them would take the history of what they did with them.
            assertEquals(1, entries(ask("HOSPITAL",
                            "/Person?identifier=urn:rl:staff-directory|HOG-0042")),
                    "deprovisioning removed the person rather than deactivating them");

            // A replace carrying a version that has moved is refused, the way
            // it is on the store's own surface — a directory pushing a state
            // computed from what it read a while ago should be told, not obeyed.
            Snippets.Ran stale = snippets.sh("""
                    curl -s -o /dev/null -w '%%{http_code}' -X PUT \
                        -H "Authorization: Bearer $DIRECTORY" \
                        -H 'Content-Type: application/scim+json' \
                        -H 'If-Match: W/"99"' "$SCIM/Users/%s" \
                        -d '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                             "externalId":"HOG-0042","userName":"mmcgonagall","active":true}'
                    """.formatted(user));
            assertEquals(0, stale.status(), "the request was never made: " + stale.err());
            assertEquals("412", stale.lastLine(),
                    "a replace against a version that had moved was accepted");

            // And erasure is not a provisioning operation. Taking somebody out
            // of the directory is not the same act as erasing them, and a door
            // that conflated them would let a directory outage read as a
            // request to forget people.
            Snippets.Ran deleted = snippets.sh("""
                    curl -s -o /dev/null -w '%{http_code}' -X DELETE \
                        -H "Authorization: Bearer $DIRECTORY" "$SCIM/Users/%s"
                    """.replace("%s", user));
            assertEquals(0, deleted.status(), "the request was never made: " + deleted.err());
            assertEquals("405", deleted.lastLine(),
                    "the directory could erase a person by deprovisioning them");
        }

        @Test
        @Order(5)
        @DisplayName("and every operation on the door is one recorded disclosure")
        @Proving(DboPromises.SCIM_EVERY_OP_IS_A_DISCLOSURE)
        void everyOperationIsADisclosure() throws Exception {
            // The door is outside the store and reaches people inside it, so
            // what it did has to be answerable from the tenant's own trail
            // rather than from the directory's word for it.
            String trail = ask("HOSPITAL", "/AuditEvent?agent=staff-directory");
            assertTrue(entries(trail) > 0,
                    "the directory acted and the tenant's trail says nothing: " + trail);
        }

        @Test
        @Order(6)
        @DisplayName("and that credential reaches the store no further than the door it was given")
        @Proving(DboPromises.SCIM_DIRECTORY_CREDENTIAL)
        void theDirectoryCredentialReachesNoFurther() throws Exception {
            // Both ways round, which is the part worth asserting: the door does
            // not open onto the store, and the store's own credential does not
            // open the door.
            assertEquals("403\n403", snippets.run("scim-blind").text());
        }

        @Test
        @Order(7)
        @DisplayName("and who is an administrator here is not the directory's to say")
        @Proving(DboPromises.SCIM_GROUPS_READ_ONLY)
        void roleGovernanceDoesNotArriveByProvisioning() throws Exception {
            assertTrue(snippets.run("scim-groups").text().contains("read-only"),
                    "role governance arrived by provisioning");
        }

    }

    /**
     * Somebody asks to be forgotten, their number resolves to nobody, and the
     * record keeps its shape while losing the person.
     *
     * <p><b>Last, and irreversibly so.</b> It erases a patient of its own for
     * that reason. A promise proven here cannot assume anything it erases is
     * still available to a later story, because there is no later story.
     */
    @Nested
    @Order(20)
    @DisplayName("being forgotten")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BeingForgotten {

        @Test
        @Order(1)
        @DisplayName("somebody asks to be forgotten")
        @Proving({DboPromises.PDI_ERASURE_IS_A_RUN, DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT})
        void somebodyAsksToBeForgotten() throws Exception {
            assertEquals(0, snippets.run("somebody-to-forget").status(),
                    "the patient who asks to be forgotten was not written");
            String receipt = snippets.run("erasure-ask", "token").text();
            assertTrue(receipt.contains("\"run\""),
                    "the erasure answered without a run to show for it: " + receipt);
        }

        @Test
        @Order(2)
        @DisplayName("and their number resolves to nobody")
        @Proving({DboPromises.PDI_UNFINDABLE_AFTER_ERASURE, DboPromises.PDI_EXACT_RESOLUTION})
        void andTheirNumberResolvesToNobody() throws Exception {
            assertEquals("0 found", snippets.run("erasure-unfindable").lastLine(),
                    "an erased person is still resolvable by their number");
        }

        @Test
        @Order(3)
        @DisplayName("while the record keeps its shape and loses the person")
        @Proving({DboPromises.PDI_CRYPTO_SHREDDING, DboPromises.POL_ERASURE_COMPATIBLE})
        void theRecordKeepsItsShapeAndLosesThePerson() throws Exception {
            // Shredding never rewrites a record. What is gone is gone because the
            // key is, so the record is still there and still a Patient.
            String remains = snippets.run("erasure-remains").text();
            assertTrue(remains.contains("\"resourceType\":\"Patient\""),
                    "the record lost its shape as well as its person: " + remains);
            for (String element : java.util.List.of("name", "identifier", "birthDate")) {
                assertTrue(!remains.contains("\"" + element + "\""),
                        "the erased record still carries the person's " + element + ": " + remains);
            }
        }

        @Test
        @Order(4)
        @DisplayName("while the trail still says something happened to her record, and can "
                + "no longer say to whom")
        @Proving(DboPromises.POL_ERASURE_COMPATIBLE)
        void theTrailOutlivesThePerson() throws Exception {
            // Scoped to her record. A page of the tenant's trail would be an
            // answer about whatever it did lately, not about whether the
            // account of HER request survived — and erasure is the one place
            // that distinction has to hold.
            String trail = ask("HOSPITAL",
                    "/AuditEvent?entity=Patient/" + snippets.recall("forgettable"));
            assertTrue(entries(trail) > 0,
                    "the account went with the person, so the clinic cannot show it handled "
                            + "her request at all: " + trail);
            assertTrue(!trail.contains("Riddle"),
                    "the trail still names her, so erasure stopped at the record: " + trail);
        }

    }

    /** A read on the provisioning door, which is not the store's own surface. */
    private String scim(String pathAndQuery) throws Exception {
        Snippets.Ran ran = snippets.sh(
                "curl -sf -H \"Authorization: Bearer $DIRECTORY\" \"$SCIM" + pathAndQuery + "\"");
        assertEquals(0, ran.status(), "the door answered nothing for " + pathAndQuery
                + ": " + ran.err());
        return ran.text();
    }

    /** The same, when the answer being looked for is a refusal. */
    private String scimCode(String pathAndQuery) throws Exception {
        Snippets.Ran ran = snippets.sh("curl -s -o /dev/null -w '%{http_code}' "
                + "-H \"Authorization: Bearer $DIRECTORY\" \"$SCIM" + pathAndQuery + "\"");
        assertEquals(0, ran.status(), "the request was never made: " + ran.err());
        return ran.lastLine();
    }

    /** A SCIM filter, encoded the way a URL needs it. */
    private static String query(String filter) {
        return java.net.URLEncoder.encode(filter, java.nio.charset.StandardCharsets.UTF_8);
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
