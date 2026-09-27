package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run over a referred object, a given one, and several of them.
 *
 * <p>On the shared runtime, because nothing here is about a tenant: it needs
 * one that holds a Basic, and what is being proven is what the STORE does with
 * each shape a slot can be declared as.
 *
 * <p>Through a real lane over a port rather than in process, so that what is
 * asserted is what a participant is actually handed — a reference resolved
 * where the data is, an object carried with the run and resolved by nothing,
 * and a repeating slot arriving in the order it was filled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ASlotIsReferredOrGivenIT {

    private static final String STEP = "shapes.over.both";

    private static final StepDeclaration OVER_BOTH =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("held", "Basic")
                    .taking("proposed", "Basic")
                    .taking("notes", "Basic");

    static SharedTenants.Tenant tenant;
    static ObjectStore engine;
    static Runs runs;
    static Lane lane;

    @BeforeAll
    void up() {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        engine = tenant.engine();
        runs = new Runs(engine);
        tenant.authority().ensureClient("shapes", "shapes-secret", List.of("work/" + STEP));
        URI at = URI.create(tenant.base() + "/work");
        lane = HttpLane.to(at, () -> token(), tenant.code(), "shapes",
                new Executor("shapes", "1", "cloud.jengu.test", Scope.BASELINE));
        lane.introduce(OVER_BOTH);
    }

    @Test
    @DisplayName("a referred slot is resolved where the data is, a given one is carried, and a "
            + "repeating slot arrives in the order it was filled")
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    void allThreeShapesArrive() {
        String stored = write("the record this tenant already holds");

        Run run = runs.filling(OVER_BOTH, RunKind.PIPELINE, "shapes/" + stored, Map.of(
                "held", RunSlot.referring("Basic/" + stored),
                "proposed", RunSlot.given(basic("a proposal with no record anywhere")),
                "notes", RunSlot.givenAll(List.of(basic("first"), basic("second")))));

        Map<String, List<StoredObject>> inputs = inputsOf(run);

        assertEquals("the record this tenant already holds", textOf(inputs, "held", 0),
                "the referred slot did not arrive as the object it names, so a reference "
                        + "reached a participant that has no way to resolve one");
        assertEquals("a proposal with no record anywhere", textOf(inputs, "proposed", 0),
                "the given object did not arrive, so an object sent with a run is lost "
                        + "between the door and whoever performs it");

        // ORDER, asserted as order. A list somebody sent is a list they meant,
        // and a set would pass this while losing the one thing a repeat adds.
        assertEquals(2, inputs.get("notes").size(),
                "the repeating slot did not arrive whole: " + inputs.get("notes"));
        assertEquals("first", textOf(inputs, "notes", 0));
        assertEquals("second", textOf(inputs, "notes", 1),
                "the repeating slot arrived out of the order it was filled in");
    }

    @Test
    @DisplayName("a given object is not a record: no id and no version, so nothing can read it "
            + "again or write a version over it")
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    void aGivenObjectIsNotARecord() {
        String stored = write("held");
        Run run = runs.filling(OVER_BOTH, RunKind.PIPELINE, "shapes-record/" + stored, Map.of(
                "held", RunSlot.referring("Basic/" + stored),
                "proposed", RunSlot.given(basic("given")),
                "notes", RunSlot.givenAll(List.of(basic("one")))));

        Map<String, List<StoredObject>> inputs = inputsOf(run);

        StoredObject given = inputs.get("proposed").get(0);
        assertNull(given.id(),
                "a given object arrived with an id, so a participant can treat something that "
                        + "was never stored as a record — read it again, or write a version of "
                        + "it over a record that does not exist");
        assertEquals(0L, given.versionId(),
                "a given object arrived with a version, which is the same mistake as an id");
        assertEquals("Basic", given.typeName(),
                "a given object did not say its own type, so nothing downstream can tell what "
                        + "it is without the declaration in hand");

        // And the referred one is the opposite in both, which is what makes
        // the assertions above about the given object rather than about a
        // delivery path that loses ids either way.
        StoredObject referred = inputs.get("held").get(0);
        assertEquals(stored, referred.id());
        assertTrue(referred.versionId() > 0,
                "the referred object carries no version either, so this proves nothing");
    }

    /** Claimed and resolved, the way a participant gets its work. */
    private static Map<String, List<StoredObject>> inputsOf(Run run) {
        Run held = lane.claim(run, Duration.ofMinutes(5))
                .orElseThrow(() -> new AssertionError("the run could not be claimed"));
        return lane.inputs(held);
    }

    private static String textOf(Map<String, List<StoredObject>> inputs, String slot, int at) {
        String json = new String(inputs.get(slot).get(at).payload(), StandardCharsets.UTF_8);
        int from = json.indexOf("\"text\":\"") + "\"text\":\"".length();
        return json.substring(from, json.indexOf('"', from));
    }

    private static String write(String text) {
        return engine.put(PutRequest.create("Basic",
                basic(text).getBytes(StandardCharsets.UTF_8))).id();
    }

    private static String basic(String text) {
        return "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + text + "\"}}";
    }

    private static String token() {
        try {
            String form = "grant_type=client_credentials&client_id=shapes"
                    + "&client_secret=shapes-secret";
            String body = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(
                                    URI.create(tenant.base() + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            java.util.regex.Matcher found = java.util.regex.Pattern
                    .compile("\"access_token\":\"([^\"]+)\"").matcher(body);
            assertTrue(found.find(), body);
            return found.group(1);
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no token for the participant", failed);
        }
    }
}
