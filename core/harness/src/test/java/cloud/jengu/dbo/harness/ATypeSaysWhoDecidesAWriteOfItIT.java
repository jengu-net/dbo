package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whose answer decides a write, said by the type rather than by the release.
 *
 * <p>The two checkers have been measured beside each other for a long time and
 * the toolchain's answer was always the one used. What was missing was not
 * confidence but a way to say so: a deployment cannot move all of validation
 * at once, and a tenant that could not move any of it was waiting on a
 * decision nobody could make in pieces.
 *
 * <p>Per type, declared, and never inferred — the rule {@code extraction}
 * already follows, for a sharper reason. An envelope computed the wrong way
 * makes a document unfindable; a verdict decided the wrong way changes what
 * this store ACCEPTS.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATypeSaysWhoDecidesAWriteOfItIT {

    static SharedTenants.Tenant tenant;
    static String ROOT;


    @BeforeAll
    void up() throws Exception {
        // Shared. A face root holds the version's whole definition set,
        // which is the most expensive thing this suite builds — and four
        // classes were each building one to ask a question about what the
        // version says, not about the tenant holding it.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        ROOT = tenant.code();
    }

    private static void write(String document) {
        tenant.store().create(document);
    }

    @Test
    @DisplayName("a type whose verdict is the database's is refused by the database's own "
            + "words, naming what is wrong and where")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void theDatabaseDecidesAndSaysWhy() {
        // The one divergence where this store is stricter than the toolchain,
        // and it is right: gender is 0..1, an array is not one value, and the
        // toolchain says nothing at all about it.
        ValidationFailedException refused = assertThrows(ValidationFailedException.class,
                () -> write("{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Kaks\"}],"
                        + "\"gender\":[\"male\",\"female\"]}"),
                "the type says the database decides, and a document only the database "
                        + "refuses was accepted — so the declaration changed nothing");

        assertTrue(refused.getMessage().contains("Patient.gender"),
                "the refusal has to name where the problem is, or a writer can only send "
                        + "the same thing again: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("0..1"),
                "and what the rule was: " + refused.getMessage());
    }

    @Test
    @DisplayName("what the database is happy with is still written")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aCleanWriteIsUnaffected() {
        assertDoesNotThrow(() -> write(
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Tamm\"}],"
                        + "\"gender\":\"female\",\"birthDate\":\"1980-04-01\"}"),
                "an ordinary patient was refused, which is worse than the gap it replaced");
    }

    /**
     * The whole point of declaring it per type: a deployment moves one type at
     * a time and watches, rather than moving all of validation on one day.
     */
    @Test
    @DisplayName("a type that said nothing is still decided by the toolchain, so the switch "
            + "is one type's and not the release's")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aTypeThatSaidNothingIsUnchanged() {
        // The same shape of mistake on a type that did not ask: `issued` is
        // 0..1 too. The database would refuse it and is not being asked, and
        // the toolchain does not — so it lands, exactly as it did yesterday.
        assertDoesNotThrow(() -> write("{\"resourceType\":\"Observation\",\"status\":\"final\","
                        + "\"code\":{\"text\":\"kaks korda\"},"
                        + "\"issued\":[\"2020-01-01T00:00:00Z\",\"2021-01-01T00:00:00Z\"]}"),
                "a type that declared nothing started being decided by this store, so the "
                        + "declaration is a release-wide switch wearing a per-type label");
    }
}
