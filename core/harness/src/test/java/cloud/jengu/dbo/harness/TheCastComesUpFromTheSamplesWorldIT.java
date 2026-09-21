package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenants this suite shares are the ones the guide's world mounts, from
 * the same files.
 *
 * <p>Two definitions of one tenant is two tenants that can differ, and the
 * difference shows up as a disagreement between what a chapter says and what
 * a test proves — late, and as somebody else's confusing failure. So the
 * sample owns the specs, the guide's container mounts them, and this suite
 * brings the same files up in its own JVM.
 *
 * <p>What this proves is that they still come up here: a spec the guide can
 * serve and this cannot is the drift, arriving as a bring-up failure with a
 * name on it rather than as a shape somebody quietly wrote a second time.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheCastComesUpFromTheSamplesWorldIT {

    @Test
    @DisplayName("the clinic comes up from the sample's own spec, with the hospital and the "
            + "zone it declares serving before it")
    void theCastComesUpInOrder() {
        SharedTenants.Tenant clinic = SharedTenants.cast(SharedTenants.Cast.CLINIC);

        assertEquals("st-jerome", clinic.code());
        // Its own records, under ids it assigned: the part of the cast no
        // other member plays, and the reason it exists.
        String written = clinic.store().create("""
                {"resourceType":"Patient","name":[{"family":"Nobody"}]}""").id();
        assertTrue(written != null && !written.isBlank(),
                "the clinic could not write a patient of its own");
        assertTrue(clinic.engine().select(Criteria.of("Patient")).size() >= 1,
                "the patient this test wrote is not in the clinic's own store");
    }
}
