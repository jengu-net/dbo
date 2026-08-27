package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-DBO-VER-CONCURRENT-VERSIONS — R4 and R5 personalities over ONE
 * database in ONE process, isolated by domain, with the core untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentVersionsIT {

    static final String EID = "https://ee.ee/eid";

    static PostgreSQLContainer<?> postgres;
    static String jdbcUrl;
    static R4Store r4;
    static R5Store r5;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        jdbcUrl = SharedPostgres.urlFor("ConcurrentVersionsIT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(jdbcUrl);
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        // one database, two personalities, two domains — the founding promise
        R4Personality p4 = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation")));
        R5Personality p5 = new R5Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("SubscriptionTopic")));
        r4 = new R4Store(new PgObjectStore(pg, p4.registrations()), p4, "https://dbo.test/r4");
        r5 = new R5Store(new PgObjectStore(pg, p5.registrations()), p5, "https://dbo.test/r5");
    }

    @AfterAll
    void down() {
    }

    private static String patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(EID, eid, family);
    }

    /** Same shapes, both versions, one database — searches stay domain-isolated. */
    @Test
    @Proving(DboPromises.VER_CONCURRENT_VERSIONS)
    void bothVersionsServeTheSamePatientShapesIndependently() {
        r4.create(patient("39001010001", "NeljasFhir"));
        r5.create(patient("39001010002", "ViiesFhir"));

        String inR4 = r4.search("Patient", Map.of("identifier", EID + "|"), null);
        String inR5 = r5.search("Patient", Map.of("identifier", EID + "|"), null);
        assertEquals(1, entryCount(inR4));
        assertEquals(1, entryCount(inR5));
        assertTrue(inR4.contains("NeljasFhir"));
        assertTrue(inR5.contains("ViiesFhir"));

        // identity claims are per-domain: the same eid can exist in both
        // versions' stores (they are different tenants'/domains' worlds)
        r4.create(patient("50505050005", "Shared"));
        r5.create(patient("50505050005", "Shared"));
    }

    /** Genuine version divergence: SubscriptionTopic is an R5 citizen, storable and searchable. */
    @Test
    void r5AcceptsAndSearchesSubscriptionTopics() {
        r5.create("""
                {"resourceType":"SubscriptionTopic","status":"active",
                 "url":"https://dbo.test/topics/obs-final",
                 "title":"Final observations"}""");
        String hits = r5.search("SubscriptionTopic",
                Map.of("url", "https://dbo.test/topics/obs-final"), null);
        assertEquals(1, entryCount(hits));
    }

    /** R5 validation runs against R5 base profiles (the r5 validation-resources stack). */
    @Test
    @Proving(DboPromises.VER_PERSONALITY_OWNS_MEANING)
    void r5ValidationGatesWritesAgainstR5Profiles() {
        ValidationFailedException failure = assertThrows(ValidationFailedException.class, () ->
                r5.create("{\"resourceType\":\"Observation\",\"id\":\"x\"}"));
        assertTrue(failure.issues().stream()
                .anyMatch(i -> i.contains("status") || i.contains("code")));
    }

    /** The ported tier-1 compiler works in R5: modifiers, sort, strictness. */
    @Test
    @Proving(DboPromises.SRCH_STRICT_BY_DEFAULT)
    void r5SearchCompilationSpotChecks() {
        r5.create("""
                {"resourceType":"Observation","status":"final",
                 "meta":{"tag":[{"system":"http://example.org/tags","code":"synced"}]},
                 "code":{"coding":[{"system":"http://loinc.org","code":"R5-1"}]}}""");
        r5.create("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://loinc.org","code":"R5-2"}]}}""");

        String untagged = r5.search("Observation",
                Map.of("_tag:not", "synced", "_sort", "-_lastUpdated"), null);
        assertTrue(bodyOf(untagged).contains("R5-2"));
        assertTrue(!bodyOf(untagged).contains("R5-1"));

        assertThrows(cloud.jengu.dbo.fhir.common.UnknownSearchParameterException.class,
                () -> r5.search("Observation", Map.of("favourite-color", "blue"), null));
    }

    private static int entryCount(String bundleJson) {
        Matcher m = Pattern.compile("\"fullUrl\"").matcher(bundleJson);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String bodyOf(String bundleJson) {
        int entry = bundleJson.indexOf("\"entry\"");
        return entry < 0 ? "" : bundleJson.substring(entry);
    }
}
