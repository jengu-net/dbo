package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.auth.Scopes;
import cloud.jengu.dbo.auth.TenantAuthority.AuthContext;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Run;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether automation may take a task, decided at the step door as the task is
 * authored, from what its step declares and what the task was given.
 *
 * <p>Over the door itself, on a socket: the decision is made where a run is
 * started, so that is where it is proven. The store underneath is a map.
 */
class AStepSaysWhenAutomationMayTakeItTest {

    private static final String STEP = "lab.result.verify";

    private final InMemory held = new InMemory();
    private final Runs runs = new Runs(held.store());
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    @BeforeEach
    void theLabsStepDoor() throws Exception {
        TenantSpec.Step verify = new TenantSpec.Step(STEP, Map.of("result", "Observation"),
                java.util.Set.of(), null, cloud.jengu.dbo.core.process.AutomationCriterion
                .compile("result.interpretation.coding.code = 'N'",
                        Map.of("result", "Observation"), (type, element) -> false));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StepSurface door = new StepSurface(
                bearer -> Optional.of(new AuthContext(bearer, null, null, List.of(Scopes.WORK),
                        null, List.of())),
                runs, null, held.store(), List.of(verify), "/t/lab/run", true, "lab",
                (tenant, step) -> null, id -> Optional.empty());
        server.createContext("/t/lab/step", door);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/t/lab/step/";
    }

    @AfterEach
    void closed() {
        server.stop(0);
    }

    @Test
    @DisplayName("a result the step's condition admits is open to automation, and one it does "
            + "not is authored open to people alone, saying why")
    @Proving(DboPromises.PROC_AUTOMATION_TAKES_ONLY_WHAT_ITS_STEP_ADMITS)
    void theConditionIsDecidedAsTheTaskIsAuthored() throws Exception {
        Run normal = started("N");
        Run high = started("H");

        assertTrue(normal.automation(), "a normal result was kept from automation: " + normal);
        assertFalse(high.automation(), "an abnormal result was left to automation: " + high);
        assertTrue(String.valueOf(high.statusReason()).contains("interpretation"),
                "the task does not say why it is for people: " + high.statusReason());
        assertFalse(high.forAutomation(Instant.now()));
    }

    private Run started(String interpretation) throws Exception {
        HttpResponse<String> answered = client.send(HttpRequest.newBuilder(URI.create(base + STEP))
                        .header("Authorization", "Bearer bench")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"inputs":{"result":{"resourceType":"Observation",
                                 "status":"final","code":{"text":"potassium"},
                                 "interpretation":[{"coding":[{"code":"%s"}]}]}}}"""
                                .formatted(interpretation))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, answered.statusCode(), answered.body());
        String id = answered.body().replaceAll("(?s).*\"run\":\"([^\"]+)\".*", "$1");
        return runs.byId(id).orElseThrow();
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
