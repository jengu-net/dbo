package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunChain;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.SealedWork;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thing that can reach the store is the participant, and it holds the
 * claim; the edge behind it holds the key and does the work.
 *
 * <p>A router claims work it cannot read, names its edge as the recipient
 * so the payload is sealed past it, carries the edge's signed opening home,
 * waits for the edge's answer, and closes on the head the chain reached.
 * Naming the edge is the forward, and leaves the travel link that makes the
 * edge the chain's next author. A router that returns before its edge
 * answers is the shape the contract forbids; what the store can show is what
 * the right shape produces when the edge never answers — the claim lapses
 * and the run reads released, visibly still owed.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARouterHoldsTheClaimIT {

    static SharedTenants.Tenant tenant;
    static String TENANT;
    // Named for this class rather than for the domain: a step declaration
    // is tenant-scoped and keyed by name, so two classes sharing a tenant
    // and declaring the same step fight over its version. That is what
    // happened here — one class introduced it at 1.0 while another
    // expected 2.1, and the failure read as the lane losing a declaration.
    private static final String STEP = "dbo.router.assay";
    private static final String MARKER = "specimen-plaintext-4a71";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static ObjectStore engine;
    static Runs runs;
    static KeyPair edgeSealing;
    static KeyPair edgeSigning;
    static HttpLane gateway;

    @BeforeAll
    void up() throws Exception {
        // Shared. What this proves is about a router holding a claim it
        // cannot open, not about a tenant: it needs somewhere to keep a Basic
        // and two enrolled participants, and a shared tenant takes both
        // without the next class noticing.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        TENANT = tenant.code();
        laneUri = URI.create(tenant.base() + "/work");
        engine = tenant.engine();
        runs = new Runs(engine);

        // The gateway: a credential and no keys — it opens nothing. The
        // edge: enrolled with both keys, reachable only through the gateway.
        tenant.authority().ensureClient("gateway", "gateway-secret", List.of("work/" + STEP));
        edgeSealing = KeyWrap.newParticipantKeyPair();
        edgeSigning = SigningKey.newKeyPair();
        tenant.authority().ensureClient("bench-7", "bench-secret", List.of(),
                ParticipantKey.of(edgeSealing.getPublic()), SigningKey.of(edgeSigning.getPublic()));
        tenant.authority().ensureClient("bench-9", "bench-secret", List.of());
        gateway = HttpLane.to(laneUri, () -> token("gateway", "gateway-secret"), TENANT, "gateway",
                new Executor("gateway", "1.0", "cloud.jengu.test", Scope.BASELINE));
        gateway.introduce(ASSAY);
        gateway.routes(List.of(
                Trackable.routed("bench-7", "analyser", "gateway", Map.of("power", "on")),
                Trackable.routed("bench-9", "analyser", "gateway", Map.of("power", "on"))));
    }

    @Test
    @DisplayName("the router claims, names its edge as the recipient, cannot open what it "
            + "carries, forwards the edge's signed opening, and closes on the chain the edge left")
    @Proving({DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM,
            DboPromises.POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK})
    void theRouterHoldsTheClaimAndTheEdgeHoldsTheKey() throws Exception {
        Run run = runFor("routed");
        Run held = gateway.claim(run, Duration.ofMinutes(5)).orElseThrow();

        SealedWork work = gateway.sealed(held, List.of("bench-7"));
        assertEquals(List.of("bench-7"), work.manifest().recipients(),
                "sealed past the router, to the edge it named");
        assertEquals(1, work.payload().size());
        assertFalse(work.payload().get(0).wrapped().containsKey("gateway"),
                "the router holds no wrap it could use: " + work.payload().get(0).wrapped().keySet());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> work.payload().get(0).open("gateway", edgeSealing.getPrivate()),
                "and is not among those the payload was sealed to");

        // The edge, behind the router: opens with its own keys, signs its
        // link, and the router carries it home.
        StoredObject opened = work.payload().get(0).open("bench-7", edgeSealing.getPrivate());
        assertTrue(new String(opened.payload(), StandardCharsets.UTF_8).contains(MARKER));
        String reference = work.payload().get(0).reference();
        String previous = work.manifest().head();
        String link = RunChain.accessLink(previous, held.key(), reference, "bench-7");
        String head = gateway.opened(held, reference, new RunChain.Link("access", previous, link,
                "bench-7", reference,
                SigningKey.sign(link.getBytes(StandardCharsets.UTF_8), edgeSigning.getPrivate())));
        assertEquals(link, head);

        // The edge answered; the router reports, committing to the head.
        gateway.closed(held, head);
        assertEquals(Holder.NOBODY, runs.byKey(held.key()).orElseThrow().holder());

        List<Map<String, String>> chain = chainOf(held);
        assertEquals(List.of("travel", "travel", "access"),
                chain.stream().map(e -> e.get("code")).toList(), chain.toString());
        assertEquals("gateway", chain.get(0).get("to"), "the store handed the run to the router");
        assertEquals("bench-7", chain.get(1).get("to"), "and the router handed it on — the forward "
                + "is a hop on the task's journey, and it names who it handed to");
        assertEquals(chain.get(0).get("link"), chain.get(1).get("previous"));
        assertEquals(chain.get(1).get("link"), chain.get(2).get("previous"),
                "the edge's opening commits to the forward, which made it the next author");
        assertEquals("bench-7", chain.get(2).get("by"), "the opening is the edge's, not the router's");
        assertTrue(SigningKey.of(edgeSigning.getPublic()).verifies(
                chain.get(2).get("link").getBytes(StandardCharsets.UTF_8),
                chain.get(2).get("signature")), "signed with the edge's key, not the router's");
    }

    @Test
    @DisplayName("a router seals only past itself to what it declared behind it, and only to a "
            + "routee that offered a key; an opening it carries is its routee's or nobody's")
    @Proving(DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM)
    void aRouterSealsToItsOwnEdgesOnly() throws Exception {
        Run held = gateway.claim(runFor("strangers"), Duration.ofMinutes(5)).orElseThrow();

        IllegalStateException stranger = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held, List.of("somebody-else")));
        assertTrue(stranger.getMessage().contains("has not declared 'somebody-else' behind it"),
                stranger.getMessage());
        IllegalStateException keyless = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held, List.of("bench-9")));
        assertTrue(keyless.getMessage().contains("'bench-9' offered no key"), keyless.getMessage());
        IllegalStateException itself = assertThrows(IllegalStateException.class,
                () -> gateway.sealed(held));
        assertTrue(itself.getMessage().contains("'gateway' offered no key"), itself.getMessage());

        String reference = held.inputs().get("specimen");
        IllegalStateException forged = assertThrows(IllegalStateException.class,
                () -> gateway.opened(held, reference, new RunChain.Link("access",
                        RunChain.root(held), "x", "somebody-else", reference, "sig")));
        assertTrue(forged.getMessage().contains("cannot report an opening of its"),
                forged.getMessage());
        assertTrue(chainOf(held).stream().noneMatch(e -> "bench-9".equals(e.get("to"))),
                "a refused seal handed nothing on");
    }

    @Test
    @DisplayName("a router whose edge never answers waits, the claim lapses, the run reads "
            + "released and still owed, and a late report is refused")
    @Proving({DboPromises.PROC_DONE_MEANS_DONE, DboPromises.PROC_THE_ROUTER_HOLDS_THE_CLAIM})
    void aWedgedEdgeLetsTheClaimLapse() throws Exception {
        Run held = gateway.claim(runFor("wedged"), Duration.ofSeconds(1)).orElseThrow();
        gateway.sealed(held, List.of("bench-7"));
        // The router's perform is blocked on an edge that will not answer.
        // Nothing is reported, because nothing has happened.
        Thread.sleep(1500);
        assertTrue(gateway.releaseLapsed() >= 1, "the lapsed claim is released by the machinery");

        Run released = runs.byKey(held.key()).orElseThrow();
        assertNotEquals(Holder.NOBODY, released.holder(), "released is not done");
        assertTrue(released.assignment() != null && released.assignment().executor() == null
                        && String.valueOf(released.assignment().note()).contains("lapsed"),
                "the run says released, and why: " + released.assignment());

        // The edge answers late; the router's report lands on a claim it no
        // longer holds and is refused, rather than closing work somebody
        // else may by now be doing.
        assertThrows(RuntimeException.class,
                () -> gateway.closed(held, RunChain.root(held)),
                "a report after the claim lapsed does not close the run");
        assertNotEquals(Holder.NOBODY, runs.byKey(held.key()).orElseThrow().holder());
    }

    // ------------------------------------------------------------ fixtures

    private static Run runFor(String key) {
        String specimen = engine.put(PutRequest.create("Basic",
                ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + MARKER + "\"}}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(ASSAY, RunKind.PIPELINE, key, Map.of("specimen", "Basic/" + specimen));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> chainOf(Run run) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var entry : engine.select(Criteria.of("AuditEntry")
                .eq("run", EnvelopeValue.of(run.key())))) {
            Map<String, Object> map = (Map<String, Object>) cloud.jengu.dbo.core.wire.RecordWire
                    .read(new String(entry.payload(), StandardCharsets.UTF_8));
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

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create(tenant.base() + "/oidc/token"))
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
