package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.SpecChange;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classification has to cover the whole declaration, because the answer it
 * gives for a field nobody thought about is "nothing changed" — which is the
 * silence the classification exists to end, arriving by a different route.
 *
 * <p>This cannot check that each field is classified <i>correctly</i>; it can
 * check that adding one to the declaration and forgetting it is a failing
 * build rather than a change that quietly never applies.
 */
class EveryDeclaredFieldIsClassifiedTest {

    /**
     * Every component of the declaration, and where it is answered.
     * {@code code} is the tenant itself: a different code is a different
     * tenant, not a tenant changed.
     */
    private static final List<String> CLASSIFIED = List.of(
            "code", "face", "types", "pdi", "policies", "zone", "broker",
            "acceptedBrokers", "dependencies", "scim", "mandatorySteps", "managedBy", "faceRoot");

    @Test
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    @DisplayName("a field added to a declaration is classified, or this fails")
    void everyComponentOfADeclarationIsAccountedFor() {
        List<String> declared = Arrays.stream(TenantSpec.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(CLASSIFIED, declared,
                "the declaration gained or lost a field. Classify it in SpecChange — hot if "
                        + "the tenant absorbs it while serving, rewire if its surfaces have "
                        + "to be rebuilt, cold if it moves what is already stored — and name "
                        + "it here. A field nobody classifies reads as no change at all.");
    }

    /** And the classifier answers, rather than falling through to "nothing". */
    @Test
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void aDeclarationEqualToItselfIsNoChange() {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"same","face":"r4","types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}]}""");
        assertEquals(SpecChange.NONE.fields(), SpecChange.between(spec, spec).fields());
    }
}
