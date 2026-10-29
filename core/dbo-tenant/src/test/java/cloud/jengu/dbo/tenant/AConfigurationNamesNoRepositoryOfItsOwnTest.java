package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration taking its owner's configuration, now that the store keeps the
 * repository.
 *
 * <p>A declaration used to name a path the deployment kept a copy of. A path
 * still named would be read by nothing, and the owner would push to the store
 * while a copy somewhere else went stale beside it unread: refused, saying
 * where the repository is now.
 */
class AConfigurationNamesNoRepositoryOfItsOwnTest {

    private static final String DECLARED = """
            {"code":"clinic","face":"r5",
             %s
             "types":[{"name":"Organization","identity":"internal","handling":"operational"}]}""";

    @Test
    @DisplayName("a declaration naming a repository of its own is refused, saying where the "
            + "store keeps it; one naming a ref alone takes the owner's configuration")
    @Proving(DboPromises.TEN_A_TENANTS_CONFIGURATION_REPOSITORY_IS_SERVED_UNDER_ITS_AUTHORITY)
    void aRepositoryOfItsOwnIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse(DECLARED.formatted(
                        "\"configuration\":{\"git\":\"/srv/config/clinic.git\",\"ref\":\"main\"},")));
        assertTrue(refused.getMessage().contains("/configuration.git"), refused.getMessage());

        assertEquals("main", TenantSpec.parse(DECLARED.formatted(
                "\"configuration\":{\"ref\":\"main\"},")).configuration().ref());
        assertEquals("HEAD", TenantSpec.parse(DECLARED.formatted(
                "\"configuration\":{},")).configuration().ref());
    }
}
