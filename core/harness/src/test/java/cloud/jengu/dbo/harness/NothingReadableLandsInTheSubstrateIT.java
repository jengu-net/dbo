package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.stream.StreamLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Holder;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared plane holds nothing readable and no credential — checked by
 * looking, because a violation looks exactly like compliance from anywhere
 * else.
 *
 * <p>A platform-plane row carrying a payload is indistinguishable from one
 * carrying a work id unless somebody searches the plane for something they
 * know was in the payload. So this drives a run whose document carries a
 * marker nobody would write by accident, and a person-like value beside it,
 * over the stream — the carrier that crosses the shared plane — and then
 * reads every table of the substrate for either. What must be there is the
 * manifest, readable, because routing is what it is for; what must not is
 * the document, the marker, or any bearer token.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NothingReadableLandsInTheSubstrateIT {

    private static final String TENANT = "planehost";
    private static final String STEP = "dbo.lab.assay";
    private static final String MARKER = "plaintext-marker-7c1e-never-written-by-accident";
    private static final String IDENTIFYING = "37001010021";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static PGSimpleDataSource substrate;
    static final HttpClient http = HttpClient.newHttpClient();
    static ObjectStore engine;
    static Runs runs;
    static KeyPair sealing;
    static KeyPair signing;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-plane");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("NothingReadableLandsInTheSubstrateIT"),
                postgres.getUsername(), postgres.getPassword());
        substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("NothingReadableLandsInTheSubstrateIT_substrate"));
        substrate.setUser(postgres.getUsername());
        substrate.setPassword(postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.substrate(substrate);
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        engine = manager.runtime(TENANT).orElseThrow().engine();
        runs = new Runs(engine);
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("analyser", "analyser-secret",
                List.of("work/" + STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        HttpLane.to(URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work"),
                () -> token(), TENANT, "analyser", executor()).introduce(ASSAY);
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
    @DisplayName("a run crosses the substrate with a marked document: the manifest is found "
            + "there and the marker, the identifying value and any token are not")
    @Proving({DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE, DboPromises.WF_TWO_PLANES})
    void theSubstrateHoldsTheManifestAndNothingReadable() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + MARKER + "\"},"
                        + "\"identifier\":[{\"value\":\"" + IDENTIFYING + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "crosses-the-plane",
                Map.of("specimen", "Basic/" + specimen));

        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser", executor(),
                sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            assertTrue(new String(lane.inputs(held).get("specimen").payload(), StandardCharsets.UTF_8)
                    .contains(MARKER), "the analyser read the document, on its side");
            lane.closed(held);
        }
        assertEquals(Holder.NOBODY, runs.byKey(run.key()).orElseThrow().holder());

        // Now look. Every row of every table in the substrate, as text.
        List<String> rows = everyRowOfTheSubstrate();
        assertTrue(rows.stream().anyMatch(r -> r.contains(run.key())),
                "the scan reached the plane the work crossed: the manifest names the run, "
                        + "readable, and it was not found — so nothing below is evidence");
        assertTrue(rows.stream().anyMatch(r -> r.contains("Basic/" + specimen)),
                "and the manifest's references are there too, because routing is what they "
                        + "are for");
        List<String> leaks = rows.stream().filter(r -> r.contains(MARKER)
                || r.contains(IDENTIFYING) || r.contains("Bearer ")
                || r.contains("analyser-secret")).toList();
        assertEquals(List.of(), leaks,
                "resource content, or a credential, readable in the shared plane");
    }

    @Test
    @DisplayName("the clear verb has no answer on the stream, whatever the asker holds")
    @Proving(DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE)
    void inputsInTheClearAreRefusedOnTheStream() throws Exception {
        String specimen = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\"}".getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "asks-clear",
                Map.of("specimen", "Basic/" + specimen));
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "analyser", executor(),
                sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            // The lane never asks in the clear for a keyed participant; the
            // door refuses the verb itself when asked directly.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> lane.askedInTheClear(held));
            assertTrue(refused.getMessage().contains("inputs travel sealed"), refused.getMessage());
        }
        assertFalse(everyRowOfTheSubstrate().stream()
                .anyMatch(r -> r.contains("\"resourceType\":\"Basic\"")),
                "nothing readable landed even for the refused ask");
    }

    // ------------------------------------------------------------ fixtures

    /** Every row of every table in the substrate's schemas, rendered as text. */
    private static List<String> everyRowOfTheSubstrate() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement tables = c.createStatement();
                ResultSet t = tables.executeQuery("select table_schema, table_name from "
                        + "information_schema.tables where table_schema not in "
                        + "('pg_catalog', 'information_schema')")) {
            List<String> names = new ArrayList<>();
            while (t.next()) {
                names.add("\"" + t.getString(1) + "\".\"" + t.getString(2) + "\"");
            }
            assertFalse(names.isEmpty(), "the substrate has tables to look in");
            for (String name : names) {
                try (Statement rowsOf = c.createStatement();
                        ResultSet r = rowsOf.executeQuery("select row_to_json(x)::text from "
                                + name + " x")) {
                    while (r.next()) {
                        rows.add(name + " " + r.getString(1));
                    }
                }
            }
        }
        return rows;
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
