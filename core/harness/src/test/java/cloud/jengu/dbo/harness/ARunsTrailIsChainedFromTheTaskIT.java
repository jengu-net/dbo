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
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A run's travel and access entries are chained from the task the store
 * minted, and the result that closes the run is the chain's last link.
 *
 * <p>The chain has teeth because links come home as they happen: what
 * arrived cannot be retracted, so the only way to break it is to stop. The
 * store walks the chain when the result lands, and a completion with a
 * hole is refused and told which link, so the run stays owed with a named
 * participant and a named gap rather than closing on its own word.
 *
 * <p>What the chain cannot do is compel a link that was never made. An
 * intended recipient can open a payload and never say so; the alternative
 * was declined knowing the cost, and the test says nothing it cannot show.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARunsTrailIsChainedFromTheTaskIT {

    private static final String TENANT = "chainhost";
    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen")
            .taking("order", "https://meristem.example/shape/order");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static ObjectStore engine;
    static Runs runs;
    static KeyPair sealing;
    static KeyPair signing;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-chained-trail");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ARunsTrailIsChainedFromTheTaskIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("analyser", "analyser-secret",
                List.of("work/" + STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        HttpLane.to(laneUri, () -> token(), TENANT, "analyser", executor()).introduce(ASSAY);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @DisplayName("claimed, opened twice, closed: every link commits to the one before, the "
            + "first to the task, the openings are signed, and the result carries the head")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aCleanRunClosesOnItsChain() throws Exception {
        Run run = twoInputRun("clean");
        HttpLane lane = HttpLane.holding(laneUri, () -> token(), TENANT, "analyser",
                executor(), sealing.getPrivate(), signing.getPrivate());
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        assertEquals(2, lane.inputs(held).size(), "both documents opened");
        lane.closed(held);

        Run closed = runs.byKey(held.key()).orElseThrow();
        assertEquals(Holder.NOBODY, closed.holder(), "the run closed on a chain with no hole");

        List<Map<String, String>> chain = chainOf(held);
        assertEquals(3, chain.size(), "a travel entry and two access entries: " + chain);
        assertEquals("travel", chain.get(0).get("code"));
        assertEquals(RunChain.root(held), chain.get(0).get("previous"),
                "the first link commits to the task, which the store minted: " + chain.get(0));
        assertEquals("analyser", chain.get(0).get("to"), "a travel entry names who it handed to");
        assertEquals(chain.get(0).get("link"), chain.get(1).get("previous"),
                "the first opening commits to the hop");
        assertEquals(chain.get(1).get("link"), chain.get(2).get("previous"),
                "the second opening commits to the first");
        assertTrue(chain.get(1).get("signature") != null && chain.get(2).get("signature") != null,
                "the participant signed its openings: " + chain);
        assertTrue(SigningKey.of(signing.getPublic()).verifies(
                        chain.get(2).get("link").getBytes(StandardCharsets.UTF_8),
                        chain.get(2).get("signature")),
                "and the signature is the analyser's, checkable by anybody holding the "
                        + "public half it enrolled with");
    }

    @Test
    @DisplayName("a suppressed middle link is exposed by the next: the opening that commits "
            + "to it is refused, naming the link the store never received")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aSuppressedLinkIsExposedByTheNext() throws Exception {
        Run run = twoInputRun("suppressed");
        HttpLane lane = HttpLane.holding(laneUri, () -> token(), TENANT, "analyser",
                executor(), sealing.getPrivate(), signing.getPrivate());
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        SealedWork work = lane.sealed(held);
        String head = work.manifest().head();

        // The analyser opens the specimen, computes its link — and never
        // sends it. Then it opens the order and sends that one, committing
        // to the link it kept to itself.
        String suppressed = RunChain.accessLink(head, held.key(), reference(held, "specimen"),
                "analyser");
        String next = RunChain.accessLink(suppressed, held.key(), reference(held, "order"),
                "analyser");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.opened(held, reference(held, "order"),
                        new RunChain.Link("access", suppressed, next, "analyser",
                                reference(held, "order"), sign(next))));
        assertTrue(refused.getMessage().contains(suppressed)
                        && refused.getMessage().contains("missing before"),
                "the refusal names the link the store never received: " + refused.getMessage());
        assertEquals(1, chainOf(held).size(), "nothing after the hop landed on the chain");
        assertNotEquals(Holder.NOBODY, runs.byKey(held.key()).orElseThrow().holder(),
                "and the run is still owed");
    }

    @Test
    @DisplayName("a result whose head does not match the trail's is refused by name, and the "
            + "run stays owed; a chain that stops leaves the run owed with its opening on record")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aMismatchedHeadIsRefusedAndAStoppedChainStaysOwed() throws Exception {
        Run run = twoInputRun("mismatch");
        HttpLane lane = HttpLane.holding(laneUri, () -> token(), TENANT, "analyser",
                executor(), sealing.getPrivate(), signing.getPrivate());
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
        lane.inputs(held);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.closed(held, "not-the-head"));
        assertTrue(refused.getMessage().contains("not-the-head")
                        && refused.getMessage().contains("the trail's head is"),
                "told which head it committed to and which the trail holds: "
                        + refused.getMessage());
        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> lane.closed(held, null));
        assertTrue(none.getMessage().contains("carries none"),
                "a participant that signs closes with the head it commits to: " + none.getMessage());

        // The chain that stops: two openings on record, no result. The run
        // stays owed, and the trail holds the pair of facts an investigation
        // starts from — opened, and not finished.
        Run owed = runs.byKey(held.key()).orElseThrow();
        assertNotEquals(Holder.NOBODY, owed.holder(), "no result, no close");
        assertEquals(2, chainOf(held).stream().filter(e -> "access".equals(e.get("code"))).count(),
                "the openings are on record even though the result never came");
    }

    @Test
    @DisplayName("a pruned predecessor reads unchained, not broken: the chain closes from the "
            + "earliest link still held, while a hole in the middle is still a hole")
    @Proving(DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK)
    void aPrunedPredecessorReadsUnchained() throws Exception {
        // In-process, over a trail this test holds, so retention can be
        // played by hand: the links are a list, and pruning removes the first.
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ARunsTrailIsChainedFromTheTaskIT_pruned"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
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

    private static String sign(String link) {
        return SigningKey.sign(link.getBytes(StandardCharsets.UTF_8), signing.getPrivate());
    }

    private static Run twoInputRun(String key) {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        String order = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(ASSAY, RunKind.PIPELINE, key,
                Map.of("specimen", "Basic/" + specimen, "order", "Basic/" + order));
    }

    private static String reference(Run run, String slot) {
        return run.inputs().get(slot);
    }

    /** The run's chain entries as recorded, in the order they were written. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> chainOf(Run run) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var entry : engine.select(Criteria.of("AuditEntry")
                .eq("run", cloud.jengu.dbo.core.api.EnvelopeValue.of(run.key())))) {
            String json = new String(entry.payload(), StandardCharsets.UTF_8);
            Object node = cloud.jengu.dbo.core.wire.RecordWire.read(json);
            Map<String, Object> map = (Map<String, Object>) node;
            if (!(map.get("detail") instanceof Map<?, ?> detail) || detail.get("link") == null) {
                continue;
            }
            Map<String, String> flat = new java.util.LinkedHashMap<>();
            flat.put("code", String.valueOf(map.get("code")));
            detail.forEach((k, v) -> flat.put(String.valueOf(k), String.valueOf(v)));
            out.add(flat);
        }
        return out;
    }

    private static Executor executor() {
        return new Executor("analyser", "1.0", "cloud.jengu.test", Scope.BASELINE);
    }

    private static String token() {
        try {
            String form = "grant_type=client_credentials&client_id=analyser&client_secret="
                    + URLEncoder.encode("analyser-secret", StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
            assertTrue(m.find(), body);
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
