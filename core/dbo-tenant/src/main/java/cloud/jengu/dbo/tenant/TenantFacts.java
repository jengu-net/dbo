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
    /**
     * Whether the tenant has an authority of its own.
     *
     * <p>Not a fact about the spec: whether a tenant gets an authority follows
     * from how the deployment is configured. It is published because it is the
     * condition several surfaces already carry — a tenant with no authority
     * has no way to say who is asking, so a private door it served would be an
     * open one.
     */
    public static final String HAS_AUTHORITY = PREFIX + "hasAuthority";
    /** Whether a person vault was built, which is what erasure acts through. */
    public static final String HAS_VAULT = PREFIX + "hasVault";
    /**
     * Whether the tenant declares a type that holds identities.
     *
     * <p>Read off what was declared rather than named here, for the reason the
     * face domain fact is: {@code Person} is the FHIR face's word, and another
     * face's would be a different one.
     */
    public static final String HOLDS_IDENTITIES = PREFIX + "holdsIdentities";

    /**
     * Every name a tenant publishes, and therefore every name a selector may
     * ask about.
     *
     * <p>{@code zone} is here although a tenant that names none does not
     * publish it: absence is an answer a filter can give, and a filter that
     * asks whether a zone is {@code rl} is asking a question this vocabulary
     * has.
     */
    public static final java.util.Set<String> PUBLISHED = java.util.Set.of(
            CODE, FACE, ZONE, HOLDS_RECORDS_IN_FACE_DOMAIN, HAS_STEPS, HAS_SCIM,
            HAS_AUTHORITY, HAS_VAULT, HOLDS_IDENTITIES);

    /**
     * The ratchet: a selector asks about facts a tenant actually publishes.
     *
     * <p>Refused where it is registered, for the reason an unparseable filter
     * is. A filter naming {@code dbo.tenant.hasVualt} parses perfectly and
     * matches nothing, for ever, saying nothing — which is the same shape of
     * silent, invisible wrongness this whole mechanism replaced. A deployment
     * would see an activity that simply never runs.
     *
     * @param target the filter as written, or null
     * @param name   the registration, so the refusal says which one to fix
     */
    static void refuseUnpublishedFacts(String target, String name) {
        if (target == null) {
            return;
        }
        java.util.regex.Matcher asked = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(PREFIX) + "[A-Za-z0-9_.]+")
                .matcher(target);
        while (asked.find()) {
            if (!PUBLISHED.contains(asked.group())) {
                throw new IllegalArgumentException(name + ": the target filter asks about '"
                        + asked.group() + "', which no tenant publishes — it would match "
                        + "nothing, for ever, and say nothing about why. A tenant publishes: "
                        + new java.util.TreeSet<>(PUBLISHED));
            }
        }
    }

    public TenantFacts {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * The facts a spec cannot answer about itself.
     *
     * <p>Each is resolved by the bring-up that knows it rather than inferred
     * from the spec's shape, which is the mistake this whole mechanism exists
     * to stop repeating: {@code faceRoot || a face dependency} looked like the
     * answer to the first of these and was wrong for a zone.
     */
    public record Resolved(boolean holdsRecordsInFaceDomain, boolean hasAuthority,
            boolean hasVault, boolean holdsIdentities) {
    }

    /** What a tenant says about itself, as the facts an activity selects on. */
    public static TenantFacts of(TenantSpec spec, Resolved resolved) {
        Map<String, Object> published = new LinkedHashMap<>();
        published.put(CODE, spec.code());
        published.put(FACE, spec.face());
        if (spec.zone() != null) {
            published.put(ZONE, spec.zone());
        }
        published.put(HOLDS_RECORDS_IN_FACE_DOMAIN, resolved.holdsRecordsInFaceDomain());
        published.put(HAS_STEPS, !spec.steps().isEmpty());
        published.put(HAS_SCIM, spec.scim() != null);
        published.put(HAS_AUTHORITY, resolved.hasAuthority());
        published.put(HAS_VAULT, resolved.hasVault());
        published.put(HOLDS_IDENTITIES, resolved.holdsIdentities());
        return new TenantFacts(published);
    }

    public String code() {
        return (String) properties.get(CODE);
    }
}
