package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three shapes a token search comes in, and the count that answers them
 * (#81).
 *
 * <p>FHIR's token syntax is three questions, not one: {@code sys|code} is this
 * code in this system, {@code code} is this code in any system, and {@code
 * sys|} is <b>anything at all</b> in this system. A form the index does not
 * carry answers zero, and zero looks like an answer — which is how a drift
 * report came to say every identifier was synthetic.
 *
 * <p><b>Asserted on entries, never on the body.</b> The self link repeats the
 * query, so a bundle with no results contains the value that was searched for:
 * the check that found this regression had passed for exactly that reason.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenSearchFormsIT {

    private static final String EID = "https://ee.ee/eid";
    private static final String OTHER = "https://ee.ee/other";

    static FhirStoreFacade fhir;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("TokenSearchFormsIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.identifier("Practitioner", EID)));
        fhir = declared.store(new PgObjectStore(ds, declared.registrations()),
                "https://dbo.test/fhir");
        fhir.create(practitioner(EID, "kood-1"));
        fhir.create(practitioner(EID, "kood-2"));
        fhir.create(practitioner(OTHER, "kood-3"));
    }

    private static String practitioner(String system, String value) {
        return "{\"resourceType\":\"Practitioner\",\"identifier\":[{\"system\":\"" + system
                + "\",\"value\":\"" + value + "\"}]}";
    }

    /** How many the search actually matched, from the entries rather than the body. */
    private static int matched(String parameter) {
        String bundle = fhir.search("Practitioner", Map.of("identifier", parameter), null);
        int entries = bundle.split("\"resource\"", -1).length - 1;
        return entries;
    }

    private static long counted(String parameter) {
        String bundle = fhir.search("Practitioner",
                Map.of("identifier", parameter, "_summary", "count"), null);
        java.util.regex.Matcher total = java.util.regex.Pattern.compile("\"total\":(\\d+)")
                .matcher(bundle);
        assertTrue(total.find(), bundle);
        return Long.parseLong(total.group(1));
    }

    @Test
    @DisplayName("system and value matches that one")
    @Proving(DboPromises.SRCH_TYPED_ORDERING)
    void systemAndValue() {
        assertEquals(1, matched(EID + "|kood-1"));
        assertEquals(1, counted(EID + "|kood-1"));
    }

    @Test
    @DisplayName("a code with no system matches it in whatever system it is in")
    @Proving(DboPromises.SRCH_TYPED_ORDERING)
    void bareCode() {
        assertEquals(1, matched("kood-3"),
                "a bare code is a question about the code, and the system is not part of it");
        assertEquals(1, counted("kood-3"));
    }

    @Test
    @DisplayName("a system with no value matches everything in that system")
    @Proving(DboPromises.SRCH_TYPED_ORDERING)
    void systemOnly() {
        assertEquals(2, matched(EID + "|"),
                "sys| asks how many carry an identifier in that system at all");
        assertEquals(2, counted(EID + "|"),
                "and _summary=count answers with that number — the shape a caller uses "
                        + "precisely because it discloses nothing else");
        assertEquals(1, counted(OTHER + "|"));
    }

    @Test
    @DisplayName("a system nothing uses answers zero, which is a different fact from a form "
            + "that never matches")
    void anUnusedSystemIsZero() {
        assertEquals(0, counted("https://ee.ee/unused|"));
        assertFalse(fhir.search("Practitioner",
                Map.of("identifier", "https://ee.ee/unused|"), null).contains("\"resource\""));
    }
}
