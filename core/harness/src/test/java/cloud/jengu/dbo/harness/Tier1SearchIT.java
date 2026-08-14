package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4TypeConfig;
import cloud.jengu.dbo.fhir.r4.UnknownSearchParameterException;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#7: the tier-1 search matrix, each scenario mirroring a production shape
 * from docs/evidence/search-usage-inventory.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Tier1SearchIT {

    static final String EID = "https://ee.ee/eid";
    static final String BARCODE_SYS = "urn:jengu:specimen-barcode";
    static final String ORDER_SYS = "https://order.example/ids";
    static final String TAG_SYS = "https://jengu.cloud/tags";

    static PostgreSQLContainer<?> postgres;
    static R4Store fhir;

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                R4TypeConfig.identifier("Patient", EID),
                R4TypeConfig.internal("Observation"),
                R4TypeConfig.internal("ServiceRequest"),
                R4TypeConfig.internal("Specimen"),
                R4TypeConfig.internal("Organization"),
                R4TypeConfig.canonical("ValueSet")));
        fhir = new R4Store(new PgObjectStore(pg, personality.registrations()),
                personality, "https://dbo.test/fhir");
    }

    @AfterAll
    void down() {
        postgres.stop();
    }

    // ------------------------------------------------------------- fixtures

    private static String patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(EID, eid, family);
    }

    private static String observation(String code, String tagCode, String orderSystemValue) {
        String tag = tagCode == null ? "" :
                "\"meta\":{\"tag\":[{\"system\":\"%s\",\"code\":\"%s\"}]},".formatted(TAG_SYS, tagCode);
        String basedOn = orderSystemValue == null ? "" :
                "\"basedOn\":[{\"identifier\":{\"system\":\"%s\",\"value\":\"%s\"}}],"
                        .formatted(ORDER_SYS, orderSystemValue);
        return """
                {"resourceType":"Observation",%s%s"status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"%s"}]}}"""
                .formatted(tag, basedOn, code);
    }

    // ------------------------------------------------------------ scenarios

    /** Inventory: `Device?identifier=<EDGE>|` — any value in system. */
    @Test
    void anyValueInSystemTokenMatches() {
        fhir.create(patient("60001019906", "SysOnly"));
        String hits = fhir.search("Patient", Map.of("identifier", EID + "|"), null);
        assertTrue(entryCount(hits) >= 1);
        String miss = fhir.search("Patient", Map.of("identifier", "https://other.sys|"), null);
        assertEquals(0, entryCount(miss));
    }

    /** Inventory: `Organization?...&partof:missing=false` / `=true`. */
    @Test
    void missingModifierSplitsRootsFromChildren() {
        PutResult root = fhir.create("{\"resourceType\":\"Organization\",\"name\":\"Root Org\"}");
        fhir.create("""
                {"resourceType":"Organization","name":"Child Org",
                 "partOf":{"reference":"Organization/%s"}}""".formatted(root.id()));

        String roots = fhir.search("Organization", Map.of("partof:missing", "true"), null);
        String children = fhir.search("Organization", Map.of("partof:missing", "false"), null);
        assertTrue(bodyOf(roots).contains("Root Org"));
        assertFalse(bodyOf(roots).contains("Child Org"));
        assertTrue(bodyOf(children).contains("Child Org"));
        assertFalse(bodyOf(children).contains("Root Org"));
    }

    /** Inventory (lab edge): `Observation?status=final&_tag:not=lis-synced&_sort=-_lastUpdated`. */
    @Test
    void tagNotModifierFindsTheUnsyncedOnes() {
        fhir.create(observation("11111-1", "lis-synced", null));
        fhir.create(observation("22222-2", "fresh", null));
        fhir.create(observation("33333-3", null, null));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("status", "final");
        params.put("_tag:not", "lis-synced");
        params.put("_sort", "-_lastUpdated");
        String hits = fhir.search("Observation", params, null);
        String body = bodyOf(hits);
        assertFalse(body.contains("11111-1"), "tagged lis-synced must be excluded");
        assertTrue(body.contains("22222-2"));
        assertTrue(body.contains("33333-3"));
    }

    /** Inventory (result intake): `Observation?based-on:identifier=<orderSystem>|&status=final`. */
    @Test
    void logicalReferenceIdentifierMatchesBySystem() {
        fhir.create(observation("44444-4", null, "ORD-77"));
        fhir.create(observation("55555-5", null, null));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("based-on:identifier", ORDER_SYS + "|");
        params.put("status", "final");
        String hits = fhir.search("Observation", params, null);
        String body = bodyOf(hits);
        assertTrue(body.contains("44444-4"));
        assertFalse(body.contains("55555-5"));
    }

    /** Inventory (lab edge HL7 context): `ServiceRequest?specimen.identifier=<barcode>&status=active`. */
    @Test
    void oneLevelChainReachesTheSpecimenBarcode() {
        PutResult patient = fhir.create(patient("37605030299", "Chained"));
        PutResult specimen = fhir.create("""
                {"resourceType":"Specimen",
                 "identifier":[{"system":"%s","value":"BAR-9001"}]}""".formatted(BARCODE_SYS));
        fhir.create("""
                {"resourceType":"ServiceRequest","status":"active","intent":"order",
                 "subject":{"reference":"Patient/%s"},
                 "specimen":[{"reference":"Specimen/%s"}]}""".formatted(patient.id(), specimen.id()));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("specimen.identifier", "BAR-9001");
        params.put("status", "active");
        String hits = fhir.search("ServiceRequest", params, null);
        assertEquals(1, entryCount(hits));

        assertEquals(0, entryCount(fhir.search("ServiceRequest",
                Map.of("specimen.identifier", "BAR-XXXX"), null)));
    }

    /** Inventory (edge sync cursor): `?_lastUpdated=gt<cursor>&_sort=_lastUpdated`. */
    @Test
    void lastUpdatedCursorSweepSeesOnlyNewerWrites() throws Exception {
        fhir.create(patient("48912120011", "BeforeCursor"));
        Thread.sleep(5);
        Instant cursor = Instant.now();
        Thread.sleep(5);
        fhir.create(patient("50104040022", "AfterCursor"));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("_lastUpdated", "gt" + cursor);
        params.put("_sort", "_lastUpdated");
        String hits = fhir.search("Patient", params, null);
        String body = bodyOf(hits);
        assertTrue(body.contains("AfterCursor"));
        assertFalse(body.contains("BeforeCursor"));
    }

    /** FHIR string semantics: default is case-insensitive starts-with; :exact is case-sensitive. */
    @Test
    void stringSearchStartsWithByDefaultAndExactWithModifier() {
        fhir.create(patient("39209090033", "Kaasik"));

        assertEquals(1, entryCount(fhir.search("Patient", Map.of("family", "kaas"), null)));
        assertEquals(1, entryCount(fhir.search("Patient", Map.of("family", "KAASIK"), null)));
        assertEquals(1, entryCount(fhir.search("Patient", Map.of("family:exact", "Kaasik"), null)));
        assertEquals(0, entryCount(fhir.search("Patient", Map.of("family:exact", "kaasik"), null)));
        assertEquals(0, entryCount(fhir.search("Patient", Map.of("family", "aasik"), null)));
    }

    /** Inventory (terminology sync): `ValueSet?url=<canonical>`. */
    @Test
    void uriParamFindsTheCanonical() {
        fhir.putCanonical("""
                {"resourceType":"ValueSet","status":"active",
                 "url":"https://dbo.test/vs/tier1","name":"TierOne"}""");
        assertEquals(1, entryCount(fhir.search("ValueSet",
                Map.of("url", "https://dbo.test/vs/tier1"), null)));
        assertEquals(0, entryCount(fhir.search("ValueSet",
                Map.of("url", "https://dbo.test/vs/other"), null)));
    }

    /** Inventory: `Practitioner?_summary=count` — total without entries. */
    @Test
    void summaryCountReturnsTotalsWithoutEntries() {
        fhir.create(patient("60807070044", "Counted"));
        String bundle = fhir.search("Patient", Map.of("_summary", "count"), null);
        assertTrue(bundle.contains("\"total\""));
        assertEquals(0, entryCount(bundle));
    }

    /** Inventory (terminology sync): `?_elements=identifier,name` trims payloads. */
    @Test
    void elementsProjectionTrimsEncodedResources() {
        fhir.create(patient("70503030055", "Trimmed"));
        String bundle = fhir.search("Patient", Map.of(
                "identifier", EID + "|70503030055", "_elements", "identifier"), null);
        assertEquals(1, entryCount(bundle));
        assertTrue(bundle.contains("70503030055"));
        assertFalse(bundle.contains("Trimmed"), "_elements=identifier must drop name");
    }

    /** Inventory (lab worklist): `ServiceRequest?...&_include=ServiceRequest:specimen`. */
    @Test
    void includeCarriesTheSpecimenAlong() {
        PutResult patient = fhir.create(patient("81201010066", "Included"));
        PutResult specimen = fhir.create("""
                {"resourceType":"Specimen",
                 "identifier":[{"system":"%s","value":"BAR-INC-1"}]}""".formatted(BARCODE_SYS));
        fhir.create("""
                {"resourceType":"ServiceRequest","status":"active","intent":"order",
                 "subject":{"reference":"Patient/%s"},
                 "specimen":[{"reference":"Specimen/%s"}]}""".formatted(patient.id(), specimen.id()));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("specimen.identifier", "BAR-INC-1");
        params.put("_include", "ServiceRequest:specimen");
        String bundle = fhir.search("ServiceRequest", params, null);
        assertTrue(bundle.contains("\"include\""), "include entry mode expected");
        assertTrue(bundle.contains("BAR-INC-1"));
        assertTrue(bundle.contains(specimen.id()));
    }

    /** Inventory (bootstrap workhorse): If-None-Exist by identifier / url is idempotent; others rejected. */
    @Test
    void conditionalCreateIsIdentityKeyedAndIdempotent() {
        PutResult first = fhir.conditionalCreate(patient("90154321077", "CondCreate"),
                Map.of("identifier", EID + "|90154321077"));
        PutResult second = fhir.conditionalCreate(patient("90154321077", "CondCreate CHANGED"),
                Map.of("identifier", EID + "|90154321077"));
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.id(), second.id());

        assertThrows(IllegalArgumentException.class, () ->
                fhir.conditionalCreate(patient("90154321088", "Bad"),
                        Map.of("family", "Bad")));
    }

    /** `_offset` is rejected with cursor guidance; unknown params stay rejected. */
    @Test
    void offsetAndUnknownParametersAreStillRejected() {
        assertThrows(UnknownSearchParameterException.class, () ->
                fhir.search("Patient", Map.of("_offset", "20"), null));
        assertThrows(UnknownSearchParameterException.class, () ->
                fhir.search("Patient", Map.of("name:soundslike", "kask"), null));
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

    /** Entry payload region only (excludes the self/next links). */
    private static String bodyOf(String bundleJson) {
        int entry = bundleJson.indexOf("\"entry\"");
        return entry < 0 ? "" : bundleJson.substring(entry);
    }
}
