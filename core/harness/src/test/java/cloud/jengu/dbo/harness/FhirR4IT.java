package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.UnknownSearchParameterException;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dbo#6 proof matrix: the R4 personality over the model-agnostic engine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FhirR4IT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static R4Store fhir;
    static PgObjectStore engine;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("FhirR4IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        DataSource ds = pg;

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.canonical("ValueSet")));
        engine = new PgObjectStore(ds, personality.registrations());
        fhir = new R4Store(engine, personality, "https://dbo.test/fhir");
    }

    @AfterAll
    void down() {
    }

    private static String patient(String eid, String family, String given) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s","given":["%s"]}],
                 "gender":"male","birthDate":"1990-01-01"}""".formatted(EID, eid, family, given);
    }

    private static String observation(String patientId, String code, String effective, double value) {
        return """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"%s"}]},
                 "subject":{"reference":"Patient/%s"},
                 "effectiveDateTime":"%s",
                 "valueQuantity":{"value":%s,"unit":"mmol/L"}}"""
                .formatted(code, patientId, effective, value);
    }

    /** Patient round-trips; token identifier search and exact string search hit. */
    @Test
    void patientIsFoundByIdentifierTokenAndByFamilyString() {
        PutResult kask = fhir.create(patient("39001010000", "Kask", "Jaan"));
        fhir.create(patient("48802020000", "Tamm", "Mari"));

        String byIdentifier = fhir.search("Patient",
                Map.of("identifier", EID + "|39001010000"), null);
        assertEquals(1, entryCount(byIdentifier));
        assertTrue(byIdentifier.contains(kask.id()));

        String byFamily = fhir.search("Patient", Map.of("family", "Kask"), null);
        assertEquals(1, entryCount(byFamily));

        String miss = fhir.search("Patient",
                Map.of("identifier", EID + "|00000000000"), null);
        assertEquals(0, entryCount(miss));
    }

    /** REQ-DBO-CORE-NO-IMPLICIT-MERGE holds through the FHIR path: same national id → conflict. */
    @Test
    void aSecondPatientClaimingTheSameNationalIdIsAConflict() {
        fhir.create(patient("35012310000", "Original", "Owner"));
        assertThrows(IdentityConflictException.class, () ->
                fhir.create(patient("35012310000", "Impostor", "Claim")));
    }

    /** Reference + token params compile and hit; combined search narrows. */
    @Test
    void observationsAreFoundBySubjectAndCode() {
        PutResult p = fhir.create(patient("50101010001", "Mets", "Ants"));
        fhir.create(observation(p.id(), "15074-8", "2026-08-01T10:00:00Z", 5.5));
        fhir.create(observation(p.id(), "718-7", "2026-08-02T10:00:00Z", 14.2));
        PutResult other = fhir.create(patient("61202020002", "Ilves", "Tiit"));
        fhir.create(observation(other.id(), "15074-8", "2026-08-03T10:00:00Z", 6.1));

        assertEquals(2, entryCount(fhir.search("Observation",
                Map.of("subject", "Patient/" + p.id()), null)));
        assertEquals(1, entryCount(fhir.search("Observation",
                Map.of("subject", "Patient/" + p.id(), "code", "http://loinc.org|15074-8"), null)));
        // bare-code token matches any system
        assertEquals(2, entryCount(fhir.search("Observation", Map.of("code", "15074-8"), null)));
    }

    /** REQ-DBO-SRCH-TYPED-ORDERING + keyset paging framed as Bundle link[next], descending. */
    @Test
    void descendingDateSortPagesThroughBundleNextLinksWithoutDuplicates() {
        PutResult p = fhir.create(patient("32303030003", "Paged", "Person"));
        for (int day = 1; day <= 7; day++) {
            fhir.create(observation(p.id(), "999-9",
                    "2026-07-0%dT08:00:00Z".formatted(day), day));
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("subject", "Patient/" + p.id());
        params.put("code", "999-9");
        params.put("_sort", "-date");
        params.put("_count", "3");

        List<String> effectives = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String bundle = fhir.search("Observation", params, cursor);
            pages++;
            Matcher m = Pattern.compile("\"effectiveDateTime\":\"([^\"]+)\"").matcher(bundle);
            while (m.find()) {
                effectives.add(m.group(1));
            }
            cursor = nextCursor(bundle);
            if (cursor == null) {
                break;
            }
        }
        assertEquals(3, pages);
        assertEquals(7, effectives.size());
        assertEquals(7, java.util.Set.copyOf(effectives).size(), "no duplicates across pages");
        for (int i = 1; i < effectives.size(); i++) {
            assertTrue(effectives.get(i).compareTo(effectives.get(i - 1)) < 0,
                    "descending date order violated at " + i);
        }
    }

    /** ValueSet upserts conditionally by canonical url (create → new version, same object). */
    @Test
    void valueSetUpsertsByCanonicalUrl() {
        String vs1 = """
                {"resourceType":"ValueSet","status":"active",
                 "url":"https://dbo.test/vs/blood-panels","name":"BloodPanels1"}""";
        String vs2 = vs1.replace("BloodPanels1", "BloodPanels2");
        PutResult r1 = fhir.putCanonical(vs1);
        PutResult r2 = fhir.putCanonical(vs2);
        assertTrue(r1.created());
        assertFalse(r2.created());
        assertEquals(r1.id(), r2.id());
        assertEquals(2, r2.versionId());
        assertTrue(fhir.read("ValueSet", r1.id()).contains("BloodPanels2"));
    }

    /** REQ-DBO-SRCH-STRICT-BY-DEFAULT: unknown parameter → rejected, not ignored. */
    @Test
    void unknownSearchParametersAreRejectedNotIgnored() {
        assertThrows(UnknownSearchParameterException.class, () ->
                fhir.search("Patient", Map.of("favourite-color", "blue"), null));
        assertThrows(UnknownSearchParameterException.class, () ->
                fhir.search("Patient", Map.of("_sort", "favourite-color"), null));
    }

    /** Validation gates the write: structurally invalid Observation never reaches the store. */
    @Test
    void anInvalidObservationIsRejectedByValidationBeforeStorage() {
        ValidationFailedException failure = assertThrows(ValidationFailedException.class, () ->
                fhir.create("{\"resourceType\":\"Observation\",\"id\":\"x\"}"));
        assertTrue(failure.issues().stream()
                .anyMatch(i -> i.contains("status") || i.contains("code")));
        assertEquals(0, entryCount(fhir.search("Observation",
                Map.of("code", "no-such-code"), null)));
    }

    /** The engine below is still model-agnostic: the identifier written via FHIR is a plain engine identifier. */
    @Test
    void theEngineSeesFhirDataAsPlainObjects() {
        PutResult p = fhir.create(patient("77012345678", "Engine", "View"));
        var hits = engine.getByIdentifier("Patient",
                List.of(new Identifier(EID, "77012345678")));
        assertEquals(1, hits.size());
        assertEquals(p.id(), hits.get(0).id());
        assertNotNull(engine.get("Patient", p.id()).orElseThrow().payload());
    }

    // ------------------------------------------------------------- plumbing

    private static int entryCount(String bundleJson) {
        Matcher m = Pattern.compile("\"fullUrl\"").matcher(bundleJson);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String nextCursor(String bundleJson) {
        Matcher m = Pattern.compile("\"relation\":\"next\",\"url\":\"[^\"]*_cursor=([^\"&]+)\"")
                .matcher(bundleJson);
        if (m.find()) {
            return m.group(1);
        }
        // lenient across HAPI's serialization order
        Matcher m2 = Pattern.compile("_cursor=([A-Za-z0-9_-]+)").matcher(bundleJson);
        return m2.find() ? m2.group(1) : null;
    }
}
