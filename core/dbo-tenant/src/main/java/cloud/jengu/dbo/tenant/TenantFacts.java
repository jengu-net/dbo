package cloud.jengu.dbo.tenant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tenant is, as the properties an activity selects on.
 *
 * <p>These are facts rather than a summary: the coarse one is the kind, and
 * the specific ones are what the runtime's existing conditions are actually
 * about. Both are needed, because the conditions written inline today are not
 * all about the kind — the step surface is mounted for a tenant that declares
 * steps, which is a property of the spec and not of what kind of tenant it is.
 *
 * <p>Names under {@code dbo.tenant.} belong to dbo, on the same terms as the
 * {@code dbo.} kind namespace.
 */
public record TenantFacts(Map<String, Object> properties) {

    /** The prefix every published fact carries, and which dbo reserves. */
    public static final String PREFIX = "dbo.tenant.";

    public static final String CODE = PREFIX + "code";
    public static final String FACE = PREFIX + "face";
    public static final String ZONE = PREFIX + "zone";
    /**
     * Whether the tenant's records live in its face's record domain.
     *
     * <p>False for a face root and for a projection, which keep theirs in the
     * definitions domain — so the face domain's tables were never created in
     * their databases, and anything that assumes otherwise fails against a
     * relation that does not exist.
     */
    public static final String HOLDS_RECORDS_IN_FACE_DOMAIN = PREFIX + "holdsRecordsInFaceDomain";
    public static final String HAS_STEPS = PREFIX + "hasSteps";
    public static final String HAS_SCIM = PREFIX + "hasScim";

    public TenantFacts {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** What a spec says about itself, as the facts an activity selects on. */
    public static TenantFacts of(TenantSpec spec, boolean holdsRecordsInFaceDomain) {
        Map<String, Object> published = new LinkedHashMap<>();
        published.put(CODE, spec.code());
        published.put(FACE, spec.face());
        if (spec.zone() != null) {
            published.put(ZONE, spec.zone());
        }
        published.put(HOLDS_RECORDS_IN_FACE_DOMAIN, holdsRecordsInFaceDomain);
        published.put(HAS_STEPS, !spec.steps().isEmpty());
        published.put(HAS_SCIM, spec.scim() != null);
        return new TenantFacts(published);
    }

    public String code() {
        return (String) properties.get(CODE);
    }
}
