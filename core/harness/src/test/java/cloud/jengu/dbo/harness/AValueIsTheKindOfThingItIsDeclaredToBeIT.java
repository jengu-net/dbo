package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.definitions.DefinitionStore;
import cloud.jengu.dbo.fhir.element.FaceRootPackages;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A date that is not a date, and a string standing where a boolean belongs.
 *
 * <p>The checks that read what an element must EQUAL or CONTAIN answer a
 * question about a value that is already the right kind of thing. Nothing
 * asked whether it was — so two of the three clinical divergences measured
 * against a face were a document the toolchain refused and this store had
 * nothing to say about.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AValueIsTheKindOfThingItIsDeclaredToBeIT {

    static SharedTenants.Tenant tenant;
    static String ROOT;
    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    static DefinitionStore definitions;

    @BeforeAll
    void up() throws Exception {
        // Shared. A face root holds the version's whole definition set,
        // which is the most expensive thing this suite builds — and four
        // classes were each building one to ask a question about what the
        // version says, not about the tenant holding it.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_FACE_ROOT);
        ROOT = tenant.code();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(tenant.databaseUrl());
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        definitions = new DefinitionStore(source);
    }

    private long issues(String patient) {
        return definitions.issuesUnder(patient.getBytes(StandardCharsets.UTF_8), PATIENT)
                .orElseThrow(() -> new AssertionError("this root holds no Patient to judge by"));
    }

    @Test
    @DisplayName("a date that is not a date is found, and a well-formed one is not")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aDateIsADate() {
        assertTrue(issues("{\"resourceType\":\"Patient\",\"birthDate\":\"not-a-date\"}") > 0,
                "a birthDate of 'not-a-date' was accepted, which is the divergence this "
                        + "exists to close");
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980-04-01\"}"),
                "a well-formed date was refused, which is worse than the gap it replaced");
        // The specification allows a year and a year-month, so neither is wrong.
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980\"}"));
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"birthDate\":\"1980-04\"}"));
    }

    @Test
    @DisplayName("a string where a boolean belongs is found, and a choice element keeps its "
            + "freedom")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aBooleanIsABoolean() {
        assertTrue(issues("{\"resourceType\":\"Patient\",\"deceasedBoolean\":\"yes\"}") > 0,
                "'yes' is not a boolean and is not a dateTime either, so nothing this "
                        + "element declares admits it");
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"deceasedBoolean\":true}"));
        // The other arm of the same choice: a dateTime is equally correct here,
        // and a check that judged against boolean alone would refuse it.
        assertEquals(0, issues(
                "{\"resourceType\":\"Patient\",\"deceasedDateTime\":\"2020-01-01T00:00:00Z\"}"));
    }

    @Test
    @DisplayName("what was already correct stays correct")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void nothingWellFormedBecomesAFinding() {
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Tamm\"}]}"));
        assertEquals(0, issues("{\"resourceType\":\"Patient\",\"active\":true,"
                + "\"gender\":\"female\",\"birthDate\":\"1980-04-01\","
                + "\"telecom\":[{\"system\":\"phone\",\"value\":\"+372\"}]}"));
        // A complex type is not a primitive this understands, and a check that
        // did not understand it must not refuse it.
        assertEquals(0, issues("{\"resourceType\":\"Patient\","
                + "\"managingOrganization\":{\"reference\":\"Organization/x\"}}"));
    }

    @Test
    @DisplayName("the whole corpus a face carries is still judged as it was")
    @Proving(DboPromises.VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION)
    void theCarriedCorpusIsUnmoved() {
        int looked = 0;
        int refused = 0;
        java.util.Map<String, String> canonicalOf = new java.util.HashMap<>();
        for (FaceRootPackages.Definition document : FaceRootPackages.definitionsFor("r4",
                java.util.Set.of("StructureDefinition", "SearchParameter", "ValueSet",
                        "CodeSystem"))) {
            String canonical = canonicalOf.computeIfAbsent(document.typeName(),
                    type -> definitions.theTypeItself(type).orElse(null));
            if (canonical == null) {
                continue;
            }
            if (looked++ >= 120) {
                break;
            }
            if (definitions.issuesUnder(document.document(), canonical).orElse(0) > 0) {
                refused++;
            }
        }
        assertTrue(looked > 50, "only " + looked + " documents were looked at");
        // The specification's own documents are well formed. A primitive check
        // that refused them would be refusing the corpus this store is built
        // from, which is how a rule written slightly wrong announces itself.
        assertTrue(refused * 10 < looked,
                refused + " of " + looked + " carried documents are now refused, so the new "
                        + "check is refusing the specification's own resources");
    }
}
