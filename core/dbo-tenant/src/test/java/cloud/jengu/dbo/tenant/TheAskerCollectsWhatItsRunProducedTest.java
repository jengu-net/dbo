package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority.AuthContext;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Disclosure;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application that asked for a run collects what it was given and what it
 * produced, for a window after the work is over, as the audience the step
 * names — and nobody else does, and nothing more.
 *
 * <p>Over the door itself, on a socket, with the door's clock in the test's
 * hand: the window is a comparison with the clock, so standing at the instant
 * it shuts proves the lapse without waiting for it.
 *
 * <p>The face underneath is a recorder. What the door decides is which read it
 * makes and under what — the mode, the purpose, the run occasioning it and the
 * client acting — and those are the seams the vault and the trail read. Each
 * read the recorder sees under a run is the reading the policy layer writes an
 * access entry for, whatever the tenant's audit level.
 */
class TheAskerCollectsWhatItsRunProducedTest {

    private static final String GIVEN = "Patient/0190a000-0000-7000-8000-00000000a001";
    private static final String WRITTEN = "Patient/0190a000-0000-7000-8000-00000000a002";
    private static final String ASKER = "the-clinic";
    private static final String BENCH = "bench";
    private static final String STRANGER = "another-clinic";
    private static final Duration COLLECT = Duration.ofMinutes(15);

    private static final TenantSpec.Step DESK = new TenantSpec.Step("care.records.register",
            Map.of("patient", "Reference(Patient)"), Set.of("Patient"), null, null,
            new TenantSpec.Answer("desk", Set.of("Patient"), Disclosure.Mode.OMIT, COLLECT,
                    null));
    private static final TenantSpec.Step WARD = new TenantSpec.Step("care.records.treat",
            Map.of("patient", "Reference(Patient)"), Set.of("Patient"), null, null,
            new TenantSpec.Answer("ward", Set.of("Patient"), Disclosure.Mode.INCLUDE, COLLECT,
                    "TREAT"));
    private static final TenantSpec.Step BLIND = new TenantSpec.Step("care.records.count",
            Map.of("patient", "Reference(Patient)"), Set.of("Patient"), null, null,
            new TenantSpec.Answer("auditor", Set.of("Encounter"), Disclosure.Mode.OMIT,
                    COLLECT, null));
    private static final TenantSpec.Step SILENT = new TenantSpec.Step("care.records.file",
            Map.of("patient", "Reference(Patient)"), Set.of("Patient"));

    private final InMemory held = new InMemory();
    private final Runs runs = new Runs(held.store());
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Read> reads = new CopyOnWriteArrayList<>();
    private StepSurface door;
    private HttpServer server;
    private String base;

    /** One read the door made of the face, and everything it made it under. */
    record Read(String reference, Disclosure.Mode mode, String purpose, String run,
            String actor) {}

