package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A face root holds its version's own search parameters as records, and those
 * author nothing.
 *
 * <p>A tenant authoring a parameter has the affected types reindexed, so its
 * history answers the new search. A face root stores the parameters the
 * version already carries, with the same codes and expressions; extraction
 * already uses them, so reindexing under them rebuilds every definition the
 * root holds into the envelope it already had. On r5 that was 6,379 records
 * and four minutes on the reconciler's thread, during which no other tenant in
 * the deployment synced anything.
 *
 * <p>The first call is the one that matters: nothing has recorded what the
 * root authored yet, so everything it holds looks new.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AVersionsOwnParametersAuthorNothingIT {

    @Test
    @DisplayName("the r5 face root's own search parameters reindex nothing, because the "
            + "version already extracts every one of them")
    void theVersionsOwnParametersReindexNothing() {
        SharedTenants.Tenant root = SharedTenants.cast(SharedTenants.Cast.R5_ROOT);

        int reindexed = SharedTenants.manager().runtime(root.code()).orElseThrow()
                .store().searchParametersChanged();

        assertEquals(0, reindexed,
                "the face root reindexed records under parameters its version already "
                        + "carries, which rebuilds every definition into the envelope it had");
    }
}
