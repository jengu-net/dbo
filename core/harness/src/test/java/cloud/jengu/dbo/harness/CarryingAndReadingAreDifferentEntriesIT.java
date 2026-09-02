package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.policy.AuditModel;
import cloud.jengu.dbo.policy.PolicyObjectStore;
import cloud.jengu.dbo.policy.TenantPolicies;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrying a piece of work and reading the document it names are different
 * things, and the trail says which happened.
 *
 * <p>Before this, a hop left nothing and a participant's read of a named
 * document was an ordinary read — recorded or not by the tenant's audit
 * level, and carrying no sign of the run that occasioned it. So "who has read
 * this document" could not distinguish a clinician at a screen from a step
 * that was handed it, and "where did this task go" had no answer at all.
 *
 * <p>Now a hop leaves a <b>travel</b> entry about the <em>task</em>, naming
 * who it was handed to; and a participant's read leaves an <b>access</b> entry
 * about the <em>document</em>, in the place every other reading of it lands,
 * naming the run as its occasion — recorded whatever the audit level, because
 * a document handed to a participant is a disclosure and not a preference
 * about volume.
 *
 * <p>The audit level here is deliberately <b>writes</b>, not full. The claim
 * is that the work-driven read is recorded regardless, and a level that
 * records every read would prove nothing about that.
 *
 * <p>One clause of the promise waits for sealing: that the machinery's own
 * read to seal a payload records nothing. Today nothing is sealed, so the
 * resolution read <em>is</em> the opening and is rightly recorded; the clause
 * becomes provable when the read starts yielding ciphertext.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarryingAndReadingAreDifferentEntriesIT {

    private static final StepDeclaration ASSAY =
            StepDeclaration.of("lab.result.assay", "1.0", WorkModel.DOMAIN)
                    .taking("specimen", "https://meristem.example/shape/specimen");

    static PGSimpleDataSource ds;
    static PolicyObjectStore store;
    static Runs runs;
    static Declarations declarations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("CarryingAndReadingAreDifferentEntriesIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        List<TypeRegistration> types = new ArrayList<>(WorkModel.registrations());
        types.addAll(AuditModel.registrations());
        types.add(new TypeRegistration("Basic", WorkModel.DOMAIN, IdentityClass.INTERNAL,
                Set.of(), Handling.operational(), (type, payload) -> new Envelope(), List.of()));
        store = new PolicyObjectStore(new PgObjectStore(ds, types),
                TenantPolicies.parse(Map.of("audit", Map.of("level", "writes"))));
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    @AfterEach
    void nobodyIsCalling() {
        Caller.clear();
    }

    /** A lane for one participant, writing hops into the tenant's trail as the tenant would. */
    private Lane lane(String participant) {
        Executor identity = new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE);
        return Lane.inProcess("t-trail", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, participant, identity, store, null,
                Lane.Entitlement.everything(), null,
                new Lane.Trail() {
                    @Override
                    public void handedTo(Run run, String to) {
                        store.recordCustom("travel", WorkModel.TYPE, run.id(),
                                Map.of("to", to, "key", run.key()));
                    }

                    @Override
                    public void opened(Run run, String by, String typeName, String id) {
                        throw new AssertionError("nothing is sealed here, so nothing is opened");
                    }
                });
    }

    @Test
    @DisplayName("carried twice, opened once: two travel entries on the task, one access entry "
            + "on the document naming the run, and nothing else on either")
    @Proving({DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
            DboPromises.WF_HOPS_AUDITED})
    void carriedTwiceOpenedOnce() {
        String specimen = store.put(PutRequest.create("Basic",
                "{\"kind\":\"specimen\"}".getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "carried-twice",
                Map.of("specimen", "Basic/" + specimen));

        Caller.set("bench-one");
        Lane first = lane("bench-one");
        Run held = first.claim(run, Duration.ofMinutes(5)).orElseThrow();
        first.inputs(held);
        first.released(held, "handing it on");

        Caller.set("bench-two");
        Lane second = lane("bench-two");
        second.claim(runs.byKey(run.key()).orElseThrow(), Duration.ofMinutes(5)).orElseThrow();

        List<String> onTheTask = entries(WorkModel.TYPE, run.id());
        List<String> hops = onTheTask.stream()
                .filter(e -> e.contains("\"code\":\"travel\"")).toList();
        assertEquals(2, hops.size(),
                "two hands took this work, and the task's trail must say so twice: "
                        + onTheTask);
        assertTrue(hops.get(0).contains("\"to\":\"bench-one\"")
                        && hops.get(1).contains("\"to\":\"bench-two\""),
                "each travel entry names who it was handed to, in order: " + hops);
        assertTrue(onTheTask.stream().noneMatch(e -> e.contains("\"interaction\":\"read\"")),
                "a hop was recorded as a reading — carrying is not looking: " + onTheTask);

        List<String> onTheDocument = entries("Basic", specimen);
        List<String> opened = onTheDocument.stream()
                .filter(e -> e.contains("\"interaction\":\"read\""))
                .filter(e -> e.contains("\"run\":\"" + run.key() + "\""))
                .toList();
        assertEquals(1, opened.size(),
                "the document was opened once, by the participant that resolved it, and "
                        + "the entry must land on the document naming the run: " + onTheDocument);
        assertTrue(opened.get(0).contains("\"actor\":\"bench-one\""),
                "and it names who opened it, from the caller seam: " + opened.get(0));
        assertTrue(onTheDocument.stream().noneMatch(e -> e.contains("\"code\":\"travel\"")),
                "a travel entry landed on the document, so a hop reads as a disclosure: "
                        + onTheDocument);
    }

    @Test
    @DisplayName("carried but never opened: a travel entry on the task and no access entry "
            + "anywhere — the absence is the assertion")
    @Proving({DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES,
            DboPromises.WF_HOPS_AUDITED})
    void carriedAndNeverOpened() {
        Run bare = runs.pipeline("lab.result", "archive", "lab.result/archive/carried-unopened",
                List.of(WorkModel.DOMAIN));

        Caller.set("bench-three");
        Lane lane = lane("bench-three");
        Run held = lane.claim(bare, Duration.ofMinutes(5)).orElseThrow();
        assertEquals(Map.of(), lane.inputs(held), "no slots, nothing resolved");

        assertEquals(1, entries(WorkModel.TYPE, bare.id()).stream()
                        .filter(e -> e.contains("\"code\":\"travel\"")).count(),
                "the hop is on the task's trail");
        assertTrue(store.select(Criteria.of("AuditEntry")).stream()
                        .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                        .noneMatch(e -> e.contains("\"run\":\"" + bare.key() + "\"")
                                && e.contains("\"interaction\":\"read\"")),
                "nothing was opened for this run, and the trail must be able to say so: "
                        + "an access entry appeared for a run that resolved nothing");
    }

    @Test
    @DisplayName("an ordinary read outside any run follows the tenant's audit level, so the "
            + "work-driven one is recorded because of the run and not because of the level")
    @Proving(DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES)
    void anOrdinaryReadIsNotAnOpening() {
        String document = store.put(PutRequest.create("Basic",
                "{\"kind\":\"unrelated\"}".getBytes(StandardCharsets.UTF_8))).id();

        Caller.set("a-clinician");
        store.get("Basic", document);

        assertTrue(entries("Basic", document).stream()
                        .noneMatch(e -> e.contains("\"interaction\":\"read\"")),
                "the level is writes, so an ordinary read records nothing — which is what "
                        + "makes the work-driven read above evidence of the run rather than of "
                        + "the level");
    }

    /** Every entry on the trail about one target, oldest first, as text. */
    private static List<String> entries(String targetType, String targetId) {
        return store.select(Criteria.of("AuditEntry")).stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .filter(e -> e.contains("\"targetType\":\"" + targetType + "\""))
                .filter(e -> e.contains("\"targetId\":\"" + targetId + "\""))
                .toList();
    }
}
