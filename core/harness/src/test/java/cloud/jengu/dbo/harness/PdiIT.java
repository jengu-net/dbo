package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;

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
import org.junit.jupiter.api.DisplayName;
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
 * §14: identifying elements are ciphertext everywhere the engine
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
    static String jdbcUrl;
    static PGSimpleDataSource ds;
    static PersonVault vault;
    static PdiObjectStore store;
    static byte[] ownerKey = new byte[32];
    static String personId;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("PdiIT");
        ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                new FhirTypeConfig("Patient", cloud.jengu.dbo.core.api.IdentityClass.IDENTIFIER, Set.of(EID),
                        cloud.jengu.dbo.core.api.Handling.operational()),
                FhirTypeConfig.internal("Observation")));
        PdiSpec spec = PdiSpec.fhir();
        List<TypeRegistration> transformed = PdiSetup.transform(personality.registrations(), spec);
        byte[] workingKey = new byte[32];
        new SecureRandom().nextBytes(workingKey);
        new SecureRandom().nextBytes(ownerKey);
        vault = new PersonVault(ds, workingKey);
        store = new PdiObjectStore(new PgObjectStore(ds, transformed), vault, spec,
                cloud.jengu.dbo.fhir.r4.R4FhirVersion.INSTANCE.face()
                        .require(cloud.jengu.dbo.core.face.Coarsening.class));
    }

    @AfterAll
    void down() {
    }

    private static byte[] patient(String family, String code) {
        return ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                + "\",\"value\":\"" + code + "\"}],\"name\":[{\"family\":\"" + family
                + "\"}],\"birthDate\":\"1970-01-01\",\"gender\":\"male\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The coarse value is computed on write and kept in the
     * clear, because a reader without the key has no plaintext to derive one
     * from — and the face supplies the coarsening, since knowing that a birth
     * date reduces to its year is knowledge about FHIR shapes, not about
     * storage (§12).
     */
    @Test
    @Order(0)
    @DisplayName("the stored payload carries the birth year, and only the year")
    @Proving({DboPromises.PDI_STRUCTURAL_VAULT})
    void theCoarseValueIsWrittenInTheClear() throws Exception {
        String id = store.put(PutRequest.create("Patient", patient("Coarse", "39001010023"))).id();

        String stored;
        try (java.sql.Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT payload FROM state." + R4Personality.DOMAIN
                             + "_data WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                stored = new String(rs.getBytes(1), StandardCharsets.UTF_8);
            }
        }

        assertTrue(stored.contains("\"birthDate\":\"1970\""),
                "the year must survive in the clear, or a reader with no right to the full date "
                        + "gets nothing where a clinician needs an age: " + stored);
        assertFalse(stored.contains("1970-01-01"),
                "and the full date must not be in the clear — it rides encrypted like the rest");
    }

    /**
     * Reads whole, the way an authorised caller does: a stated purpose and the
     * mode that goes with it.
     *
     * <p>These tests were written when a read with a key returned everything,
     * so they read plainly and expected identity back. The default is now to
     * omit it, which is the disruption intended — so what they assert
     * about an AUTHORISED read they now have to ask for.
     */
    private static <T> T reading(java.util.function.Supplier<T> read) {
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "TREAT");
        try {
            return read.get();
        } finally {
            cloud.jengu.dbo.core.api.Disclosure.clear();
        }
    }

    /** Authorized reads see the full resource; versions reassemble from history. */
    @Test
    @Order(1)
    void splitAndReassembleAreInvisibleToAuthorizedReads() {
        PutResult v1 = store.put(PutRequest.create("Patient", patient(NAME, CODE_37)));
        personId = v1.id();
        String read = reading(() -> new String(store.get("Patient", personId).orElseThrow()
                .payload(), StandardCharsets.UTF_8));
        assertTrue(read.contains(NAME) && read.contains(CODE_37) && read.contains("1970-01-01"));
        assertFalse(read.contains("__pdiEnc"), "the ciphertext block never reaches a reader");

        store.put(PutRequest.update("Patient", personId, 1, patient("Salakas-Uus", CODE_37)));
        List<StoredObject> history = reading(() -> store.history("Patient", personId));
        assertEquals(2, history.size());
        assertTrue(new String(history.get(0).payload(), StandardCharsets.UTF_8).contains(NAME));
        assertTrue(new String(history.get(1).payload(), StandardCharsets.UTF_8).contains("Salakas-Uus"));
    }

    /** The identifying values appear NOWHERE in the database in plaintext. */
    @Test
    @Order(2)
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
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
        List<StoredObject> found = reading(() -> store.getByIdentifier("Patient",
                List.of(new Identifier(EID, CODE_37))));
        assertEquals(1, found.size());
        assertTrue(new String(found.get(0).payload(), StandardCharsets.UTF_8).contains("Salakas-Uus"));
    }

    /** Restriction of processing: reads turn pseudonymous, reversibly. */
    @Test
    @Order(4)
    @Proving(DboPromises.PDI_RIGHTS_AS_OPERATIONS)
    void restrictionMakesReadsPseudonymous() {
        vault.restrict(personId, true);
        // asked for whole, and still pseudonymous: restriction is not a mode a
        // caller can talk its way past
        assertFalse(reading(() -> new String(store.get("Patient", personId).orElseThrow()
                .payload(), StandardCharsets.UTF_8)).contains("Salakas"));
        vault.restrict(personId, false);
        assertTrue(reading(() -> new String(store.get("Patient", personId).orElseThrow()
                .payload(), StandardCharsets.UTF_8)).contains("Salakas-Uus"));
    }

    /**
     * The subject's own export states its own purpose rather than inheriting
     * the request's.
     *
     * <p>Article 20 is not satisfied by a pseudonymous file, and ciphertext the
     * subject holds no key for satisfies it even less — so this path says
     * PATRQT, which is exactly what it is, and puts the request's own
     * disclosure back when it is done.
     */
    @Test
    @Order(5)
    @Proving(DboPromises.PDI_RIGHTS_AS_OPERATIONS)
    void theSubjectsOwnExportStatesItsPurpose() {
        cloud.jengu.dbo.core.api.Disclosure.clear();
        String export = store.exportPerson("Patient", personId, List.of());
        assertTrue(export.contains("Salakas-Uus"),
                "the person's own data, whole, because they asked for it: " + export);
        assertEquals(cloud.jengu.dbo.core.api.Disclosure.Mode.OMIT,
                cloud.jengu.dbo.core.api.Disclosure.mode(),
                "and what the request was doing is put back afterwards");
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

    /**
     * A tenant archive carries the carrier form, and does so by construction
     * rather than by remembering to ask.
     *
     * <p>The export copies rows, so what it holds is what is at rest:
     * ciphertext and coarse values. That is {@code ENCRYPTED} without anyone
     * selecting it, and it is the right answer — an archive crosses a boundary
     * and must not disclose identity to whatever carries it.
     *
     * <p>Asserted rather than left implicit, because it is exactly the property
     * a refactor would break silently: routing the export through the store
     * would make it inherit the request's disclosure, and a backup taken under
     * the default would come back pseudonymous with nothing to say it had.
     */
    @Test
    @Order(6)
    @Proving(DboPromises.PDI_BLIND_OPERATIONS)
    void aTenantArchiveCarriesCiphertextWhateverTheRequestWasDoing() throws Exception {
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "TREAT");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try {
            TenantExport.export(ds, R4Personality.DOMAIN, ownerKey, archive);
        } finally {
            cloud.jengu.dbo.core.api.Disclosure.clear();
        }

        String bytes = archive.toString(StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("Salakas-Uus") || bytes.contains(CODE_37),
                "an archive taken during an authorised read must still carry no plaintext "
                        + "identity — it is a boundary, not a reader");
    }

    /** §14.1+§14.4: shred erases every copy at once; a pre-shred archive cannot resurrect. */
    @Test
    @Order(6)
    @Proving({DboPromises.PDI_CRYPTO_SHREDDING, DboPromises.PDI_SHRED_LEDGER,
            DboPromises.PDI_UNFINDABLE_AFTER_ERASURE, DboPromises.POL_ERASURE_COMPATIBLE})
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
        assertFalse(read.contains("1970"),
                "a coarse birth year must not survive an erasure — generalisation is what a "
                        + "reader without the right to see an identity gets instead, not what "
                        + "is left behind after somebody asked to be forgotten.");
        for (StoredObject version : store.history("Patient", personId)) {
            assertFalse(new String(version.payload(), StandardCharsets.UTF_8).contains("Salakas"),
                    "history must be unreadable after shred");
        }
        assertEquals(0, store.getByIdentifier("Patient", List.of(new Identifier(EID, CODE_37))).size(),
                "an erased person is unfindable");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                store.put(PutRequest.update("Patient", personId, 2, patient(NAME, CODE_37))),
                "no new identifying data for a shredded person");
        assertTrue(refused.getMessage().contains(
                        DboPromises.PDI_CRYPTO_SHREDDING.code()),
                "the refusal names the promise it enforces: " + refused.getMessage());

        // restore the pre-shred archive: the ledger MERGES and replays
        CoSignedArchive.over(archive.toByteArray(), ownerKey)
                .restoreFidelityInto(ds, R4Personality.DOMAIN, ownerKey);
        String restored = new String(store.get("Patient", personId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertFalse(restored.contains("Salakas") || restored.contains(CODE_37),
                "a pre-shred archive must not resurrect the person");
        assertEquals(0, store.getByIdentifier("Patient", List.of(new Identifier(EID, CODE_37))).size());
    }
    /**
     * After erasure, exact identifier resolution answers "nobody" — which is
     * then the true answer, not a lie. The claim rows went with the
     * shred, and the pre-shred archive restored in the previous step could
     * not resurrect them either.
     */
    @Test
    @Order(7)
    @Proving({DboPromises.PDI_UNFINDABLE_AFTER_ERASURE, DboPromises.PDI_EXACT_RESOLUTION})
    void aShreddedPersonIsNotResolvableByIdentifier() {
        cloud.jengu.dbo.core.api.Caller.set("test-client");
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "TREAT");
        try {
            assertEquals(0, store.select(cloud.jengu.dbo.core.api.Criteria.of("Patient")
                            .eq("identifier", cloud.jengu.dbo.core.api.EnvelopeValue.token(EID, CODE_37)))
                    .size(), "an erased person is unresolvable, not merely unreadable");
        } finally {
            cloud.jengu.dbo.core.api.Disclosure.clear();
            cloud.jengu.dbo.core.api.Caller.clear();
        }
    }

}
