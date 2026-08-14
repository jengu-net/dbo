package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.PolicyViolationException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.policy.AuditModel;
import cloud.jengu.dbo.policy.PolicyObjectStore;
import cloud.jengu.dbo.policy.RetentionSweep;
import cloud.jengu.dbo.policy.TenantPolicies;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#22 (§15): audit entries are regular pseudonymous records riding their
 * own outbox with the actor from the caller seam; append-only discipline
 * rejects tombstones naming the policy; retention removes expired objects
 * from state AND history (audited), and a restored pre-sweep archive comes
 * up already swept.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyIT {

    static PostgreSQLContainer<?> postgres;
    static PGSimpleDataSource ds;
    static PolicyObjectStore store;
    static TenantPolicies policies;
    static byte[] ownerKey = new byte[32];

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        new SecureRandom().nextBytes(ownerKey);

        policies = TenantPolicies.parse(Map.of(
                "audit", Map.of("level", "writes"),
                "writeDiscipline", Map.of("default", "append-only", "perType", Map.of("Task", "standard")),
                "retention", Map.of("perType", Map.of(
                        "Observation", Map.of("keepAtLeast", "P7D", "removeAfter", "P30D")))));

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("Task"),
                FhirTypeConfig.internal("DocumentReference")));
        List<cloud.jengu.dbo.core.api.TypeRegistration> registrations =
                new ArrayList<>(personality.registrations());
        registrations.addAll(AuditModel.registrations());
        store = new PolicyObjectStore(new PgObjectStore(ds, registrations), policies);
    }

    @AfterAll
    void down() {
        Caller.clear();
        postgres.stop();
    }

    private static byte[] observation(String code) {
        return ("{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\""
                + code + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Incoherent policies never parse: floor above ceiling, unknown levels. */
    @Test
    @Order(1)
    void incoherentPoliciesAreRejectedAtParse() {
        assertThrows(IllegalArgumentException.class, () -> TenantPolicies.parse(Map.of(
                "retention", Map.of("perType", Map.of("Observation",
                        Map.of("keepAtLeast", "P10Y", "removeAfter", "P30D"))))));
        assertThrows(IllegalArgumentException.class, () -> TenantPolicies.parse(Map.of(
                "audit", Map.of("level", "verbose"))));
        assertThrows(IllegalArgumentException.class, () -> TenantSpec.parse("""
                {"code":"halb","fhirVersion":"r4","writeDiscipline":{"default":"immutable"},
                 "types":[{"name":"Task","identity":"internal"}]}"""));
    }

    /** Writes are audited with the caller's identity; the audit stream is its own outbox. */
    @Test
    @Order(2)
    void writesAreAuditedWithTheActor() {
        Caller.set("lab-engine");
        PutResult created = store.put(PutRequest.create("Observation", observation("Hb")));
        Caller.clear();

        List<StoredObject> entries = store.select(Criteria.of("AuditEntry"));
        assertEquals(1, entries.size());
        String entry = new String(entries.get(0).payload(), StandardCharsets.UTF_8);
        assertTrue(entry.contains("\"actor\":\"lab-engine\"")
                && entry.contains("\"interaction\":\"create\"")
                && entry.contains(created.id()), entry);

        // feed-visible on the audit domain's own outbox
        assertEquals(1, new PgChangeFeed(ds, AuditModel.DOMAIN).read(null, 10).items().size());

        // reads are NOT audited at level=writes
        store.get("Observation", created.id());
        assertEquals(1, store.select(Criteria.of("AuditEntry")).size());
    }

    /** Append-only rejects tombstones naming the policy; per-type override stays standard. */
    @Test
    @Order(3)
    void appendOnlyRejectsTombstonesButOverridesApply() {
        PutResult observation = store.put(PutRequest.create("Observation", observation("Na")));
        PolicyViolationException refusal = assertThrows(PolicyViolationException.class,
                () -> store.delete("Observation", observation.id(), null));
        assertTrue(refusal.getMessage().contains("append-only"), "the error names the policy");

        // correction is supersession — updates stay legal
        store.put(PutRequest.update("Observation", observation.id(), 1, observation("Na-corrected")));

        // Task is declared standard: tombstoning works
        PutResult task = store.put(PutRequest.create("Task",
                "{\"resourceType\":\"Task\",\"status\":\"requested\"}".getBytes(StandardCharsets.UTF_8)));
        store.delete("Task", task.id(), null);
    }

    /** §15.3: expired objects leave state AND history; the removal is audited, the data is not retained. */
    @Test
    @Order(4)
    void retentionRemovesExpiredObjectsAndAuditsTheRemoval() throws Exception {
        PutResult old = store.put(PutRequest.create("Observation", observation("Vana")));
        backdate(old.id(), "45 days");
        // archive BEFORE the sweep, with the aged timestamps — the
        // come-up-already-swept candidate
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, ownerKey, archive);

        RetentionSweep sweep = new RetentionSweep(ds, R4Personality.DOMAIN, policies, store);
        assertTrue(sweep.sweepOnce() >= 1);

        assertTrue(store.get("Observation", old.id()).isEmpty(), "expired object gone from state");
        assertEquals(0, store.history("Observation", old.id()).size(), "and from history");
        String audit = store.select(Criteria.of("AuditEntry")).stream()
                .map(e -> new String(e.payload(), StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertTrue(audit.contains("retention-remove") && audit.contains("removeAfter=")
                && audit.contains(old.id()), "the removal is audited with the rule");
        assertTrue(!audit.contains("Vana"), "the audit never retains the removed data");

        // a restored pre-sweep archive comes up already swept: restore, sweep at bring-up
        TenantImport.restoreFidelity(ds, R4Personality.DOMAIN,
                new ByteArrayInputStream(archive.toByteArray()), ownerKey);
        assertTrue(store.get("Observation", old.id()).isPresent(), "restore brings the bytes back");
        sweep.sweepOnce();
        assertTrue(store.get("Observation", old.id()).isEmpty(),
                "bring-up sweep re-applies policy before serving");
    }

    private void backdate(String id, String interval) throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String sql : List.of(
                    "UPDATE state.r4_data SET last_updated = now() - interval '" + interval + "' WHERE id = ?::uuid",
                    "UPDATE history.r4_history SET last_updated = now() - interval '" + interval + "' WHERE id = ?::uuid")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
        }
    }
}
