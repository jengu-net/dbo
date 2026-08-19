package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A FhirContext is what a version knows, so a node builds one — however many
 * tenants it serves.
 *
 * <p>It used to be a field of the personality, and a personality is per tenant.
 * Twenty tenants meant twenty of the most expensive object in the runtime, and
 * every tenant built two of them because the maintenance surface constructed a
 * second personality of its own. Neither was visible in any test: the only
 * symptom was the same line twice in a startup log.
 */
class OneContextPerVersionTest {

    private static R5Personality personalityFor(String system) {
        return new R5Personality(List.of(
                new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER, Set.of(system),
                        Handling.operational())));
    }

    @Test
    @DisplayName("two tenants on one version share the version's context")
    void twoTenantsShareOneContext() {
        // two personalities as two tenants would have them: different declared
        // types, same version
        assertSame(personalityFor("urn:a").ctxInternal(), personalityFor("urn:b").ctxInternal(),
                "a context is being built per tenant again");
    }

    @Test
    @DisplayName("and a second personality for the same tenant shares it too")
    void aSecondPersonalityForOneTenantSharesIt() {
        // the maintenance surface builds its own, which is what made the cost
        // two per tenant rather than one
        R5Personality serving = personalityFor("urn:same");
        R5Personality maintenance = personalityFor("urn:same");
        assertSame(serving.ctxInternal(), maintenance.ctxInternal());
    }
}
