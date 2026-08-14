package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.pdi.PdiObjectStore;
import cloud.jengu.dbo.pdi.PdiSetup;
import cloud.jengu.dbo.pdi.PdiSpec;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
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
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#21 (§14): identifying elements are ciphertext everywhere the engine
 * writes — state, history, envelopes, feed, archives — reassembled only for
 * authorized reads; identity stays unmergeable vault-side; shredding erases
 * the person from every copy at once and a restore cannot resurrect them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PdiIT {

    static final String EID = "https://eesti.ee/isikukood";
    static final String NAME = "Salakas";
    static final String CODE_37 = "37001010021";

    static PostgreSQLContainer<?> postgres;
    static PGSimpleDataSource ds;
    static PersonVault vault;
    static PdiObjectStore store;
    static byte[] ownerKey = new byte[32];
    static String personId;

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                new FhirTypeConfig("Patient", cloud.jengu.dbo.core.api.IdentityClass.IDENTIFIER, Set.of(EID)),
                FhirTypeConfig.internal("Observation")));
        PdiSpec spec = PdiSpec.fhir();
        List<TypeRegistration> transformed = PdiSetup.transform(personality.registrations(), spec);
        byte[] workingKey = new byte[32];
        new SecureRandom().nextBytes(workingKey);
        new SecureRandom().nextBytes(ownerKey);
        vault = new PersonVault(ds, workingKey);
        store = new PdiObjectStore(new PgObjectStore(ds, transformed), vault, spec);
    }

    @AfterAll
    void down() {
        postgres.stop();
    }

    private static byte[] patient(String family, String code) {
        return ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                + "\",\"value\":\"" + code + "\"}],\"name\":[{\"family\":\"" + family
                + "\"}],\"birthDate\":\"1970-01-01\",\"gender\":\"male\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Authorized reads see the full resource; versions reassemble from history. */
    @Test
    @Order(1)
    void splitAndReassembleAreInvisibleToAuthorizedReads() {
        PutResult v1 = store.put(PutRequest.create("Patient", patient(NAME, CODE_37)));
        personId = v1.id();
        String read = new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertTrue(read.contains(NAME) && read.contains(CODE_37) && read.contains("1970-01-01"));
        assertFalse(read.contains("__pdiEnc"), "the ciphertext block never reaches a reader");

        store.put(PutRequest.update("Patient", personId, 1, patient("Salakas-Uus", CODE_37)));
        List<StoredObject> history = store.history("Patient", personId);
        assertEquals(2, history.size());
        assertTrue(new String(history.get(0).payload(), StandardCharsets.UTF_8).contains(NAME));
        assertTrue(new String(history.get(1).payload(), StandardCharsets.UTF_8).contains("Salakas-Uus"));
    }

    /** The identifying values appear NOWHERE in the database in plaintext. */
    @Test
    @Order(2)
    void identifyingValuesAreCiphertextEverywhere() throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String probe : List.of(NAME, "Salakas-Uus", CODE_37)) {
                for (String sql : List.of(
                        "SELECT count(*) FROM state.r4_data WHERE convert_from(payload, 'UTF8') LIKE ?",
                        "SELECT count(*) FROM state.r4_data WHERE envelope::text LIKE ?",
                        "SELECT count(*) FROM history.r4_history WHERE convert_from(payload, 'UTF8') LIKE ?",
                        "SELECT count(*) FROM state.r4_identifier WHERE value LIKE ?")) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, "%" + probe + "%");
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            assertEquals(0, rs.getLong(1), probe + " leaked via: " + sql);
                        }
                    }
                }
            }
        }
        // the feed carries the same ciphertext payloads
        String feed = new PgChangeFeed(ds, R4Personality.DOMAIN).read(null, 10).items().stream()
                .map(i -> new String(i.payload(), StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertFalse(feed.contains(NAME) || feed.contains(CODE_37), "feed must be pseudonymous");
    }

    /** No-implicit-merge survives the split: the claim lives in the vault. */
    @Test
    @Order(3)
    void identityStaysUnmergeableVaultSide() {
        assertThrows(IdentityConflictException.class, () ->
                store.put(PutRequest.create("Patient", patient("Teine", CODE_37))));
        List<StoredObject> found = store.getByIdentifier("Patient",
                List.of(new Identifier(EID, CODE_37)));
        assertEquals(1, found.size());
        assertTrue(new String(found.get(0).payload(), StandardCharsets.UTF_8).contains("Salakas-Uus"));
    }

    /** Restriction of processing: reads turn pseudonymous, reversibly. */
    @Test
    @Order(4)
    void restrictionMakesReadsPseudonymous() {
        vault.restrict(personId, true);
        assertFalse(new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8).contains("Salakas"));
        vault.restrict(personId, false);
        assertTrue(new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8).contains("Salakas-Uus"));
    }

    /** Portability: one person's data, no one else's. */
    @Test
    @Order(5)
    void exportContainsExactlyOnePerson() {
        store.put(PutRequest.create("Patient", patient("Kolmas", "48001010030")));
        String export = store.exportPerson("Patient", personId, List.of());
        assertTrue(export.contains("Salakas-Uus"));
        assertFalse(export.contains("Kolmas"));
    }

    /** §14.1+§14.4: shred erases every copy at once; a pre-shred archive cannot resurrect. */
    @Test
    @Order(6)
    void shredErasesEverywhereAndRestoreCannotResurrect() throws Exception {
        // archive BEFORE the shred — the resurrection candidate
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, ownerKey, archive);

        vault.shred(personId);

        String read = new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertFalse(read.contains("Salakas") || read.contains(CODE_37) || read.contains("__pdiEnc"),
                "shredded person must read pseudonymous and clean");
        assertTrue(read.contains("Patient"), "the record itself remains");
        for (StoredObject version : store.history("Patient", personId)) {
            assertFalse(new String(version.payload(), StandardCharsets.UTF_8).contains("Salakas"),
                    "history must be unreadable after shred");
        }
        assertEquals(0, store.getByIdentifier("Patient", List.of(new Identifier(EID, CODE_37))).size(),
                "an erased person is unfindable");
        assertThrows(IllegalStateException.class, () ->
                store.put(PutRequest.update("Patient", personId, 2, patient(NAME, CODE_37))),
                "no new identifying data for a shredded person");

        // restore the pre-shred archive: the ledger MERGES and replays
        TenantImport.restoreFidelity(ds, R4Personality.DOMAIN,
                new ByteArrayInputStream(archive.toByteArray()), ownerKey);
        String restored = new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertFalse(restored.contains("Salakas") || restored.contains(CODE_37),
                "a pre-shred archive must not resurrect the person");
        assertEquals(0, store.getByIdentifier("Patient", List.of(new Identifier(EID, CODE_37))).size());
    }
}
