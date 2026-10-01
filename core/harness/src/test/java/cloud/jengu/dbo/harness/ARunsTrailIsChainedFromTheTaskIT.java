package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunChain;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.SealedWork;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trail whose oldest links were pruned reads unchained, not broken.
 *
 * <p>The chain from a task through every hop and opening is walked in the edge
 * roundtrip story, on the sample world. What it cannot show there is a trail
 * that lost its predecessors to retention: that needs a store whose trail this
 * test holds in its hand, so it can remove the first link and ask what the
 * verdict is. So it runs here, over a database of its own and an in-process
 * lane, with no runtime at all.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARunsTrailIsChainedFromTheTaskIT {

    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen")
            .taking("order", "https://meristem.example/shape/order");

    static final KeyPair sealing = KeyWrap.newParticipantKeyPair();
    static final KeyPair signing = SigningKey.newKeyPair();

    @Test
    @DisplayName("a pruned predecessor reads unchained, not broken: the chain closes from the "
            + "earliest link still held, while a hole in the middle is still a hole")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aPrunedPredecessorReadsUnchained() throws Exception {
        // In-process, over a trail this test holds, so retention can be
        // played by hand: the links are a list, and pruning removes the first.
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ARunsTrailIsChainedFromTheTaskIT_pruned"));
        ds.setUser(SharedPostgres.username());
        ds.setPassword(SharedPostgres.password());
        List<TypeRegistration> types = new ArrayList<>(WorkModel.registrations());
        types.add(new TypeRegistration("Basic", WorkModel.DOMAIN, IdentityClass.INTERNAL,
                Set.of(), Handling.operational(), (type, payload) -> new Envelope(), List.of()));
        PgObjectStore store = new PgObjectStore(ds, types);
        Runs local = new Runs(store);
        List<RunChain.Link> trail = new ArrayList<>();
        Executor identity = executor();
        Lane lane = Lane.inProcess("t-pruned", local, new PgChangeFeed(ds, WorkModel.DOMAIN),
                new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                        Duration.ofSeconds(30)),
                "analyser", identity, store, null, Lane.Entitlement.everything(), null,
                new Lane.Trail() {
                    @Override
                    public void handedTo(Run r, String to, RunChain.Link link) {
                        trail.add(link);
                    }

                    @Override
                    public void opened(Run r, String by, String t, String id, RunChain.Link link) {
                        trail.add(link);
                    }

                    @Override
                    public List<RunChain.Link> links(Run r) {
                        return List.copyOf(trail);
                    }
                }, new Lane.Keys() {
                    @Override
                    public Optional<ParticipantKey> of(String participant) {
                        return Optional.of(ParticipantKey.of(sealing.getPublic()));
                    }

                    @Override
                    public Optional<SigningKey> signing(String participant) {
                        return Optional.of(SigningKey.of(signing.getPublic()));
                    }
                });
        String a = store.put(PutRequest.create("Basic", "{}".getBytes(StandardCharsets.UTF_8))).id();
        String b = store.put(PutRequest.create("Basic", "{}".getBytes(StandardCharsets.UTF_8))).id();
        Run run = local.of(ASSAY, RunKind.PIPELINE, "pruned",
                Map.of("specimen", "Basic/" + a, "order", "Basic/" + b));
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        String head = lane.sealed(held).manifest().head();
        head = open(lane, held, "Basic/" + a, head);
        head = open(lane, held, "Basic/" + b, head);
        assertEquals(3, trail.size());

        // Retention takes the oldest: the hop. What remains commits to a
        // link nobody holds, and that is the shape pruning leaves — one
        // start, nothing before it.
        RunChain.Link hop = trail.remove(0);
        RunChain.Verdict verdict = RunChain.verify(held, trail);
        assertTrue(verdict.complete() && verdict.unchained(),
                "unchained rather than broken: " + verdict);
        final String committed = head;
        lane.closed(held, committed);
        assertEquals(Holder.NOBODY, local.byKey(held.key()).orElseThrow().holder(),
                "the run closed on what the trail still holds");

        // A hole in the middle is a different shape: the link after it
        // commits to something absent while what came before is present.
        List<RunChain.Link> holed = new ArrayList<>(List.of(hop, trail.get(1)));
        RunChain.Verdict broken = RunChain.verify(held, holed);
        assertFalse(broken.complete(), "a missing middle is a hole: " + broken);
        assertEquals(trail.get(0).link(), broken.missingBefore(),
                "and the verdict names the link that is missing");
    }

    // ------------------------------------------------------------ fixtures

    private static String open(Lane lane, Run run, String reference, String previous) {
        String link = RunChain.accessLink(previous, run.key(), reference, "analyser");
        return lane.opened(run, reference,
                new RunChain.Link("access", previous, link, "analyser", reference, sign(link)));
    }

    private static Executor executor() {
        return new Executor("analyser", "1.0", "cloud.jengu.test", Scope.BASELINE);
    }

    private static String sign(String link) {
        return SigningKey.sign(link.getBytes(StandardCharsets.UTF_8), signing.getPrivate());
    }
}
