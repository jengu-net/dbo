package cloud.jengu.dbo.tenant;

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
import cloud.jengu.dbo.work.Status;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person takes a run with their own token, as a role the tenant holds, and
 * the run's context is then theirs.
 *
 * <p>Over the door itself, on a socket. The tokens are read the way the
 * tenant's authority reads them — a client, the practitioner it names, and
 * what the role grants — and the roles are the tenant's record of who holds
 * which.
 */
class APersonTakesARunAsTheirRoleTest {

    private static final String PATIENT = "Patient/0190a000-0000-7000-8000-00000000a001";
    private static final String STEP = "ward.porter.fetch";

    private final InMemory held = new InMemory();
    /** Run once, just before the next write lands — the other writer arriving in between. */
    private final java.util.concurrent.atomic.AtomicReference<Runnable> beforeTheNextWrite =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final Runs runs = new Runs(racing(held.store()));
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    /** Who each bearer is: two nurses, a clerk with no role, and a nurse for another step. */
    private static Optional<AuthContext> signedIn(String bearer) {
        return switch (bearer) {
            case "hermione" -> Optional.of(new AuthContext("person-1", "Practitioner/p1", null,
                    List.of("work/" + STEP), null, List.of()));
            case "poppy" -> Optional.of(new AuthContext("person-2", "Practitioner/p2", null,
                    List.of("work/" + STEP), null, List.of()));
            case "filch" -> Optional.of(new AuthContext("person-3", "Practitioner/p3", null,
                    List.of("work/" + STEP), null, List.of()));
            case "elsewhere" -> Optional.of(new AuthContext("person-4", "Practitioner/p1", null,
                    List.of("work/ward.kitchen.cook"), null, List.of()));
            default -> Optional.empty();
        };
    }

