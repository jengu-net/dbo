package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a step's declared secrets reach: names, and families by a trailing .* alone. */
class ASecretFamilyIsATrailingDotStarTest {

    private static TenantSpec declaring(String secrets) {
        return TenantSpec.parse("""
                {"code":"clinic","face":"r5",
                 "types":[{"name":"Organization","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"clinic.lab.connect","slots":{"organisation":"Organization"},
                   "secrets":[%s]}]}""".formatted(secrets));
    }

    @Test
    @DisplayName("a family reaches every name its prefix extends, at any depth, and nothing "
            + "beside it; names and families are declared together")
    @Proving(DboPromises.TEN_A_STEP_REACHES_A_FAMILY_OF_SECRETS)
    void aFamilyIsAPrefix() {
        Set<String> declared = declaring("\"connection.*\",\"zone.token\"").steps().get(0)
                .secrets();
        assertTrue(TenantSpec.reaches(declared, "connection.sample-1.password"));
        assertTrue(TenantSpec.reaches(declared, "connection.b.token"));
        assertTrue(TenantSpec.reaches(declared, "zone.token"));
        assertFalse(TenantSpec.reaches(declared, "connections.sample-1.password"));
        assertFalse(TenantSpec.reaches(declared, "connection"));
        assertFalse(TenantSpec.reaches(declared, "connection."));
        assertFalse(TenantSpec.reaches(declared, "zone.token.other"));
    }

    @Test
    @DisplayName("a * anywhere but a trailing .* is refused when the tenant is declared")
    @Proving(DboPromises.TEN_A_STEP_REACHES_A_FAMILY_OF_SECRETS)
    void onlyATrailingDotStarIsAFamily() {
        for (String wrong : new String[] {"\"*\"", "\".*\"", "\"connection*\"",
                "\"connection.*.password\"", "\"*.password\""}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> declaring(wrong), wrong);
            assertTrue(refused.getMessage().contains("trailing .*"), refused.getMessage());
        }
    }
}
