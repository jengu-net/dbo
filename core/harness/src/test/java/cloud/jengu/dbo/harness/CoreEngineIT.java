package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#4 proof matrix. Test names describe behaviour; the REQ each proves is
 * named in its javadoc.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreEngineIT {

    static PostgreSQLContainer<?> postgres;
    static DataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        ds = pg;
        store = new PgObjectStore(ds, GadgetModel.registrations());
    }

    @AfterAll
    void down() {
        postgres.stop();
    }

    private static byte[] gadget(String serial, String vendor, String name, int weight) {
        return ("{\"serial\":\"%s\",\"vendor\":\"%s\",\"name\":\"%s\",\"weightGrams\":%d}"
                .formatted(serial, vendor, name, weight)).getBytes(StandardCharsets.UTF_8);
    }

    /** REQ-DBO-CORE-READ-YOUR-WRITES, REQ-DBO-CORE-VERSIONED-HISTORY, REQ-DBO-CORE-PAYLOAD-IS-TRUTH */
    @Test
    void aWriteIsImmediatelyReadableAndEveryVersionIsKept() {
        PutResult v1 = store.put(PutRequest.create("Gadget", gadget("S-100", "acme", "Pump", 900)));
        assertTrue(v1.created());
        assertEquals(1, v1.versionId());

        Optional<StoredObject> read = store.get("Gadget", v1.id());
        assertTrue(read.isPresent());
        assertArrayEquals(gadget("S-100", "acme", "Pump", 900), read.get().payload());

        PutResult v2 = store.put(PutRequest.update("Gadget", v1.id(), 1, gadget("S-100", "acme", "Pump Mk2", 950)));
        assertEquals(2, v2.versionId());
        assertFalse(v2.created());

        List<StoredObject> history = store.history("Gadget", v1.id());
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).versionId());
        assertEquals(2, history.get(1).versionId());
        assertArrayEquals(gadget("S-100", "acme", "Pump", 900), history.get(0).payload());
    }

    /** Optimistic concurrency: stale expected version → typed conflict (maps to 412). */
    @Test
    void aStaleExpectedVersionIsRejected() {
        PutResult created = store.put(PutRequest.create("Gadget", gadget("S-101", "acme", "Valve", 120)));
        store.put(PutRequest.update("Gadget", created.id(), 1, gadget("S-101", "acme", "Valve b", 121)));
        assertThrows(VersionConflictException.class, () ->
                store.put(PutRequest.update("Gadget", created.id(), 1, gadget("S-101", "acme", "Valve c", 122))));
    }

    /** REQ-DBO-CORE-EXTERNAL-IDENTIFIERS: identifiers extracted from payload, OR-match lookup. */
    @Test
    void objectsAreFoundByAnyOfTheirIdentifiers() {
        store.put(PutRequest.create("Gadget", gadget("S-200", "bolt", "Sensor", 40)));
        store.put(PutRequest.create("Gadget", gadget("S-201", "bolt", "Sensor", 41)));

        List<StoredObject> hits = store.getByIdentifier("Gadget", List.of(
                new Identifier(GadgetModel.SERIAL_SYSTEM, "S-200"),
                new Identifier(GadgetModel.SERIAL_SYSTEM, "S-201"),
                new Identifier(GadgetModel.SERIAL_SYSTEM, "S-does-not-exist")));
        assertEquals(2, hits.size());
    }

    /** REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS: conditional create is idempotent by identity. */
    @Test
    void conditionalCreateReturnsTheExistingObjectUntouched() {
        IdentityRef serial = IdentityRef.identifier(GadgetModel.SERIAL_SYSTEM, "S-300");
        PutResult first = store.putIfAbsent(serial, PutRequest.create("Gadget", gadget("S-300", "acme", "Motor", 5000)));
        assertTrue(first.created());

        PutResult second = store.putIfAbsent(serial, PutRequest.create("Gadget", gadget("S-300", "acme", "Motor NEW", 5001)));
        assertFalse(second.created());
        assertEquals(first.id(), second.id());
        assertEquals(1, second.versionId());
        assertArrayEquals(gadget("S-300", "acme", "Motor", 5000),
                store.get("Gadget", first.id()).orElseThrow().payload());
    }

    /** Conditional upsert by CANONICAL identity — create then in-place new version. */
    @Test
    void canonicalUpsertCreatesThenUpdatesTheSameObject() {
        IdentityRef url = IdentityRef.canonical("https://dbo.dev/blueprints/pump");
        byte[] b1 = "{\"url\":\"https://dbo.dev/blueprints/pump\",\"title\":\"Pump v1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] b2 = "{\"url\":\"https://dbo.dev/blueprints/pump\",\"title\":\"Pump v2\"}".getBytes(StandardCharsets.UTF_8);

        PutResult r1 = store.putConditional(url, PutRequest.create("Blueprint", b1));
        PutResult r2 = store.putConditional(url, PutRequest.create("Blueprint", b2));
        assertTrue(r1.created());
        assertFalse(r2.created());
        assertEquals(r1.id(), r2.id());
        assertEquals(2, r2.versionId());
    }

    /** A conditional write keyed on a non-identity system is rejected — it is a search, not an identity claim. */
    @Test
    void aConditionalWriteOnANonIdentitySystemIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                store.putIfAbsent(IdentityRef.identifier("urn:vendor", "acme"),
                        PutRequest.create("Gadget", gadget("S-301", "acme", "X", 1))));
        assertThrows(IllegalArgumentException.class, () ->
                store.putIfAbsent(IdentityRef.canonical("https://nope"),
                        PutRequest.create("Gadget", gadget("S-302", "acme", "X", 1))));
    }

    /** REQ-DBO-CORE-NO-IMPLICIT-MERGE: a second object claiming a serial is a surfaced conflict. */
    @Test
    void aSecondClaimOnTheSameSerialSurfacesAConflictInsteadOfMerging() {
        PutResult owner = store.put(PutRequest.create("Gadget", gadget("S-400", "acme", "Original", 10)));
        IdentityConflictException conflict = assertThrows(IdentityConflictException.class, () ->
                store.put(PutRequest.create("Gadget", gadget("S-400", "bolt", "Impostor", 11))));
        assertEquals(owner.id(), conflict.existingId());
        assertEquals(GadgetModel.SERIAL_SYSTEM, conflict.identifier().system());
        // and nothing of the impostor was stored
        assertEquals(1, store.getByIdentifier("Gadget",
                List.of(new Identifier(GadgetModel.SERIAL_SYSTEM, "S-400"))).size());
    }

    /** Typed envelope: numeric sort orders 9 < 70 < 500 (text ordering would say 500 < 70 < 9). */
    @Test
    void numericSortOrdersByNumberNotByText() {
        store.put(PutRequest.create("Gadget", gadget("S-500", "sortco", "Heavy", 500)));
        store.put(PutRequest.create("Gadget", gadget("S-501", "sortco", "Light", 9)));
        store.put(PutRequest.create("Gadget", gadget("S-502", "sortco", "Middle", 70)));

        List<StoredObject> sorted = store.select(Criteria.of("Gadget")
                .eq("vendor", EnvelopeValue.token("urn:vendor", "sortco"))
                .sortBy("weightGrams", ValueKind.NUMBER, true));
        assertEquals(3, sorted.size());
        List<String> serials = sorted.stream()
                .map(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .map(p -> p.substring(p.indexOf("S-5"), p.indexOf("S-5") + 5))
                .toList();
        assertEquals(List.of("S-501", "S-502", "S-500"), serials);
    }

    /** REQ-DBO-CORE-REFERENCE-EDGES: reference edges power referential selects. */
    @Test
    void objectsAreSelectableByTheObjectsTheyReference() {
        PutResult hub = store.put(PutRequest.create("Gadget", gadget("S-600", "refco", "Hub", 100)));
        byte[] part = ("{\"serial\":\"S-601\",\"vendor\":\"refco\",\"name\":\"Part\",\"weightGrams\":5,"
                + "\"partOf\":\"" + hub.id() + "\"}").getBytes(StandardCharsets.UTF_8);
        PutResult partResult = store.put(PutRequest.create("Gadget", part));

        List<StoredObject> parts = store.select(Criteria.of("Gadget")
                .referencing("partOf", "Gadget", hub.id()));
        assertEquals(1, parts.size());
        assertEquals(partResult.id(), parts.get(0).id());
    }

    /** REQ-DBO-EVT-TRANSACTIONAL-OUTBOX: every committed write leaves exactly one ordered, content-free event. */
    @Test
    void everyWriteLeavesExactlyOneOutboxEventInCommitOrder() throws Exception {
        long before = outboxCount();
        PutResult r = store.put(PutRequest.create("Gadget", gadget("S-700", "evco", "Emitter", 1)));
        store.put(PutRequest.update("Gadget", r.id(), 1, gadget("S-700", "evco", "Emitter b", 2)));
        store.delete("Gadget", r.id(), 2L);

        List<String[]> events = outboxFor(r.id());
        assertEquals(3, events.size());
        assertEquals("C", events.get(0)[0]);
        assertEquals("U", events.get(1)[0]);
        assertEquals("D", events.get(2)[0]);
        assertEquals(before + 3, outboxCount());
    }

    /** Atomicity: a failing extractor aborts the whole write — no data, no history, no outbox, no identifier. */
    @Test
    void aFailedWriteLeavesNoPartialState() throws Exception {
        long outboxBefore = outboxCount();
        byte[] boom = "{\"serial\":\"S-800\",\"vendor\":\"x\",\"name\":\"x\",\"weightGrams\":1,\"boom\":true}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> store.put(PutRequest.create("Gadget", boom)));

        assertEquals(0, store.getByIdentifier("Gadget",
                List.of(new Identifier(GadgetModel.SERIAL_SYSTEM, "S-800"))).size());
        assertEquals(outboxBefore, outboxCount());
    }

    /** Delete is a tombstone: gone from reads, present in history, identity claim freed. */
    @Test
    void deleteTombstonesAndFreesTheIdentityClaim() {
        PutResult r = store.put(PutRequest.create("Gadget", gadget("S-900", "delco", "Ephemeral", 7)));
        store.delete("Gadget", r.id(), 1L);

        assertTrue(store.get("Gadget", r.id()).isEmpty());
        List<StoredObject> history = store.history("Gadget", r.id());
        assertEquals(2, history.size());
        assertTrue(history.get(1).deleted());

        // the serial is claimable again — by a NEW object
        PutResult successor = store.put(PutRequest.create("Gadget", gadget("S-900", "delco", "Successor", 8)));
        assertNotEquals(r.id(), successor.id());
    }

    /** REQ-DBO-CORE-REINDEX-IS-AN-OPERATION: new extractor + rebuild → new search dimension, payloads untouched. */
    @Test
    void reindexAddsASearchDimensionWithoutTouchingPayloads() {
        PutResult r = store.put(PutRequest.create("Gadget", gadget("S-950", "mixedCase", "Probe", 33)));
        byte[] payloadBefore = store.get("Gadget", r.id()).orElseThrow().payload();
        long versionBefore = store.get("Gadget", r.id()).orElseThrow().versionId();

        PgObjectStore v2 = new PgObjectStore(ds, GadgetModel.registrationsV2());
        int rebuilt = v2.rebuildEnvelopes("Gadget");
        assertTrue(rebuilt > 0);

        List<StoredObject> byNewPath = v2.select(Criteria.of("Gadget")
                .eq("vendorUpper", EnvelopeValue.of("MIXEDCASE")));
        assertEquals(1, byNewPath.size());
        assertEquals(r.id(), byNewPath.get(0).id());

        StoredObject after = v2.get("Gadget", r.id()).orElseThrow();
        assertArrayEquals(payloadBefore, after.payload());
        assertEquals(versionBefore, after.versionId());
    }

    /** dbo#18 R2: reindex runs in chunked short transactions across many objects. */
    @Test
    void reindexChunksAcrossManyObjects() {
        for (int i = 0; i < 1100; i++) {
            store.put(PutRequest.create("Reading",
                    ("{\"metric\":\"bulk\",\"value\":" + i + ",\"gadget\":\"g\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        int rebuilt = store.rebuildEnvelopes("Reading", 500); // 3 chunked transactions
        assertTrue(rebuilt >= 1100, "all objects reindexed across chunks, got " + rebuilt);
    }

    /** UUIDv7 ids: version nibble 7, RFC variant, time-ordered across sequential writes. */
    @Test
    void generatedIdsAreTimeOrderedUuidV7() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(store.put(PutRequest.create("Reading",
                    ("{\"metric\":\"temp\",\"value\":" + i + ",\"gadget\":\"g\"}").getBytes(StandardCharsets.UTF_8))).id());
            Thread.sleep(2);
        }
        for (String id : ids) {
            UUID u = UUID.fromString(id);
            assertEquals(7, u.version());
            assertEquals(2, u.variant());
        }
        List<String> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        assertEquals(ids, sorted, "sequentially generated v7 ids must sort in creation order");
    }

    // ------------------------------------------------------------- plumbing

    private long outboxCount() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM state.gadgets_outbox");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String[]> outboxFor(String objectId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, version_id FROM state.gadgets_outbox WHERE object_id = ? ORDER BY seq")) {
            ps.setObject(1, UUID.fromString(objectId));
            try (ResultSet rs = ps.executeQuery()) {
                List<String[]> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
                return out;
            }
        }
    }
}