    @BeforeEach
    void theWardsRunDoor() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StepSurface door = new StepSurface(APersonTakesARunAsTheirRoleTest::signedIn,
                practitioner -> switch (practitioner) {
                    case "p1" -> List.of("nurse-1");
                    case "p2" -> List.of("nurse-2");
                    default -> List.of();
                },
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
    @DisplayName("a nurse takes a waiting run as the role they hold, reads what it was given, "
            + "and ends it; the run names the role and no executor")
    @Proving(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE)
    void aNurseTakesReadsAndEndsARun() throws Exception {
        Run run = started(null);

        HttpResponse<String> taken = send(base + run.id() + "/claim", "POST", "hermione");
        assertEquals(200, taken.statusCode(), taken.body());
        Run holding = runs.byId(run.id()).orElseThrow();
        assertEquals(Status.IN_PROGRESS, holding.status());
        assertEquals("PractitionerRole/nurse-1", holding.assignment().role());
        assertNull(holding.assignment().executor(), "an executor was named on a person's run");
        assertEquals(200, get(run, "/fhir/" + PATIENT, "hermione").statusCode());
        assertEquals(200, send(base + run.id() + "/checkpoint", "POST", "hermione").statusCode());
        assertEquals(200, send(base + run.id() + "/done", "POST", "hermione").statusCode());
        assertEquals(Status.COMPLETED, runs.byId(run.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("while a nurse holds the run nobody else reads it, ends it or takes it")
    @Proving({DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE,
            DboPromises.PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN})
    void theHolderAloneIsAnswered() throws Exception {
        Run run = started(null);
        assertEquals(200, send(base + run.id() + "/claim", "POST", "hermione").statusCode());

        assertEquals(404, get(run, "/fhir/" + PATIENT, "poppy").statusCode());
        assertEquals(404, send(base + run.id() + "/done", "POST", "poppy").statusCode());
        assertEquals(409, send(base + run.id() + "/claim", "POST", "poppy").statusCode(),
                "a second person took a run somebody holds");
        assertEquals("PractitionerRole/nurse-1",
                runs.byId(run.id()).orElseThrow().assignment().role());
    }

    @Test
    @DisplayName("somebody holding no role here, or whose work does not reach the step, is "
            + "answered as for a run that never existed")
    @Proving(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE)
    void noRoleNoRun() throws Exception {
        Run run = started(null);
        String invented = "0190a000-0000-7000-8000-00000000beef";

        HttpResponse<String> clerk = send(base + run.id() + "/claim", "POST", "filch");
        HttpResponse<String> other = send(base + run.id() + "/claim", "POST", "elsewhere");
        assertEquals(404, clerk.statusCode(), clerk.body());
        assertEquals(404, other.statusCode(), other.body());
        assertEquals(send(base + invented + "/claim", "POST", "hermione").body(), clerk.body());
        assertTrue(runs.byId(run.id()).orElseThrow().open()
                && !runs.byId(run.id()).orElseThrow().claimed(Instant.now()));
    }

    @Test
    @DisplayName("a nurse whose lapsed lease is handed back and taken by another between the "
            + "door's look and the close is answered as a stranger, and the other's claim "
            + "stands")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aLateCloseAtTheDoorLosesTheRace() throws Exception {
        Run run = started(null);
        assertEquals(200, send(base + run.id() + "/claim", "POST", "hermione").statusCode());
        beforeTheNextWrite.set(() -> handedBackAndTakenByPoppy(run));

        HttpResponse<String> late = done(run, "hermione");

        assertEquals(404, late.statusCode(), late.body());
        assertEquals(strangersDone(), late.body(),
                "a close that lost its run answered differently from a run that never existed");
        Run after = runs.byId(run.id()).orElseThrow();
        assertEquals(Status.IN_PROGRESS, after.status(), "the late close ended the run");
        assertEquals("PractitionerRole/nurse-2", after.assignment().role(),
                "the late close undid the next nurse's claim");
        assertEquals("person-2", after.assignment().claimant());
    }

    @Test
    @DisplayName("a nurse whose lapsed lease is handed back and taken by another between the "
            + "door's look and the checkpoint extends nothing, and is answered as a stranger")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aLateCheckpointAtTheDoorLosesTheRace() throws Exception {
        Run run = started(null);
        assertEquals(200, send(base + run.id() + "/claim", "POST", "hermione").statusCode());
        beforeTheNextWrite.set(() -> handedBackAndTakenByPoppy(run));

        HttpResponse<String> late = send(base + run.id() + "/checkpoint", "POST", "hermione");
        Run taken = runs.byId(run.id()).orElseThrow();

        assertEquals(404, late.statusCode(), late.body());
        assertEquals(send(base + INVENTED + "/checkpoint", "POST", "hermione").body(),
                late.body(), "a checkpoint that lost its run answered differently from a run "
                        + "that never existed");
        assertEquals("PractitionerRole/nurse-2", taken.assignment().role());
        assertEquals(taken.versionId(), runs.byId(run.id()).orElseThrow().versionId(),
                "the late checkpoint still wrote to the next nurse's run");
    }

    @Test
    @DisplayName("the client that started a run, closing it as a lane takes it over, is "
            + "answered as a stranger and the lane's claim stands")
    @Proving(DboPromises.PROC_ONLY_THE_HOLDER_ACTS_ON_A_RUN)
    void aStartersLateCloseLosesToALane() throws Exception {
        Run run = started("person-1");
        cloud.jengu.dbo.work.Executor lane = new cloud.jengu.dbo.work.Executor("porter-bot",
                "1", "example.ward", cloud.jengu.dbo.work.Scope.BASELINE);
        beforeTheNextWrite.set(() -> runs.claim(runs.byId(run.id()).orElseThrow(), lane,
                java.time.Duration.ofMinutes(5), "porter-bot").orElseThrow());

        HttpResponse<String> late = done(run, "hermione");

        assertEquals(404, late.statusCode(), late.body());
        assertEquals(strangersDone(), late.body());
        Run after = runs.byId(run.id()).orElseThrow();
        assertEquals(Status.IN_PROGRESS, after.status(), "the starter's late close ended the "
                + "run a lane holds");
        assertTrue(after.heldBy(lane), "the starter's late close undid the lane's claim: "
                + after.assignment());
    }

    private static final String INVENTED = "0190a000-0000-7000-8000-00000000beef";

    /** What a close by somebody holding nothing is answered, word for word. */
    private String strangersDone() throws Exception {
        return send(base + INVENTED + "/done", "POST", "hermione").body();
    }

    /**
     * Housekeeping finding the first nurse's lease lapsed and handing it
     * back, and the second nurse taking the run — both landing after the
     * door looked and before it wrote.
     */
    private void handedBackAndTakenByPoppy(Run run) {
        Run holding = runs.byId(run.id()).orElseThrow();
        Instant lapsed = holding.assignment().until().plusSeconds(1);
        runs.handBack(holding, lapsed).orElseThrow();
        runs.claimAsPerson(runs.byId(run.id()).orElseThrow(), "PractitionerRole/nurse-2",
                java.time.Duration.ofMinutes(30), "person-2").orElseThrow();
    }

    /** The store, letting a test put another writer between a read and the write after it. */
    private ObjectStore racing(ObjectStore store) {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) -> {
                    if ("put".equals(method.getName())) {
                        Runnable other = beforeTheNextWrite.getAndSet(null);
                        if (other != null) {
                            other.run();
                        }
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }

    private Run started(String requester) {
        return runs.filling(StepDeclaration.of(STEP, "1", "r5")
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
