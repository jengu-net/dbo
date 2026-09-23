package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant taking a dialect from a sender it cannot change says so, per type.
 *
 * <p>It could not be said by authoring definitions, which is why it is a
 * declaration. A tenant's own profiles are offered on top of the face's, but a
 * canonical under the specification's own namespace is skipped — so the base
 * definitions cannot be replaced — and a FHIR profile only ever constrains:
 * there is no construct for permitting an element the base resource does not
 * define, and the refusal comes from the parser before any profile is read.
 */
class ATypeSaysWhatItDoesWithWhatItCannotReadTest {

    private static final String SPEC = """
            {"code":"murre","face":"r5","types":[
              {"name":"Observation","identity":"internal","handling":"operational"%s}]}""";

    @Test
    @Proving(DboPromises.VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED)
    @DisplayName("a type says it keeps what it cannot read, and a type that says nothing "
            + "refuses as it always did")
    void keptIsDeclaredAndRefusedIsTheDefault() {
        assertEquals(FhirTypeConfig.Unknown.KEPT,
                TenantSpec.parse(SPEC.formatted(",\"unknown\":\"kept\""))
                        .types().get(0).unknown(),
                "a type told to keep what it cannot read did not carry the declaration");

        assertEquals(FhirTypeConfig.Unknown.REFUSED,
                TenantSpec.parse(SPEC.formatted("")).types().get(0).unknown(),
                "a type that said nothing about unknown content changed what it does, so "
                        + "every existing spec quietly started keeping what it used to refuse");
    }

    @Test
    @DisplayName("and a word that is neither is refused where the file can be fixed")
    void aTypoIsNotATolerance() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse(SPEC.formatted(",\"unknown\":\"keep\"")));
        assertTrue(refused.getMessage().contains("kept"),
                "the refusal does not say what to write instead: " + refused.getMessage());
    }
}
