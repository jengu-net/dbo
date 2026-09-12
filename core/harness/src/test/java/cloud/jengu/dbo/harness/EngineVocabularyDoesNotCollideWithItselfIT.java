package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo's own vocabulary does not collide with itself across a stream.
 *
 * <p>Every tenant is given the engine's vocabulary at bring-up — that is what
 * makes a {@code urn:dbo:} code resolvable in the tenant that served it. A
 * tenant that ALSO inherits CodeSystem from an upstream is therefore given the
 * identical publication twice, once by each route, under two object ids.
 *
 * <p>The second arrival was read as a local override and parked. Every round
 * re-attempted it, every round raised a unique-constraint violation in the
 * database log, and the shadow could never clear because clearing one means
 * removing the local override and there was no override to remove.
 *
 * <p>Both halves are asserted here, because the fix runs an obvious risk: the
 * cure for "identical content is not an override" must not become "an upstream
 * copy always wins".
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineVocabularyDoesNotCollideWithItselfIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    /** Where the container log stood before this test's own work. */
    static int logMark;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        logMark = postgres.getLogs().length();
        dir = Files.createTempDirectory("dbo-tenants-vocabsync");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("EngineVocabularyDoesNotCollideWithItselfIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("vocabzone.json"), spec("vocabzone", ""));
        UntilServed.scan(manager, up -> up.contains("vocabzone"));
        // A tenant that inherits AND may write its own vocabulary — a zone
        // under a parent zone. A tenant whose CodeSystem is read-only-here
        // never publishes its own copy, so it never meets this at all, which
        // is why the first attempt at this test could not fail.
        Files.writeString(dir.resolve("vocabdep.json"), spec("vocabdep",
                ",\"dependencies\":[{\"name\":\"vocabzone\","
                        + "\"types\":[\"CodeSystem\",\"ValueSet\"]}]"));
        UntilServed.scan(manager, up -> up.contains("vocabdep"));
    }

    private static String spec(String code, String dependencies) {
        return """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"mirrored"},
                  {"name":"ValueSet","identity":"canonical","handling":"mirrored"}]%s}"""
                .formatted(code, dependencies);
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

    /**
     * Two rounds, because one proves nothing: the collision recurred on the
     * sync cadence, so the second round is the one that was re-attempting a
     * shadow it could never clear.
     */
    @Test
    @Order(1)
    void theEnginesOwnVocabularyNeverParksAsSomebodyElsesOverride() {
        manager.syncRound();
        manager.syncRound();

        List<String> parked = new ArrayList<>();
        for (ContentSyncEngine engine : manager.streamsOf("vocabdep")) {
            for (ContentSyncEngine.ShadowedEvent shadow : engine.shadowedEvents()) {
                parked.add(shadow.typeName() + " " + canonicalOf(shadow.payload()));
            }
            assertTrue(engine.deadLetters().isEmpty(),
                    "nothing dead-lettered: " + engine.deadLetters());
        }
        assertEquals(List.of(), parked,
                "the engine's own vocabulary arriving from upstream is the same publication "
                        + "this tenant already has, not a local override of it");

        // The other half of the harm, measured where it lands: the
        // dedup used to be DISCOVERED by a failed INSERT, so every first
        // delivery put a duplicate-key ERROR in the Postgres log — six per
        // fresh bring-up, one per vocabulary, describing a situation the code
        // handles. A log that keeps errors for handled situations buries the
        // ones that matter. Only this test's own slice of the shared
        // container's log is judged, and only for the engine's systems.
        //
        // Judged by the TYPES this test writes, not by "any duplicate key in
        // the window". The container's log is shared and Docker fills it
        // asynchronously, so a line written before the mark can appear after
        // it — and the suite contains a test that races a fleet of eight
        // replicas at one identity ON PURPOSE, whose seven handled losses are
        // correct behaviour and were landing here as this test's failure. A
        // mark cannot be made reliable against an async log; naming the
        // subject can.
        String sinceMark = postgres.getLogs().substring(Math.min(logMark,
                postgres.getLogs().length()));
        List<String> noise = sinceMark.lines()
                .filter(line -> line.contains("already exists"))
                .filter(line -> line.contains("(CodeSystem,") || line.contains("(ValueSet,"))
                .toList();
        assertEquals(List.of(), noise,
                "a handled conflict is resolved by asking, not by failing an insert "
                        + "the database logs");
    }

    /**
     * A genuine local decision still shadows. The tenant writes its own
     * definition at a canonical the upstream also publishes, with different
     * content — and a stream that overwrote that would be doing the exact thing
     * shadowing exists to prevent (REQ-DBO-SYNC-LOCAL-SHADOWING).
     */
    @Test
    @Order(2)
    void aLocalDecisionThatDiffersStillShadowsTheUpstream() {
        String canonical = "https://terms.test/CodeSystem/disputed";
        write("vocabdep", canonical, "LocalOverride");
        write("vocabzone", canonical, "Upstream");
        manager.syncRound();
        manager.syncRound();

        List<String> parked = new ArrayList<>();
        for (ContentSyncEngine engine : manager.streamsOf("vocabdep")) {
            engine.shadowedEvents().forEach(shadow ->
                    parked.add(shadow.typeName() + " " + canonicalOf(shadow.payload())));
        }
        assertEquals(List.of("CodeSystem " + canonical), parked,
                "the tenant's own decision is held and the upstream version parks for a "
                        + "person to resolve");

        String held = manager.runtime("vocabdep").orElseThrow().store()
                .read("CodeSystem", localIdOf(canonical));
        assertTrue(held.contains("LocalOverride"),
                "and what the tenant reads is still its own: " + held);
    }

    private static void write(String tenant, String canonical, String name) {
        manager.runtime(tenant).orElseThrow().store().create("""
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"%s","version":"1","name":"%s","concept":[{"code":"x"}]}"""
                .formatted(canonical, name));
    }

    private static String localIdOf(String canonical) {
        return manager.runtime("vocabdep").orElseThrow().engine()
                .getByIdentifier("CodeSystem",
                        List.of(new Identifier(Identifier.CANONICAL_SYSTEM, canonical)))
                .get(0).id();
    }

    private static String canonicalOf(byte[] payload) {
        String body = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"url\":\"(urn:dbo:[^\"]+|https://terms[^\"]+)\"")
                        .matcher(body);
        return matcher.find() ? matcher.group(1) : "(no canonical)";
    }
}