    @BeforeEach
    void theClinicsRunDoor() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        door = new StepSurface(
                bearer -> Optional.of(new AuthContext(bearer, null, null, List.of(Scopes.WORK),
                        null, List.of())),
                runs, documents(), held.store(), List.of(DESK, WARD, BLIND, SILENT),
                "/t/clinic/run", false, "clinic", (tenant, step) -> null,
                id -> Optional.empty());
        server.createContext("/t/clinic/run", door);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/t/clinic/run/";
    }

    @AfterEach
    void closed() {
        server.stop(0);
    }

    @Test
    @DisplayName("once the result is written, the asker reads what the run was given and the "
            + "version it produced, read as that version — and nobody holds the run")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void theAskerCollectsWithinItsWindow() throws Exception {
        Run run = answered(DESK);

        assertEquals(200, get(run, "/fhir/" + GIVEN, ASKER).statusCode());
        HttpResponse<String> version = get(run, "/fhir/" + WRITTEN + "/_history/1", ASKER);
        assertEquals(200, version.statusCode(), version.body());
        assertTrue(version.body().contains("\"version\":1"),
                "the asker was answered with something other than the version produced: "
                        + version.body());
        assertEquals(cloud.jengu.dbo.work.Status.COMPLETED, run.status());
        assertEquals(cloud.jengu.dbo.work.Awaits.NOTHING, run.awaits(Instant.now()),
                "collecting made a finished run read as owed");

        // What the run produced, by version and only so: the record as it
        // stands now, or another version of it, is not what the run did.
        assertEquals(404, get(run, "/fhir/" + WRITTEN, ASKER).statusCode());
        assertEquals(404, get(run, "/fhir/" + WRITTEN + "/_history/2", ASKER).statusCode());
        assertEquals(404, get(run, "/fhir/Patient/0190a000-0000-7000-8000-00000000beef",
                ASKER).statusCode());
    }

    @Test
    @DisplayName("the asker says it is done collecting and its window shuts now, after which "
            + "the run answers it as one that never existed")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void theAskerShutsItsWindow() throws Exception {
        Run run = answered(DESK);

        assertEquals(200, done(run, ASKER).statusCode());
        HttpResponse<String> after = get(run, "/fhir/" + GIVEN, ASKER);
        assertEquals(404, after.statusCode());
        assertEquals(never(GIVEN, ASKER).body(), after.body());
        assertEquals(never(GIVEN, ASKER).body(), done(run, ASKER).body(),
                "saying done twice answered differently from a run that never existed");
    }

    @Test
    @DisplayName("past the window the asker is answered as for a run that never existed, byte "
            + "for byte, with the clock and nothing else moved")
    @Proving(DboPromises.PROC_AN_UNCOLLECTED_ANSWER_LAPSES)
    void theWindowLapsesByTheClock() throws Exception {
        Run run = answered(DESK);
        Instant until = run.window().until();

        door.at(Clock.fixed(until.minusMillis(1), ZoneOffset.UTC));
        assertEquals(200, get(run, "/fhir/" + GIVEN, ASKER).statusCode(),
                "the window shut before its time");

        door.at(Clock.fixed(until, ZoneOffset.UTC));
        HttpResponse<String> lapsed = get(run, "/fhir/" + WRITTEN + "/_history/1", ASKER);
        assertEquals(404, lapsed.statusCode());
        assertEquals(never(WRITTEN + "/_history/1", ASKER).body(), lapsed.body(),
                "a lapsed window answered differently from a run that never existed");
        assertEquals(never("metadata", ASKER).body(), get(run, "/fhir/metadata", ASKER).body());
        assertEquals(run.versionId(), runs.byId(run.id()).orElseThrow().versionId(),
                "the window lapsing wrote to the run");
    }

    @Test
    @DisplayName("while a participant performs the run, its asker reads nothing of it")
    @Proving(DboPromises.PROC_THE_ASKER_READS_NOTHING_WHILE_THE_WORK_IS_DONE)
    void theAskerWaitsForTheWork() throws Exception {
        Run run = claimed(asked(DESK));

        HttpResponse<String> during = get(run, "/fhir/" + GIVEN, ASKER);
        assertEquals(404, during.statusCode());
        assertEquals(never(GIVEN, ASKER).body(), during.body(),
                "the asker was told, while the work was being done, that its run exists");
        assertEquals(200, get(run, "/fhir/" + GIVEN, BENCH).statusCode(),
                "the performer lost its context");

        Run answered = runs.closed(run, List.of(WRITTEN + "/1"));
        assertEquals(404, get(answered, "/fhir/" + GIVEN, BENCH).statusCode(),
                "the performer kept a context after its work was over");
        assertEquals(200, get(answered, "/fhir/" + GIVEN, ASKER).statusCode());
    }

    @Test
    @DisplayName("inside the window another client, holding the run's id, is answered as for "
            + "a run that never existed, and cannot shut the window")
    @Proving(DboPromises.PROC_A_RUN_IS_COLLECTED_BY_ITS_ASKER)
    void nobodyElseCollects() throws Exception {
        Run run = answered(DESK);

        HttpResponse<String> stranger = get(run, "/fhir/" + WRITTEN + "/_history/1", STRANGER);
        assertEquals(404, stranger.statusCode());
        assertEquals(never(WRITTEN + "/_history/1", STRANGER).body(), stranger.body());
        assertEquals(404, get(run, "/fhir/" + GIVEN, BENCH).statusCode(),
                "the performer reads on after the work is over");
        assertEquals(never(GIVEN, STRANGER).body(), done(run, STRANGER).body());
        assertEquals(200, get(run, "/fhir/" + GIVEN, ASKER).statusCode(),
                "another client shut the asker's window");
    }

    @Test
    @DisplayName("what the asker sees is the audience the step names: its mode, and only its "
            + "types; a step that names no audience leaves nothing to collect")
    @Proving(DboPromises.IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE)
    void theAskerIsTheStepsAudience() throws Exception {
        Run desk = answered(DESK);
        assertEquals(200, get(desk, "/fhir/" + WRITTEN + "/_history/1", ASKER).statusCode());
        assertEquals(Disclosure.Mode.OMIT, last().mode());
        // The request cannot raise it: a purpose stated to an omit audience
        // changes nothing it is shown.
        get(desk, "/fhir/" + WRITTEN + "/_history/1", ASKER, "TREAT");
        assertEquals(Disclosure.Mode.OMIT, last().mode(),
                "a purpose the request stated raised what the desk is shown");

        Run blind = answered(BLIND);
        HttpResponse<String> outsideItsTypes = get(blind, "/fhir/" + GIVEN, ASKER);
        assertEquals(404, outsideItsTypes.statusCode());
        assertEquals(get(desk, "/fhir/" + WRITTEN, ASKER).body().replace(WRITTEN, GIVEN),
                outsideItsTypes.body(), "a type outside the audience was refused in words "
                        + "that differ from a document the run was never given");
        assertTrue(!get(blind, "/fhir/metadata", ASKER).body().contains("Patient"),
                "the context lists a type its audience is not answered about");

        Run silent = runs.closed(asked(SILENT), List.of(WRITTEN + "/1"));
        assertEquals(never(GIVEN, ASKER).body(), get(silent, "/fhir/" + GIVEN, ASKER).body(),
                "a step declaring no answer opened its run to its asker");
    }

    @Test
    @DisplayName("a person is revealed whole only when the request states the purpose the "
            + "step declared; none, or another code, is the strict mode and never a refusal")
    @Proving(DboPromises.IDN_A_STEP_STATES_ITS_PURPOSE)
    void twoKeys() throws Exception {
        Run run = answered(WARD);
        String version = "/fhir/" + WRITTEN + "/_history/1";

        assertEquals(200, get(run, version, ASKER, "TREAT").statusCode());
        assertEquals(new Read(WRITTEN + "/_history/1", Disclosure.Mode.INCLUDE, "TREAT",
                run.key(), ASKER), last());

        assertEquals(200, get(run, version, ASKER).statusCode());
        assertEquals(Disclosure.Mode.OMIT, last().mode(),
                "the step's purpose alone revealed her");
        assertEquals(200, get(run, version, ASKER, "HRESCH").statusCode());
        assertEquals(Disclosure.Mode.OMIT, last().mode(),
                "a purpose the step never declared revealed her");
    }

    @Test
    @DisplayName("each collection is a reading of its own, acted by the asker's client and "
            + "occasioned by the run, and a version may be collected again")
    @Proving(DboPromises.POL_COLLECTING_IS_A_READING)
    void eachCollectionIsAReading() throws Exception {
        Run run = answered(WARD);

        get(run, "/fhir/" + WRITTEN + "/_history/1", ASKER, "TREAT");
        get(run, "/fhir/" + WRITTEN + "/_history/1", ASKER, "TREAT");
        get(run, "/fhir/" + GIVEN, ASKER);

        assertEquals(List.of(
                new Read(WRITTEN + "/_history/1", Disclosure.Mode.INCLUDE, "TREAT", run.key(),
                        ASKER),
                new Read(WRITTEN + "/_history/1", Disclosure.Mode.INCLUDE, "TREAT", run.key(),
                        ASKER),
                new Read(GIVEN, Disclosure.Mode.OMIT, "TREAT", run.key(), ASKER)), reads,
                "a collection was not a reading of its own, naming the asker, the run and the "
                        + "step's purpose");
    }

    // ── the run, asked for, performed elsewhere and answered ──

    private Run asked(TenantSpec.Step step) {
        return runs.filling(StepDeclaration.of(step.code(), "1", "r5")
                        .taking("patient", "Patient"), RunKind.PIPELINE,
                cloud.jengu.dbo.core.UuidV7.newId(),
                Map.of("patient", RunSlot.referring(GIVEN)), ASKER, null,
                step.answer() == null ? null : step.answer().collect());
    }

    private Run claimed(Run run) {
        return runs.claim(run, new Executor(BENCH, "1", "example.bench", Scope.BASELINE),
                Instant.now().plus(Duration.ofMinutes(5)), BENCH).orElseThrow();
    }

    private Run answered(TenantSpec.Step step) {
        return runs.closed(claimed(asked(step)), List.of(WRITTEN + "/1"));
    }

    private Read last() {
        return reads.get(reads.size() - 1);
    }

    private HttpResponse<String> never(String path, String bearer) throws Exception {
        return send(base + "0190a000-0000-7000-8000-00000000dead/fhir/" + path, "GET", bearer,
                null);
    }

    private HttpResponse<String> get(Run run, String path, String bearer) throws Exception {
        return send(base + run.id() + path, "GET", bearer, null);
    }

    private HttpResponse<String> get(Run run, String path, String bearer, String purpose)
            throws Exception {
        return send(base + run.id() + path, "GET", bearer, purpose);
    }

    private HttpResponse<String> done(Run run, String bearer) throws Exception {
        return send(base + run.id() + "/done", "POST", bearer, null);
    }

    private HttpResponse<String> send(String url, String method, String bearer, String purpose)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearer)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (purpose != null) {
            request.header(StepSurface.PURPOSE_OF_USE, purpose);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Every document held, at version 1 only; each read recorded with the
     * seams the vault and the trail read off the thread.
     */
    private FhirStoreFacade documents() {
        return (FhirStoreFacade) Proxy.newProxyInstance(
                FhirStoreFacade.class.getClassLoader(), new Class<?>[] {FhirStoreFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "readForServing" -> {
                        reads.add(new Read(args[0] + "/" + args[1], Disclosure.mode(),
                                Disclosure.purpose(), Caller.run(), Caller.current()));
                        yield new FhirStoreFacade.ReadResult("{\"resourceType\":\"" + args[0]
                                + "\",\"id\":\"" + args[1] + "\"}", 1, Instant.now());
                    }
                    case "versionForServing" -> {
                        reads.add(new Read(args[0] + "/" + args[1] + "/_history/" + args[2],
                                Disclosure.mode(), Disclosure.purpose(), Caller.run(),
                                Caller.current()));
                        yield (Long) args[2] == 1L ? new FhirStoreFacade.VersionRead(
                                "{\"resourceType\":\"" + args[0] + "\",\"id\":\"" + args[1]
                                        + "\",\"version\":1}", 1, Instant.now(), false) : null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
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
