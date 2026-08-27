package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r5.R4ToR5Converter;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DBO-CORE-UPGRADE-ON-READ — a domain written under R4 re-binds
 * to R5 with a converter; reads upgrade lazily, stored bytes stay R4, and
 * converter + reindex makes R5 search live. The transition, not the ceremony.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpgradeOnReadIT {

    static final String EID = "https://ee.ee/eid";
    static final String DOMAIN = "clinical";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static PGSimpleDataSource pg;
    static String encounterId;
    static String patientId;
    static PgObjectStore r5Engine;
    static R5Store r5;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("UpgradeOnReadIT");
        pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        // era one: the domain lives under the R4 personality
        R4Personality p4 = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Encounter")));
        R4Store r4 = new R4Store(new PgObjectStore(pg, p4.registrations(DOMAIN)),
                p4, "https://dbo.test/fhir");

        patientId = r4.create("""
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38907070777"}],
                 "name":[{"family":"Transition"}]}""".formatted(EID)).id();
        encounterId = r4.create("""
                {"resourceType":"Encounter","status":"finished",
                 "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},
                 "subject":{"reference":"Patient/%s"},
                 "period":{"start":"2026-05-01T09:00:00Z","end":"2026-05-01T09:30:00Z"}}"""
                .formatted(patientId)).id();

        // era two: SAME domain re-binds to the R5 personality + the converter
        R5Personality p5 = new R5Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Encounter")));
        r5Engine = new PgObjectStore(pg, p5.registrations(DOMAIN), List.of(new R4ToR5Converter()));
        r5 = new R5Store(r5Engine, p5, "https://dbo.test/fhir");
    }

    @AfterAll
    void down() {
    }

    /** The R4-written Encounter reads back as R5 (period → actualPeriod), lazily. */
    @Test
    @Order(1)
    @Proving(DboPromises.CORE_UPGRADE_ON_READ)
    void r4WrittenEncounterReadsAsR5() {
        String converted = r5.read("Encounter", encounterId);
        assertTrue(converted.contains("\"actualPeriod\""),
                "R5 renames Encounter.period to actualPeriod — conversion must surface it");
        assertFalse(converted.contains("\"period\""));
        assertTrue(converted.contains("2026-05-01T09:00:00"));
    }

    /** Payload-is-truth: the stored bytes are still the R4 form, version-tagged 4.0. */
    @Test
    @Order(2)
    @Proving(DboPromises.CORE_PAYLOAD_IS_TRUTH)
    void storedBytesRemainR4() throws Exception {
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload, payload_version FROM state.%s_data WHERE id = ?".formatted(DOMAIN))) {
            ps.setObject(1, UUID.fromString(encounterId));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String raw = new String(rs.getBytes(1), StandardCharsets.UTF_8);
                assertTrue(raw.contains("\"period\""), "stored payload must remain untouched R4");
                assertFalse(raw.contains("actualPeriod"));
                assertEquals("4.0", rs.getString(2));
            }
        }
    }

    /** Identifiers bit-exact through the hop. */
    @Test
    @Order(3)
    @Proving(DboPromises.CORE_IDENTITY_SURVIVES_CONVERSION)
    void identitySurvivesConversion() {
        StoredObject patient = r5Engine.getByIdentifier("Patient",
                List.of(new Identifier(EID, "38907070777"))).get(0);
        assertEquals(patientId, patient.id());
        String converted = new String(patient.payload(), StandardCharsets.UTF_8);
        assertTrue(converted.contains("\"" + EID + "\""));
        assertTrue(converted.contains("38907070777"));
        assertEquals("5.0", patient.payloadVersion());
    }

    /** Converted output is valid R5 (validated by the R5 chain). */
    @Test
    @Order(4)
    void convertedResourcesPassR5Validation() {
        // a fresh write of the converted form must validate cleanly
        String converted = r5.read("Encounter", encounterId);
        // strip id: create validates then stores as a new object
        PutResult recreated = r5.create(converted.replaceFirst("\"id\":\"[^\"]+\",?", ""));
        assertTrue(recreated.created());
    }

    /** Converter + reindex: R5 search paths (date over actualPeriod) hit R4-written data. */
    @Test
    @Order(5)
    @Proving(DboPromises.CORE_UPGRADE_ON_READ)
    void reindexMakesR5SearchLiveOverR4Data() {
        int rebuilt = r5Engine.rebuildEnvelopes("Encounter");
        assertTrue(rebuilt >= 1);

        String hits = r5.search("Encounter", Map.of(
                "date", "gt2026-04-30", "subject", "Patient/" + patientId), null);
        assertTrue(hits.contains(encounterId),
                "R5 date search must find the R4-written encounter after reindex");
    }

    /** New writes through the transitioned store are R5-tagged and skip conversion. */
    @Test
    @Order(6)
    void newWritesCarryTheNewVersion() {
        PutResult fresh = r5.create("""
                {"resourceType":"Encounter","status":"in-progress",
                 "class":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"}]}],
                 "subject":{"reference":"Patient/%s"},
                 "actualPeriod":{"start":"2026-08-14T10:00:00Z"}}""".formatted(patientId));
        StoredObject stored = r5Engine.get("Encounter", fresh.id()).orElseThrow();
        assertEquals("5.0", stored.payloadVersion());
    }
}
