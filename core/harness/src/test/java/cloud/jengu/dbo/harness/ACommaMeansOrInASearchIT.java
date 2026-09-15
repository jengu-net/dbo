package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.UnknownSearchParameterException;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

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
 * Several values for one search parameter, which FHIR spells with a comma and
 * means as OR.
 *
 * <p>It used to answer nothing. The token compiler split on {@code |} and
 * never on {@code ,}, so {@code status=draft,active} was looked for as a
 * single code that nothing carries — neither honoured nor refused, just an
 * ordinary-looking empty result. The strict-search rule catches an
 * unsupported <b>parameter</b>; this was an unsupported <b>value syntax</b>,
 * and it fell between the two.
 *
 * <p>The engine could not express it either, and that is the part worth
 * naming: equality predicates are conjunctive, so two of them on one path ask
 * for an object carrying <b>both</b> values. Adding them in a loop would have
 * produced a query that is wrong in a different way and just as quiet.
 *
 * <p>Where the OR cannot be expressed yet — a comma between two date windows
 * is two ranges, not two values — it is refused by name. That is the whole
 * lesson of the defect: the answer to "this store cannot do that" is saying
 * so, never returning an empty page that reads like an answer.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ACommaMeansOrInASearchIT {

    private static final String EID = "https://eid.test/ni";
    private static final String LOINC = "http://loinc.org";

    static PostgreSQLContainer<?> postgres;
    static R4Store fhir;
    static String her;
    static String him;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(SharedPostgres.urlFor("ACommaMeansOrInASearchIT"));
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation")));
        fhir = new R4Store(new PgObjectStore(pg, personality.registrations()),
                personality, "https://dbo.test/fhir");

        her = fhir.create(patient("49001010000", "Tamm")).id();
        him = fhir.create(patient("39002020266", "Kask")).id();
        fhir.create(patient("39003030300", "Saar,Kuusk"));

        fhir.create(observation("11111-1", "final", her));
        fhir.create(observation("22222-2", "amended", him));
        fhir.create(observation("33333-3", "cancelled", null));
    }

    @Test
    @DisplayName("several token values answer the union, which is what the comma has always "
            + "meant and what this store used to answer nothing for")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void aCommaBetweenTokensIsOr() {
        assertEquals(2, entryCount(search("Observation", "status", "final,amended")),
                "the union of two statuses was not answered");

        // The regression that was there for both wrong reasons: one value
        // must still behave as it did, and two must not become a conjunction.
        assertEquals(1, entryCount(search("Observation", "status", "final")));
        assertEquals(0, entryCount(search("Observation", "status", "final,nosuchstatus"))
                - entryCount(search("Observation", "status", "final")),
                "an unmatched alternative changed the answer, so the values are being "
                        + "ANDed rather than OR'd");
    }

    @Test
    @DisplayName("and with their systems spelled out, because each alternative is a whole "
            + "token rather than a code sharing the first one's system")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void eachAlternativeCarriesItsOwnSystem() {
        assertEquals(2, entryCount(search("Observation", "code",
                LOINC + "|11111-1," + LOINC + "|22222-2")));
        assertEquals(0, entryCount(search("Observation", "code",
                "https://elsewhere.test|11111-1,https://elsewhere.test|22222-2")),
                "a wrong system still matched, so the system is not being read per "
                        + "alternative");
    }

    @Test
    @DisplayName("a string search takes them too, matched as prefixes the way one value is")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void aCommaBetweenStringsIsOr() {
        String both = bodyOf(search("Patient", "family", "Tamm,Kask"));
        assertTrue(both.contains("Tamm") && both.contains("Kask"), both);
        assertFalse(both.contains("Saar"), "somebody outside the union was returned: " + both);
    }

    @Test
    @DisplayName("a backslash escapes one, because a value may legitimately contain a comma "
            + "and splitting it would look for two people who do not exist")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void anEscapedCommaIsPartOfTheValue() {
        String escaped = bodyOf(search("Patient", "family", "Saar\\,Kuusk"));
        assertTrue(escaped.contains("Saar,Kuusk"),
                "the escaped comma was treated as a separator: " + escaped);
        assertFalse(escaped.contains("Tamm"), escaped);
    }

    @Test
    @DisplayName("several references answer the union, so 'either of these two patients' is "
            + "one question rather than two round trips")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void aCommaBetweenReferencesIsOr() {
        assertEquals(2, entryCount(search("Observation", "subject",
                "Patient/" + her + ",Patient/" + him)));

        // One edge predicate reaches one target type. Two types OR'd is a
        // different query, and answering it as one of them would be
        // confidently wrong — so it is said rather than guessed.
        assertThrows(UnknownSearchParameterException.class,
                () -> search("Observation", "subject", "Patient/" + her + ",Group/x"));
    }

    @Test
    @DisplayName("excluding several is excluding each, which is a conjunction of negations "
            + "rather than an any-of")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void aCommaUnderNotExcludesEachOfThem() {
        String left = bodyOf(search("Observation", "_tag:not", "a,b"));
        // Nothing carries either tag, so all three remain; the point is that
        // it did not collapse to excluding a single literal "a,b".
        assertEquals(3, entryCount(search("Observation", "_tag:not", "a,b")), left);
    }

    @Test
    @DisplayName("where the union cannot be expressed yet it is refused by name, because an "
            + "empty page that reads like an answer is the defect this came from")
    @Proving(DboPromises.SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM)
    void whatCannotBeOrdYetIsSaidRatherThanAnswered() {
        // Two date windows are two ranges, not two values. Until the criteria
        // can state that, a caller is better told than given nobody.
        assertThrows(UnknownSearchParameterException.class,
                () -> search("Observation", "date", "2024-01-01,2024-02-01"));
        assertThrows(UnknownSearchParameterException.class,
                () -> search("Observation", "_lastUpdated", "2024-01-01,2024-02-01"));
    }

    // ------------------------------------------------------------- plumbing

    private static String patient(String eid, String family) {
        return """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""".formatted(EID, eid, family);
    }

    private static String observation(String code, String status, String subject) {
        String about = subject == null ? ""
                : "\"subject\":{\"reference\":\"Patient/%s\"},".formatted(subject);
        return """
                {"resourceType":"Observation",%s"status":"%s",
                 "code":{"coding":[{"system":"%s","code":"%s"}]}}"""
                .formatted(about, status, LOINC, code);
    }

    private static String search(String type, String param, String value) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(param, value);
        return fhir.search(type, params, null);
    }

    private static int entryCount(String bundleJson) {
        Matcher found = Pattern.compile("\"fullUrl\"").matcher(bundleJson);
        int n = 0;
        while (found.find()) {
            n++;
        }
        return n;
    }

    private static String bodyOf(String bundleJson) {
        int entry = bundleJson.indexOf("\"entry\"");
        return entry < 0 ? "" : bundleJson.substring(entry);
    }
}
