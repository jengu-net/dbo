package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work leaves the tenant as a readable manifest and a sealed payload, and a
 * carrier holding no key reads the one and not the other.
 *
 * <p>Driven from outside the container over real HTTP with real tokens,
 * because the failure this repository keeps producing is a surface that was
 * built and never mounted. The carrier here is the wire itself: the bytes
 * the lane answers with, read as any router between the store and the
 * analyser would read them.
 *
 * <p>The tenant's audit level is deliberately <b>full</b>: every ordinary
 * read is recorded, which is what makes the absence of one for the sealing
 * read an assertion rather than a default.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkTravelsSealedIT {

    private static final String TENANT = "sealhost";
    private static final String STEP = "dbo.lab.assay";
    private static final String MARKER = "specimen-plaintext-9f2c";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static cloud.jengu.dbo.core.api.ObjectStore engine;
    static Runs runs;
    static KeyPair analyser;
    static KeyPair analyserSigning;
    static String analyserSecret = "analyser-secret";

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-sealed-work");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("WorkTravelsSealedIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"full"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);

        // The analyser generated its keypair before it was enrolled, and
        // offered the public half; the courier enrolled with none.
        analyser = KeyWrap.newParticipantKeyPair();
        analyserSigning = cloud.jengu.dbo.core.api.seal.SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("analyser", analyserSecret,
                List.of("work/" + STEP), ParticipantKey.of(analyser.getPublic()),
                cloud.jengu.dbo.core.api.seal.SigningKey.of(analyserSigning.getPublic()));
        manager.authority(TENANT).ensureClient("courier", "courier-secret",
                List.of("work/" + STEP));
        HttpLane.to(laneUri, () -> token("courier", "courier-secret"), TENANT, "courier",
                executor("courier")).introduce(ASSAY);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("the wire carries the manifest readable and the payload sealed; the analyser "
            + "opens it with the key it holds, and the opening lands on the document")
    @Proving({DboPromises.PROC_WORK_TRAVELS_SEALED,
            DboPromises.POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES})
    void aCarrierReadsTheManifestAndNotThePayload() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + MARKER + "\"}}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "sealed-out",
                Map.of("specimen", "Basic/" + specimen));

        HttpLane lane = HttpLane.holding(laneUri, () -> token("analyser", analyserSecret),
                TENANT, "analyser", executor("analyser"), analyser.getPrivate(),
                analyserSigning.getPrivate());
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        // The carrier's view: the bytes the lane answers with, as anything
        // between the store and the analyser sees them.
        String wire = sealedVerbRaw(held);
        assertTrue(wire.contains("\"tenant\":\"" + TENANT + "\"")
                        && wire.contains("\"step\":\"" + STEP + "\"")
                        && wire.contains("\"run\":\"" + held.key() + "\"")
                        && wire.contains("\"specimen\":\"Basic/" + specimen + "\""),
                "the manifest is readable — tenant, step, task, references — because "
                        + "routing on it is its job: " + wire);
        assertFalse(wire.contains(MARKER),
                "the payload is readable on the wire, so whatever carries this run can "
                        + "read it: " + wire);
        assertTrue(wire.contains("\"analyser\":\"{\\\"alg\\\":\\\"ECDH-ES+A256GCM\\\"")
                        || wire.contains("ECDH-ES+A256GCM"),
                "the data key is wrapped to the analyser, and to nobody else: " + wire);

        // The analyser's view: the same verb, opened where the key is.
        Map<String, StoredObject> inputs = lane.inputs(held);
        assertEquals(1, inputs.size(), inputs.toString());
        assertTrue(new String(inputs.get("specimen").payload(), StandardCharsets.UTF_8)
                .contains(MARKER), "the analyser, holding the private half, reads the document");

        // The trail: the sealing reads recorded nothing on the document —
        // at audit level full, where every ordinary read is recorded — and
        // the opening is there, once, naming the run.
        List<String> onTheDocument = entries("Basic", specimen);
        assertTrue(onTheDocument.stream().noneMatch(e -> e.contains("\"interaction\":\"read\"")),
                "a read that yields only ciphertext is not a disclosure, and the trail put "
                        + "one on the document anyway: " + onTheDocument);
        List<String> opened = onTheDocument.stream()
                .filter(e -> e.contains("\"code\":\"access\"")).toList();
        assertEquals(1, opened.size(), "opened once, by the analyser: " + onTheDocument);
        assertTrue(opened.get(0).contains("\"run\":\"" + held.key() + "\"")
                        && opened.get(0).contains("\"by\":\"analyser\""),
                "the access entry names the run as its occasion and who opened it: "
                        + opened.get(0));
        assertTrue(entries(WorkModel.TYPE, held.id()).stream()
                        .anyMatch(e -> e.contains("\"code\":\"travel\"")),
                "and the hop is on the task, where the journey belongs");
    }

    @Test
    @DisplayName("what a participant holds decides how its work arrives: a keyed one is refused "
            + "the clear verb, and a keyless one is refused the sealed verb — each by name")
    @Proving(DboPromises.PROC_WORK_TRAVELS_SEALED)
    void whatAParticipantHoldsDecides() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();

        Run forTheAnalyser = runs.of(ASSAY, RunKind.PIPELINE, "keyed-asks-clear",
                Map.of("specimen", "Basic/" + specimen));
        HttpLane keyed = HttpLane.to(laneUri, () -> token("analyser", analyserSecret),
                TENANT, "analyser", executor("analyser"));
        Run heldByAnalyser = keyed.claim(forTheAnalyser, Duration.ofMinutes(5)).orElseThrow();
        IllegalStateException clearRefused = assertThrows(IllegalStateException.class,
                () -> keyed.inputs(heldByAnalyser));
        assertTrue(clearRefused.getMessage().contains("travel sealed"),
                "a participant that offered a key never receives its inputs in the clear, "
                        + "not even when it asks: " + clearRefused.getMessage());

        Run forTheCourier = runs.of(ASSAY, RunKind.PIPELINE, "keyless-asks-sealed",
                Map.of("specimen", "Basic/" + specimen));
        HttpLane keyless = HttpLane.to(laneUri, () -> token("courier", "courier-secret"),
                TENANT, "courier", executor("courier"));
        Run heldByCourier = keyless.claim(forTheCourier, Duration.ofMinutes(5)).orElseThrow();
        IllegalStateException sealRefused = assertThrows(IllegalStateException.class,
                () -> keyless.sealed(heldByCourier));
        assertTrue(sealRefused.getMessage().contains("nothing to seal to"),
                "a seal to a participant that offered no key is refused by name rather "
                        + "than made under something the carrier could hold: "
                        + sealRefused.getMessage());
    }

    // ------------------------------------------------------------ fixtures

    private static Executor executor(String name) {
        return new Executor(name, "1.0", "cloud.jengu.test", Scope.BASELINE);
    }

    /** The sealed verb as a carrier sees it: the raw answer, uninterpreted. */
    private static String sealedVerbRaw(Run run) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("participant", "analyser");
        body.put("identity", RecordWire.encode(executor("analyser")));
        body.put("run", RecordWire.encode(run));
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(laneUri + "/sealed"))
                        .header("Authorization", "Bearer " + token("analyser", analyserSecret))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(RecordWire.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    private static List<String> entries(String targetType, String targetId) {
        return engine.select(Criteria.of("AuditEntry")).stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .filter(e -> e.contains("\"targetType\":\"" + targetType + "\""))
                .filter(e -> e.contains("\"targetId\":\"" + targetId + "\""))
                .toList();
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
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
