package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.tenant.api.TenantFacts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tenant publishes about itself, built from what it declared.
 *
 * <p>Here rather than on {@link TenantFacts} because it reads a
 * {@link TenantSpec}, and the facts are the half a host implements against.
 * An API type that knew the shape of this deployment's configuration would
 * drag that configuration into every application that implements an
 * extension point.
 */
final class PublishedFacts {

    private PublishedFacts() {
    }

    /** What a tenant says about itself, as the facts an activity selects on. */
    public static TenantFacts of(TenantSpec spec, TenantFacts.Resolved resolved) {
        Map<String, Object> published = new LinkedHashMap<>();
        published.put(TenantFacts.CODE, spec.code());
        published.put(TenantFacts.FACE, spec.face());
        if (spec.zone() != null) {
            published.put(TenantFacts.ZONE, spec.zone());
        }
        published.put(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN, resolved.holdsRecordsInFaceDomain());
        published.put(TenantFacts.HAS_STEPS, !spec.steps().isEmpty());
        published.put(TenantFacts.HAS_SCIM, spec.scim() != null);
        published.put(TenantFacts.HAS_AUTHORITY, resolved.hasAuthority());
        published.put(TenantFacts.HAS_VAULT, resolved.hasVault());
        published.put(TenantFacts.HOLDS_IDENTITIES, resolved.holdsIdentities());
        return new TenantFacts(published);
    }
}
