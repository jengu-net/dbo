package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PayloadConverter;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r5.R4ToR5Converter;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.sync.ContentDependency;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §6 sync streams — zone→mid→leaf. Declared types stream as
 * provenance-tagged copies; the leaf hop converts R4→R5 at apply; a local
 * object shadows the stream and deleting it falls back to the live upstream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncStreamsIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static R4Store zone;
    static PgObjectStore midEngine;
    static R4Store mid;
    static R5Store leaf;
    static PgObjectStore leafEngine;
    static ContentSyncEngine zoneToMid;
    static ContentSyncEngine midToLeaf;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("SyncStreamsIT");
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE sync_zone");
            st.execute("CREATE DATABASE sync_mid");
            st.execute("CREATE DATABASE sync_leaf");
        }
        String baseUrl = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        PGSimpleDataSource zoneDs = ds(baseUrl + "sync_zone");
        PGSimpleDataSource midDs = ds(baseUrl + "sync_mid");
        PGSimpleDataSource leafDs = ds(baseUrl + "sync_leaf");

        List<FhirTypeConfig> r4Types = List.of(
                FhirTypeConfig.canonical("ValueSet"),
                FhirTypeConfig.internal("Encounter"),
                FhirTypeConfig.identifier("Patient", EID));
        R4Personality zoneP = new R4Personality(r4Types);
        R4Personality midP = new R4Personality(r4Types);
        R5Personality leafP = new R5Personality(List.of(
                FhirTypeConfig.canonical("ValueSet"),
                FhirTypeConfig.internal("Encounter")));

        PgObjectStore zoneEngine = new PgObjectStore(zoneDs, zoneP.registrations());
        midEngine = new PgObjectStore(midDs, midP.registrations());
        leafEngine = new PgObjectStore(leafDs, leafP.registrations());
        zone = new R4Store(zoneEngine, zoneP, "https://zone.test");
        mid = new R4Store(midEngine, midP, "https://mid.test");
        leaf = new R5Store(leafEngine, leafP, "https://leaf.test");

        Set<String> declared = Set.of("ValueSet", "Encounter");
        zoneToMid = new ContentSyncEngine(
                new ContentDependency("zone", declared),
                new PgChangeFeed(zoneDs, R4Personality.DOMAIN),
                midEngine, midDs, R4Personality.DOMAIN, "4.0", List.of());
        midToLeaf = new ContentSyncEngine(
                new ContentDependency("mid", declared),
                new PgChangeFeed(midDs, R4Personality.DOMAIN),
                leafEngine, leafDs, R5Personality.DOMAIN, "5.0",
                List.of(new BoomAwareConverter()));
    }

    /** R4→R5 hop that refuses payloads carrying the boom marker (dead-letter probe). */
    static final class BoomAwareConverter implements PayloadConverter {
        private final R4ToR5Converter delegate = new R4ToR5Converter();

        @Override
        public String fromVersion() { return delegate.fromVersion(); }

        @Override
        public String toVersion() { return delegate.toVersion(); }

        @Override
        public byte[] convert(String typeName, byte[] payload) {
            if (new String(payload, StandardCharsets.UTF_8).contains("boom-marker")) {
                throw new IllegalStateException("marked unconvertible");
            }
            return delegate.convert(typeName, payload);
        }
    }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    @AfterAll
    void down() {
    }

    private void syncAll() {
        while (zoneToMid.syncOnce(100) > 0) { }
        while (midToLeaf.syncOnce(100) > 0) { }
    }

    private static String valueSet(String url, String name) {
        return """
                {"resourceType":"ValueSet","status":"active","url":"%s","name":"%s"}"""
                .formatted(url, name);
    }

    /** Declared types stream with provenance; undeclared do not (REQ-DBO-SYNC-DECLARED-ONLY). */
    @Test
    @Order(1)
    void declaredTypesStreamUndeclaredDoNot() {
        PutResult vs = zone.putCanonical(valueSet("https://zone.test/vs/a", "StreamMe"));
        zone.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"51111111111"}]}""".formatted(EID));

        syncAll();

        StoredObject copy = midEngine.get("ValueSet", vs.id()).orElseThrow();
        assertTrue(new String(copy.payload(), StandardCharsets.UTF_8).contains("StreamMe"));
        assertTrue(midEngine.get("Patient", vs.id()).isEmpty());
        assertEquals(0, mid.search("Patient", java.util.Map.of("identifier", EID + "|"), null)
                .split("fullUrl", -1).length - 1, "undeclared Patient must not stream");

        assertTrue(zoneToMid.origins().stream()
                .anyMatch(o -> o.objectId().equals(vs.id()) && o.typeName().equals("ValueSet")),
                "provenance must record the streamed copy");
    }

    /** Updates advance the copy; source deletes tombstone it. */
    @Test
    @Order(2)
    void updatesAndDeletesPropagate() {
        PutResult v1 = zone.putCanonical(valueSet("https://zone.test/vs/b", "One"));
        syncAll();
        zone.putCanonical(valueSet("https://zone.test/vs/b", "Two"));
        syncAll();

        StoredObject copy = midEngine.get("ValueSet", v1.id()).orElseThrow();
        assertTrue(new String(copy.payload(), StandardCharsets.UTF_8).contains("Two"));

        // engine-level delete on the source (canonical facade has no delete yet)
        // — the D event must tombstone the copy
        zoneDelete(v1.id());
        syncAll();
        assertTrue(midEngine.get("ValueSet", v1.id()).isEmpty());
        assertTrue(leafEngine.get("ValueSet", v1.id()).isEmpty(), "delete rides the chain too");
    }

    private void zoneDelete(String id) {
        // through the same store the zone writes with
        zone.delete("ValueSet", id, null);
    }

    /** The chain: zone R4 Encounter reaches the R5 leaf CONVERTED, through mid's own outbox. */
    @Test
    @Order(3)
    void chainDeliversConvertedCopiesHopByHop() {
        PutResult patient = zone.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"52222222222"}]}""".formatted(EID));
        PutResult encounter = zone.create("""
                {"resourceType":"Encounter","status":"finished",
                 "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},
                 "subject":{"reference":"Patient/%s"},
                 "period":{"start":"2026-06-01T08:00:00Z"}}""".formatted(patient.id()));

        syncAll();

        StoredObject midCopy = midEngine.get("Encounter", encounter.id()).orElseThrow();
        assertEquals("4.0", midCopy.payloadVersion());

        StoredObject leafCopy = leafEngine.get("Encounter", encounter.id()).orElseThrow();
        assertEquals("5.0", leafCopy.payloadVersion(), "SYNC-CONVERT-ON-APPLY: stored converted");
        String leafJson = new String(leafCopy.payload(), StandardCharsets.UTF_8);
        assertTrue(leafJson.contains("actualPeriod"), "R5 shape expected in the leaf store");

        assertTrue(midToLeaf.origins().stream()
                .anyMatch(o -> o.objectId().equals(encounter.id())),
                "leaf provenance names its DIRECT upstream (mid), not the zone");
    }

    /** Unconvertible content dead-letters and degrades the dependency; siblings still apply. */
    @Test
    @Order(4)
    void unconvertibleDeadLettersWithoutBlockingSiblings() {
        PutResult bad = zone.putCanonical(valueSet("https://zone.test/vs/bad", "boom-marker"));
        PutResult good = zone.putCanonical(valueSet("https://zone.test/vs/good", "FineAndWell"));

        syncAll();

        assertTrue(leafEngine.get("ValueSet", good.id()).isPresent(), "sibling must apply");
        assertTrue(leafEngine.get("ValueSet", bad.id()).isEmpty());
        assertTrue(midToLeaf.degraded(), "dead letters must degrade the dependency visibly");
        assertTrue(midToLeaf.deadLetters().stream()
                .anyMatch(d -> d.objectId().equals(bad.id())));
        assertFalse(zoneToMid.degraded(), "the R4→R4 hop is unaffected");
    }

    /** A local object shadows the stream; deleting it + reconcile falls back to the live upstream. */
    @Test
    @Order(5)
    void localShadowingWinsThenFallsBackOnRemoval() {
        String url = "https://zone.test/vs/shadowed";
        PutResult local = leaf.putCanonical(valueSet(url, "LocalOverride"));

        PutResult upstream = zone.putCanonical(valueSet(url, "UpstreamContent"));
        syncAll();

        // local wins; the stream event is parked, not applied and not lost
        assertNotEquals(local.id(), upstream.id());
        assertTrue(new String(leafEngine.get("ValueSet", local.id()).orElseThrow().payload(),
                StandardCharsets.UTF_8).contains("LocalOverride"));
        assertTrue(leafEngine.get("ValueSet", upstream.id()).isEmpty());
        assertEquals(1, midToLeaf.shadowedEvents().size());

        // removing the override falls back to the LIVE upstream version
        leaf.delete("ValueSet", local.id(), null);
        int applied = midToLeaf.reconcile();
        assertEquals(1, applied);
        assertTrue(new String(leafEngine.get("ValueSet", upstream.id()).orElseThrow().payload(),
                StandardCharsets.UTF_8).contains("UpstreamContent"));
        assertTrue(midToLeaf.shadowedEvents().isEmpty());
    }
}
