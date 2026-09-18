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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document too large to travel in the message goes beside it, and nothing
 * above the carrier can tell.
 *
 * <p>An answer on this wire is an event on the door's workflow, which is a row
 * in the substrate's own tables. That is right for a verb's answer and wrong
 * for a run's inputs: the substrate re-reads a workflow's inputs when it
 * recovers one, keeps them under its retention rather than the tenant's, and
 * Postgres will TOAST a large value into a side store with none of a blob
 * store's lifecycle. So above a threshold the bytes wait beside the door and
 * the message carries their key.
 *
 * <p><b>What is asserted, and why each half is needed.</b> That the large
 * payload arrives and opens is most of it — but a spill that never happened
 * would pass that on its own, so the plane is read to show the bytes were not
 * in the message and the note that replaced them was. And a spill that
 * happened to everything would pass both, so a small answer is driven through
 * the same door and must not have spilled: otherwise the threshold is
 * decorative and every poll pays for a table.
 *
 * <p>The last assertion is the one the whole mechanism is held to. What is
 * spilled is what would have travelled — the same sealed carrier form, moved
 * and not transformed — so a spilled copy is reached by an erasure exactly as
 * an in-message one is, its identifying elements being under the person's key
 * inside the seal. Stored any other way, erasure would have a hole precisely
 * where the large payloads are, which is where it would matter most.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ALargePayloadTravelsByReferenceIT {

    private static final String TENANT = "spillhost";
    private static final String STEP = "dbo.lab.image";
    /** Nobody writes this by accident, so finding it anywhere is evidence. */
    private static final String MARKER = "spilled-marker-4f2a-never-written-by-accident";

    /**
     * Comfortably over the threshold once sealed and wire-encoded, and built
     * from one repeated run of text so the document is large for the reason
     * an imaging payload is: there is a lot of it.
     */
    private static final int BIG = 96 * 1024;

    /**
     * What no row of the substrate's own tables may reach. Above the verbs —
     * a poll's answer, a manifest, a run — and well below the payload, so it
     * separates the two without being a second copy of the spill's own
     * threshold, which it would then only ever agree with.
     */
    private static final int TOO_BIG_FOR_THE_PLANE = 48 * 1024;

    private static final StepDeclaration IMAGE = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("scan", "https://meristem.example/shape/scan");

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
        dir = Files.createTempDirectory("dbo-spill");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ALargePayloadTravelsByReferenceIT"),
                postgres.getUsername(), postgres.getPassword());
        substrate = new PGSimpleDataSource();
        substrate.setUrl(SharedPostgres.urlFor("ALargePayloadTravelsByReferenceIT_substrate"));
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
        manager.authority(TENANT).ensureClient("imager", "imager-secret",
                List.of("work/" + STEP), ParticipantKey.of(sealing.getPublic()),
                SigningKey.of(signing.getPublic()));
        HttpLane.to(URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work"),
                ALargePayloadTravelsByReferenceIT::token, TENANT, "imager", executor())
                .introduce(IMAGE);
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
    @DisplayName("a payload too large for the message arrives whole, having travelled beside "
            + "it, and a small one does not pay for that")
    @Proving({DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE, DboPromises.WF_TWO_PLANES})
    void theLargeOneSpillsAndTheSmallOneDoesNot() throws Exception {
        String big = engine.put(PutRequest.create("Basic", document(MARKER, BIG))).id();
        String small = engine.put(PutRequest.create("Basic", document(MARKER, 64))).id();
        Run heavy = runs.of(IMAGE, RunKind.PIPELINE, "carries-a-large-scan",
                Map.of("scan", "Basic/" + big));
        Run light = runs.of(IMAGE, RunKind.PIPELINE, "carries-a-small-scan",
                Map.of("scan", "Basic/" + small));

        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "imager", executor(),
                sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(heavy, Duration.ofMinutes(5)).orElseThrow();
            byte[] arrived = lane.inputs(held).get("scan").payload();
            assertEquals(BIG, new String(arrived, StandardCharsets.UTF_8)
                            .replaceAll(".*\"text\":\"", "").replaceAll("\".*", "").length(),
                    "the large document did not arrive whole, so the spill lost or truncated "
                            + "what the message was too small to carry");
            lane.closed(held);

            Run also = lane.claim(light, Duration.ofMinutes(5)).orElseThrow();
            assertTrue(new String(lane.inputs(also).get("scan").payload(), StandardCharsets.UTF_8)
                    .contains(MARKER), "the small document did not arrive");
            lane.closed(also);
        }

        // It travelled beside the message rather than in it. Read from the
        // plane, because that is the only place the difference is visible:
        // from the runner's side the two runs were identical, which is the
        // property, and is also why this cannot be asserted from up there.
        List<String> events = rowsOf("dbos", "workflow_events");
        assertFalse(events.isEmpty(), "the door answered through events, or this looks in the "
                + "wrong place and everything below it is vacuous");
        assertTrue(events.stream().anyMatch(row -> row.contains("spilled")),
                "no answer was set aside, so a payload of " + BIG + " bytes went through the "
                        + "substrate's own tables — where it is re-read on every recovery, "
                        + "kept under a retention nobody here set, and TOASTed into a side "
                        + "store with no lifecycle at all");

        // And not to everything: a poll, a claim and a checkpoint are
        // hundreds of bytes and must not each buy a row in a table.
        assertTrue(events.stream().anyMatch(row -> !row.contains("spilled")),
                "every answer spilled, so the threshold is decorative");
    }

    @Test
    @DisplayName("and what was set aside is the sealed carrier form, so an erasure reaches it "
            + "exactly as it reaches a copy in the message")
    @Proving(DboPromises.WF_CONTENT_FREE_PLATFORM_PLANE)
    void whatSpillsIsWhatWouldHaveTravelled() throws Exception {
        String big = engine.put(PutRequest.create("Basic", document(MARKER, BIG))).id();
        Run run = runs.of(IMAGE, RunKind.PIPELINE, "left-uncollected",
                Map.of("scan", "Basic/" + big));

        // Asked for and deliberately never collected, so the bytes are still
        // sitting where they were put and can be looked at.
        try (StreamLane lane = StreamLane.holding(substrate, TENANT, "imager", executor(),
                sealing.getPrivate(), signing.getPrivate())) {
            Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();
            lane.sealed(held);
            lane.closed(held);
        }

        // Every row of the substrate, the whole plane, as the ratchet beside
        // this one reads it — because a spill is a new table on that plane and
        // a new table is a new place for something readable to land.
        List<String> plane = everyRowOfTheSubstrate();
        assertTrue(plane.stream().anyMatch(row -> row.contains(run.key())),
                "the scan reached the plane the work crossed: a manifest names its run, "
                        + "readable, and none was found — so nothing below is evidence");
        assertEquals(List.of(), plane.stream().filter(row -> row.contains(MARKER)
                        || row.contains("Bearer ") || row.contains("imager-secret")).toList(),
                "the document, or a credential, readable on the shared plane — and if it is "
                        + "in the spill then what was set aside was never the sealed form, so "
                        + "an erasure would not reach it and the hole is exactly where the "
                        + "large payloads are");

        // And the size, which the scan above cannot see. Sealed bytes carry
        // no marker wherever they sit, so every assertion so far would pass
        // with the payload still in the substrate's own tables — which is
        // precisely the thing the spill exists to stop. One table is allowed
        // to be large: the one that was built to hold it, with a lifecycle.
        assertEquals(List.of(), oversizedRowsOutsideTheSpill(),
                "the substrate's own tables hold a payload-sized row, so the bytes are back "
                        + "where they are re-read on every recovery and kept under a "
                        + "retention nobody here set — spilling the event is not enough if a "
                        + "step's recorded result carries them instead");
    }

    // ------------------------------------------------------------ fixtures

    /** A Basic whose text is {@code size} characters, carrying the marker. */
    private static byte[] document(String marker, int size) {
        StringBuilder text = new StringBuilder(size);
        while (text.length() < size) {
            text.append(marker).append('-');
        }
        text.setLength(size);
        return ("{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + text + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every table of the substrate whose largest row is payload-sized, except
     * the spill's own — reported with that size, because "something is too
     * big somewhere" is not a finding anybody can act on.
     */
    private static List<String> oversizedRowsOutsideTheSpill() throws Exception {
        List<String> over = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement tables = c.createStatement();
                ResultSet t = tables.executeQuery("select table_schema, table_name from "
                        + "information_schema.tables where table_schema not in "
                        + "('pg_catalog', 'information_schema')")) {
            List<String> names = new ArrayList<>();
            while (t.next()) {
                if (!"dbo_lane_spill".equals(t.getString(2))) {
                    names.add("\"" + t.getString(1) + "\".\"" + t.getString(2) + "\"");
                }
            }
            assertFalse(names.isEmpty(), "the substrate has tables to look in");
            for (String name : names) {
                try (Statement widest = c.createStatement();
                        ResultSet r = widest.executeQuery("select coalesce(max(length("
                                + "row_to_json(x)::text)), 0) from " + name + " x")) {
                    if (r.next() && r.getInt(1) > TOO_BIG_FOR_THE_PLANE) {
                        over.add(name + " holds a row of " + r.getInt(1) + " bytes");
                    }
                }
            }
        }
        return over;
    }

    private static List<String> rowsOf(String schema, String table) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = substrate.getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select row_to_json(x)::text from \"" + schema
                        + "\".\"" + table + "\" x")) {
            while (r.next()) {
                rows.add(r.getString(1));
            }
        }
        return rows;
    }

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
        return new Executor("imager", "1.0", "cloud.jengu.test", Scope.BASELINE);
    }

    private static String token() {
        try {
            String form = "grant_type=client_credentials&client_id=imager&client_secret="
                    + URLEncoder.encode("imager-secret", StandardCharsets.UTF_8);
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
