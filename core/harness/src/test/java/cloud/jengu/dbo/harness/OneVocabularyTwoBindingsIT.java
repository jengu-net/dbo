package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.asking.Across;
import cloud.jengu.dbo.asking.Asking;
import cloud.jengu.dbo.asking.Questions;
import cloud.jengu.dbo.core.api.StoredObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import cloud.jengu.dbo.work.Scope;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One vocabulary, two bindings, and a caller that cannot tell which it holds.
 *
 * <p>This is the claim the whole shape rests on, and the only thing that
 * proves it is a scene written once and run twice. Everything below takes
 * {@link Questions} — the interface — so the test cannot accidentally use
 * something only one binding offers.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneVocabularyTwoBindingsIT {

    private static final String PROCESS = "two-bindings.example";
    private static final String STEP = "two-bindings.example.weigh";
    private static final String CASE = "two-bindings-" + java.util.UUID.randomUUID();

    private static final String SYSTEM = "urn:two-bindings:test";
    private static final String MINE = "two-bindings-" + java.util.UUID.randomUUID();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static SharedTenants.Tenant tenant;
    static Questions fromInside;
    static Questions fromAcross;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_IDENTIFIER);
        fromInside = Asking.at(tenant.engine());

        String bearer = tenant.token("two-bindings", "system/*.read", "system/*.write");
        fromAcross = Across.through(pathAndQuery -> {
            try {
                HttpResponse<String> answer = HTTP.send(
                        HttpRequest.newBuilder(URI.create(tenant.fhir() + pathAndQuery))
                                .header("Authorization", "Bearer " + bearer)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return answer.body();
            } catch (Exception failed) {
                throw new IllegalStateException("the tenant did not answer " + pathAndQuery,
                        failed);
            }
        });

        // Three runs of one case, so the questions about WORK have something
        // to be asked about: one a person has to look at, one an automation is
        // holding, one finished with.
        Runs runs = new Runs(tenant.engine(), Steps.of(
                StepDeclaration.of(STEP, "1", WorkModel.DOMAIN)));
        runs.held(runs.correlated(runs.pipeline(PROCESS, STEP, CASE + "/a",
                List.of(WorkModel.DOMAIN)), CASE), Holder.PERSON);
        runs.claim(runs.correlated(runs.pipeline(PROCESS, STEP, CASE + "/b",
                        List.of(WorkModel.DOMAIN)), CASE),
                new Executor("weigher", "1", "example", Scope.BASELINE),
                java.time.Instant.now().plusSeconds(600));
        runs.closed(runs.claim(runs.correlated(runs.pipeline(PROCESS, STEP, CASE + "/c",
                        List.of(WorkModel.DOMAIN)), CASE),
                new Executor("weigher", "1", "example", Scope.BASELINE),
                java.time.Instant.now().plusSeconds(600)).orElseThrow());

        for (String state : List.of("final", "final", "preliminary")) {
            tenant.store().create(("{\"resourceType\":\"Observation\",\"status\":\"" + state
                    + "\",\"code\":{\"coding\":[{\"system\":\"" + SYSTEM + "\",\"code\":\""
                    + MINE + "\"}]},\"subject\":{\"display\":\"nobody\"}}"));
        }
    }

    /** The scene, written once. Which binding it is handed is the test. */
    private static long howManyOfMine(Questions asking) {
        return asking.records("Observation").whereCoded("code", SYSTEM, MINE).count();
    }

    private static List<String> theirIds(Questions asking) {
        try (Stream<StoredObject> mine = asking.records("Observation")
                .whereCoded("code", SYSTEM, MINE)
                .stream()) {
            return mine.map(StoredObject::id).sorted().toList();
        }
    }

    @Test
    @DisplayName("the same scene counts the same from inside the deployment and from across "
            + "a network")
    void bothBindingsCountTheSame() {
        assertEquals(3, howManyOfMine(fromInside), "the store's own answer is wrong");
        assertEquals(howManyOfMine(fromInside), howManyOfMine(fromAcross),
                "the two bindings disagree about how many there are, so a caller CAN tell "
                        + "which one it is holding");
    }

    @Test
    @DisplayName("and walks the same records, with the same ids")
    void bothBindingsWalkTheSame() {
        List<String> inside = theirIds(fromInside);
        List<String> across = theirIds(fromAcross);

        assertEquals(3, inside.size(), "the store's own walk is wrong");
        assertEquals(inside, across,
                "the two bindings handed back different records, or the same records under "
                        + "different ids: inside=" + inside + " across=" + across);
        assertTrue(inside.stream().noneMatch(String::isBlank),
                "a record came back without the id it is addressed by");
    }

    @Test
    @DisplayName("open names the holders that still owe something, because a negation asked a "
            + "different question and the surface never answered it")
    void openAsksForTheHoldersThatStillOwe() {
        // Pinned on the question rather than on an answer, because what went
        // wrong was the question. `status:not=completed` reads as open and is
        // not: Holder.NOBODY is "done, OR abandoned", so an abandoned run's
        // Task is not completed and the surface called it open while the store
        // called it closed. A word in this vocabulary may not mean two things.
        List<String> asked = new ArrayList<>();
        Questions recording = Across.through(pathAndQuery -> {
            asked.add(pathAndQuery);
            return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0,"
                    + "\"entry\":[]}";
        });

        recording.work().open().count();

        assertEquals(1, asked.size(), "one question, one request: " + asked);
        assertTrue(asked.get(0).contains("owner=automation,retry,person"),
                "open did not ask for the holders that still owe: " + asked.get(0));
        assertFalse(asked.get(0).contains(":not"),
                "open asked a negation, which this surface does not answer: " + asked.get(0));
    }

    @Test
    @DisplayName("a count the tenant will not answer is refused, rather than handed back as "
            + "a number meaning there was no number")
    void aCountThatCannotBeAnsweredRefuses() {
        // The defect this exists for: the reader turned "no total in the
        // answer" into minus one, and a caller would have put minus one on a
        // screen with nothing saying anything had gone wrong. A wrong answer
        // that looks right is the failure this store refuses everywhere else.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> fromAcross.records("NoSuchTypeHere").count());

        assertTrue(refused.getMessage().contains("could not be answered by"),
                "the refusal did not say that the count failed: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("NoSuchTypeHere"),
                "the refusal did not say what was asked, which is the useful half: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("the same work is counted from inside the deployment and from across a "
            + "network, which the surface could not answer at all until it served a run search")
    void bothBindingsCountTheSameWork() {
        assertEquals(2, fromInside.work().correlated(CASE).open().count(),
                "the store's own answer is wrong");
        assertEquals(fromInside.work().correlated(CASE).open().count(),
                fromAcross.work().correlated(CASE).open().count(),
                "the two bindings disagree about how much work is open, so a caller CAN tell "
                        + "which one it is holding");
    }

    @Test
    @DisplayName("walking work across the wire is not answerable yet, and says so rather than "
            + "handing back runs it could not read")
    void walkingWorkAcrossIsNotAnswerableYet() {
        // The surface serves a run search now, and what comes back is a Task —
        // the face's rendering of a run, not the run. Run.of reads the store's
        // own form, so the walk cannot complete until the rendering is
        // reversible. Counting is unaffected, because a count is a number.
        //
        // Held here so the gap is a failing expectation rather than a surprise
        // in somebody's screen: the day the translation lands, this test is
        // what says so.
        assertThrows(RuntimeException.class,
                () -> {
                    try (Stream<Run> walking = fromAcross.work().correlated(CASE).open().stream()) {
                        walking.forEach(run -> { });
                    }
                },
                "walking work across the wire now works, so this test should become the "
                        + "parity assertion it is standing in for");
    }

    @Test
    @DisplayName("a question the surface cannot narrow is refused there and answered here, "
            + "and the refusal says which")
    void whereTheBindingsHonestlyDiffer() {
        // The one place they are allowed to differ, and it is said out loud.
        // A run's scope is engine state; the face renders no parameter for it.
        // Answering it across the wire would mean reading every run and
        // narrowing in this process.
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> fromAcross.work().inScope("anything"));
        assertTrue(refused.getMessage().contains("no parameter on this tenant's surface"),
                "the refusal did not say why: " + refused.getMessage());

        // The same question from inside is ordinary, because the envelope
        // carries it.
        assertTrue(fromInside.work().inScope("anything").count() >= 0,
                "the store cannot narrow by a path its own envelope carries");
    }
}
