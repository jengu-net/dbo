package cloud.jengu.dbo.tenant;

/**
 * The streams a tenant carries, as an observer names them.
 *
 * <p>A tenant's records are partitioned into domains and each has a feed of
 * its own, so these are not invented here — they are what the storage already
 * separates. What the enum adds is a name an observer can register against
 * without knowing which face a tenant speaks.
 *
 * <p>{@link #CONTENT} is the one that needs resolving: a tenant's records live
 * in its face's domain, except for a face root and a projection, which keep
 * theirs in the definitions domain. An observer says {@code content} and the
 * runtime works out which. That is the same distinction subscription
 * dispatching got wrong by not making it, and the reason it is made once here
 * rather than at each place that wants a feed.
 */
public enum TenantDomain {

    /** Runs, claims, milestones and closes. */
    WORK("work"),

    /** The trail. */
    AUDIT("audit"),

    /** Credentials, delegations and provisioning. */
    IDENTITY("identity"),

    /**
     * The tenant's own records, in whichever domain this tenant keeps them.
     * A tenant that holds none is not observed for it.
     */
    CONTENT(null);

    /** The property an observer carries to say which stream it wants. */
    public static final String DOMAIN = "dbo.tenant.domain";

    /**
     * The property naming the durable consumer an observer reads as.
     *
     * <p>Required, and it is the whole reason an observer is a feed consumer
     * rather than a callback: a consumer that was absent for an hour resumes
     * where it left off instead of missing the hour.
     */
    public static final String CONSUMER = "dbo.tenant.consumer";

    private final String fixed;

    TenantDomain(String fixed) {
        this.fixed = fixed;
    }

    /** How this domain is spelled in a registration. */
    public String spelling() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The domain to read on this tenant, or null where this tenant has none —
     * which is a face root or a projection asked for its content.
     */
    String on(TenantFacts facts, String recordDomain) {
        if (fixed != null) {
            return fixed;
        }
        return Boolean.TRUE.equals(facts.properties()
                .get(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN)) ? recordDomain : null;
    }

    static TenantDomain ofSpelling(String spelling) {
        for (TenantDomain domain : values()) {
            if (domain.spelling().equals(spelling)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("no such tenant domain: " + spelling);
    }
}
