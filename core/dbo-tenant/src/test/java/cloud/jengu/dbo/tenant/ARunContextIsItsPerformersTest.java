package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority.AuthContext;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who a run's context answers: the client holding the run, and nobody else.
 *
 * <p>Over the door itself, on a socket, because the defect this proves was in
 * the door: it admitted any credential that may act in work and then served
 * whichever held run the path named. A rule asked in isolation would have been
 * right and the door around it still open.
 *
 * <p>The store underneath is a map. What the door asks of it is a run by id
 * and an advance of one, and the run's record is the real one — written and
 * read by {@link Runs} — so what the door decides on is what a tenant holds.
 */
class ARunContextIsItsPerformersTest {

    private static final String PATIENT = "Patient/0190a000-0000-7000-8000-00000000a001";
    private static final String PORTER = "porter";
    private static final String STRANGER = "another-porter";
    private static final String BENCH = "bench";

    private final InMemory held = new InMemory();
    private final Runs runs = new Runs(held.store());
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    @BeforeEach
    void theWardsRunDoor() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StepSurface door = new StepSurface(
                bearer -> Optional.of(new AuthContext(bearer, null, null, List.of(Scopes.WORK),
                        null, List.of())),
                runs, documents(), held.store(), List.of(), "/t/ward/run", false, "ward",
                (tenant, step) -> null, id -> Optional.empty());
        server.createContext("/t/ward/run", door);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/t/ward/run/";
    }

    @AfterEach
    void closed() {
        server.stop(0);
    }

    @Test
    @DisplayName("the client that started a run at the step door reads its context and ends it")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS)
    void theStarterReadsAndEndsItsRun() throws Exception {
        Run run = started(PORTER);

        assertEquals(200, get(run, "/fhir/metadata", PORTER).statusCode());
        assertEquals(200, get(run, "/fhir/" + PATIENT, PORTER).statusCode());
        assertEquals(200, done(run, PORTER).statusCode());
        assertEquals(cloud.jengu.dbo.work.Status.COMPLETED, runs.byId(run.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("another client that may act in work, holding the run's id, reads neither its "
            + "metadata nor its document and cannot end it — answered as for a run that never "
            + "existed")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS)
    void anotherWorkClientIsAnsweredAsIfTheRunDidNotExist() throws Exception {
        Run run = started(PORTER);
        String invented = "0190a000-0000-7000-8000-00000000beef";

        HttpResponse<String> metadata = get(run, "/fhir/metadata", STRANGER);
        HttpResponse<String> document = get(run, "/fhir/" + PATIENT, STRANGER);
        HttpResponse<String> ended = done(run, STRANGER);
        assertEquals(404, metadata.statusCode(), metadata.body());
        assertEquals(404, document.statusCode(), document.body());
        assertEquals(404, ended.statusCode(), ended.body());
        assertEquals(send(base + invented + "/fhir/" + PATIENT, "GET", STRANGER).body(),
                document.body(), "a run somebody else holds answered differently from one "
                        + "that never existed");
        assertEquals(send(base + invented + "/done", "POST", STRANGER).body(), ended.body(),
                "ending somebody else's run answered differently from ending none");

        // Nothing it asked changed anything, and the performer is untouched.
        assertEquals(cloud.jengu.dbo.work.Status.READY,
                runs.byId(run.id()).orElseThrow().status(),
                "another client ended a run it does not hold");
        assertEquals(200, get(run, "/fhir/" + PATIENT, PORTER).statusCode());
    }

    @Test
    @DisplayName("once a participant claims the run on a lane, the context is the claimant's "
            + "and the client that started it is answered as a stranger")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS)
    void aClaimMovesTheContextToTheClaimant() throws Exception {
        Run run = started(PORTER);
        runs.claim(run, new Executor(BENCH, "1", "example.bench", Scope.BASELINE),
                Instant.now().plus(Duration.ofMinutes(5)), BENCH).orElseThrow();

        assertEquals(200, get(run, "/fhir/" + PATIENT, BENCH).statusCode());
        assertEquals(404, get(run, "/fhir/" + PATIENT, PORTER).statusCode());
        assertEquals(404, done(run, PORTER).statusCode());
        assertEquals(200, done(run, BENCH).statusCode());
    }

    @Test
    @DisplayName("a held run no client holds — authored anywhere but the step door, and not "
            + "claimed — answers every client as a run that never existed")
    @Proving(DboPromises.PROC_A_RUN_CONTEXT_IS_ITS_PERFORMERS)
    void aRunNoClientHoldsAnswersNobody() throws Exception {
        Run run = started(null);

        assertEquals(cloud.jengu.dbo.work.Status.READY, run.status());
        assertEquals(404, get(run, "/fhir/metadata", PORTER).statusCode());
        assertEquals(404, get(run, "/fhir/" + PATIENT, PORTER).statusCode());
        assertEquals(404, done(run, PORTER).statusCode());
    }

    private Run started(String requester) {
        return runs.filling(StepDeclaration.of("ward.porter.fetch", "1", "r5")
                        .taking("patient", "Patient"), RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(),
                Map.of("patient", RunSlot.referring(PATIENT)), requester);
    }

    private HttpResponse<String> get(Run run, String path, String bearer) throws Exception {
        return send(base + run.id() + path, "GET", bearer);
    }

    private HttpResponse<String> done(Run run, String bearer) throws Exception {
        return send(base + run.id() + "/done", "POST", bearer);
    }

    private HttpResponse<String> send(String url, String method, String bearer)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearer)
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every document a run names is held: what is decided is reach, not presence. */
    private static FhirStoreFacade documents() {
        return (FhirStoreFacade) Proxy.newProxyInstance(
                FhirStoreFacade.class.getClassLoader(), new Class<?>[] {FhirStoreFacade.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readForServing")) {
                        return new FhirStoreFacade.ReadResult("{\"resourceType\":\"" + args[0]
                                + "\",\"id\":\"" + args[1] + "\"}", 1, Instant.now());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Runs by id and by key, versioned, and nothing else a run's door reaches for. */
    private static final class InMemory {

        private final Map<String, StoredObject> byId = new ConcurrentHashMap<>();
        private final Map<String, String> byIdentifier = new ConcurrentHashMap<>();

        ObjectStore store() {
            return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                    new Class<?>[] {ObjectStore.class}, (proxy, method, args) ->
                            switch (method.getName()) {
                                case "get" -> Optional.ofNullable(byId.get(args[1]));
                                case "getByIdentifier" -> ((List<?>) args[1]).stream()
                                        .map(identifier -> byIdentifier.get(
                                                ((cloud.jengu.dbo.core.api.Identifier) identifier)
                                                        .value()))
                                        .filter(java.util.Objects::nonNull)
                                        .map(byId::get).toList();
                                case "putIfAbsent" -> putIfAbsent((IdentityRef) args[0],
                                        (PutRequest) args[1]);
                                case "put" -> put((PutRequest) args[0]);
                                case "select" -> List.of();
                                case "count" -> 0L;
                                default -> throw new UnsupportedOperationException(
                                        method.getName());
                            });
        }

        private PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
            String value = ((IdentityRef.ByIdentifier) identity).identifier().value();
            String existing = byIdentifier.get(value);
            if (existing != null) {
                return new PutResult(existing, byId.get(existing).versionId(), false);
            }
            String id = cloud.jengu.dbo.core.UuidV7.newId();
            byIdentifier.put(value, id);
            byId.put(id, stored(id, request.typeName(), 1, request.payload()));
            return new PutResult(id, 1, true);
        }

        private PutResult put(PutRequest request) {
            StoredObject current = byId.get(request.id());
            long version = current == null ? 1 : current.versionId() + 1;
            if (request.expectedVersion() != null && current != null
                    && current.versionId() != request.expectedVersion()) {
                throw new VersionConflictException(request.typeName(), request.id(),
                        request.expectedVersion(), current.versionId());
            }
            byId.put(request.id(), stored(request.id(), request.typeName(), version,
                    request.payload()));
            return new PutResult(request.id(), version, current == null);
        }

        private static StoredObject stored(String id, String type, long version, byte[] payload) {
            return new StoredObject(id, type, version, Instant.now(), payload, false, null, null,
                    null);
        }
    }
}
