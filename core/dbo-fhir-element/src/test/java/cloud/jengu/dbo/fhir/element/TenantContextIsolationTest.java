package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The property the tenant view stands on: what one tenant adds to its context
 * stays its own.
 *
 * <p>Real validation never runs against the standard pack alone — it runs
 * against the pack PLUS what a tenant defined on top: its profiles, and (via
 * {@link Terms}) its terminology. {@link TenantContext} is the slot where that
 * stack assembles, and it is only sound if a resource cached into one tenant's
 * view is invisible to the shared context and to every other tenant. If this
 * test ever fails, the copy constructor has stopped cloning the canonical
 * resource managers, and per-tenant validation has silently become global.
 */
class TenantContextIsolationTest {

    @Test
    void aTenantCachedProfileIsInvisibleToTheSharedContextAndToOtherTenants() throws Exception {
        SimpleWorkerContext shared = ElementVersion.of("r4").context();
        TenantContext tenantA = new TenantContext(shared, Terms.NONE);

        StructureDefinition profile = new StructureDefinition();
        profile.setUrl("https://tenant-a.example/StructureDefinition/local-patient");
        profile.setId("local-patient");
        tenantA.cacheResource(profile);

        assertNotNull(tenantA.fetchResource(StructureDefinition.class, profile.getUrl()),
                "the tenant that cached the profile must see it");
        assertNull(shared.fetchResource(StructureDefinition.class, profile.getUrl()),
                "the shared per-version context must never see a tenant's profile");
        assertNull(new TenantContext(shared, Terms.NONE)
                        .fetchResource(StructureDefinition.class, profile.getUrl()),
                "a sibling tenant's view must never see another tenant's profile");
    }
}
