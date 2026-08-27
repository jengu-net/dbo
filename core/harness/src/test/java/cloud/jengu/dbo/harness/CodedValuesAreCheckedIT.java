package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a coded value is checked against, and what the answer says about it
 * (#50).
 *
 * <p>A resource can be syntactically valid and semantically empty — a code no
 * consumer can resolve — and that failure is the expensive kind: found by
 * whoever tries to use the data rather than by whoever entered it.
 *
 * <p>The three answers this pins apart: a <b>required</b> binding violated is a
 * refusal, a <b>weaker</b> binding violated is advice a caller is given rather
 * than refused for, and a code from a system nothing here carries is
 * <b>unresolvable</b> — a statement about this store's content, not about the
 * caller's data.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodedValuesAreCheckedIT {

    static FhirStoreFacade fhir;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("CodedValuesAreCheckedIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.internal("Patient"),
                FhirTypeConfig.internal("Observation")));
        fhir = declared.store(new PgObjectStore(ds, declared.registrations()),
                "https://dbo.test/fhir");
    }

    @Test
    @DisplayName("a required binding violated is a refusal, on the write and in the outcome")
    @Proving({DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
            DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER})
    void aRequiredBindingIsARefusal() {
        String unicorn = "{\"resourceType\":\"Patient\",\"gender\":\"unicorn\"}";

        assertThrows(ValidationFailedException.class, () -> fhir.create(unicorn),
                "a required binding is what a write is held to");

        String outcome = fhir.validationOutcome(unicorn);
        assertTrue(outcome.contains("\"severity\":\"error\""), outcome);
        assertTrue(outcome.contains("unicorn"), outcome);
    }

    @Test
    @DisplayName("a weaker binding violated is advice, which the caller is given rather than "
            + "refused for")
    @Proving({DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
            DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER})
    void aWeakerBindingIsAdvice() {
        // Observation.code is an example binding: a local code is allowed, and
        // a store that refused one would refuse most real laboratory content.
        String localCode = """
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://example.test/local","code":"kk-7"}]}}""";

        assertTrue(fhir.validationOutcome(localCode).contains("\"severity\""),
                "the outcome says something about it");
        assertFalse(fhir.validationOutcome(localCode).contains("\"severity\":\"error\""),
                "and what it says is not a refusal: " + fhir.validationOutcome(localCode));
        // the write goes through, which is the same answer read the other way
        assertFalse(fhir.create(localCode).id().isBlank());
    }

    @Test
    @DisplayName("everything the face had to say survives, not only what would refuse")
    @Proving({DboPromises.TERM_VALIDATION_USES_TENANT_TERMINOLOGY,
            DboPromises.VAL_BINDING_STRENGTH_IS_THE_ANSWER})
    void adviceIsNotDiscarded() {
        // A resource with nothing wrong enough to refuse, and something worth
        // saying: an unknown system behind an extensible binding.
        String outcome = fhir.validationOutcome("""
                {"resourceType":"Observation","status":"final",
                 "code":{"coding":[{"system":"http://example.test/nothing-carries-this",
                                    "code":"x"}]},
                 "bodySite":{"coding":[{"system":"http://example.test/also-unknown",
                                        "code":"y"}]}}""");

        assertTrue(outcome.contains("\"issue\":[{"), outcome);
        assertFalse(outcome.contains("No issues detected"),
                "discarding everything below error taught callers nothing at all: " + outcome);
        assertTrue(outcome.contains("not-found") || outcome.contains("code-invalid"),
                "an unresolvable system is a statement about this store's content rather than "
                        + "about the caller's data: " + outcome);
    }
}
