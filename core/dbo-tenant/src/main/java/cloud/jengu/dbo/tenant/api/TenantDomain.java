package cloud.jengu.dbo.tenant.api;

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

    /**
     * Runs, claims, milestones and closes.
     *
     * <p>Carries its content, because it is the machinery's own bookkeeping:
     * a task describing the delivery of a task does not terminate, which is
     * the self-reference the direct list already admits.
     */
    WORK("work", true),

    /**
     * The trail.
     *
     * <p><b>Does not carry its content</b>, and it is the domain where the
     * temptation is greatest. Reading the trail is the most sensitive read in
     * the store, because it says who saw whom — so an observer of it learns
     * that an entry was written and reads it through a step like anyone else.
     */
    AUDIT("audit", false),

    /**
     * Credentials, delegations and provisioning.
     *
     * <p>Carries its content, on the same reason as work: these are how a task
     * comes to be authorised at all, so requiring a task to observe them is
     * the regress self-reference exists for.
     */
    IDENTITY("identity", true),

    /**
     * The tenant's own records, in whichever domain this tenant keeps them.
     * A tenant that holds none is not observed for it.
     *
     * <p><b>Does not carry its content.</b> This is the surface the boundary
     * document names: a feed of record content on the control plane makes
     * every integrator take the feed instead of the data plane, and the
     * boundary is decorative.
     */
    CONTENT(null, false);

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
    private final boolean carriesContent;

    TenantDomain(String fixed, boolean carriesContent) {
        this.fixed = fixed;
        this.carriesContent = carriesContent;
    }

    /**
     * Whether an observer of this stream is handed what changed as well as
     * that it changed.
     *
     * <p>True only where one of the five enumerated reasons already admits a
     * direct operation, and each constant says which. Everything else is a
     * crossing of the boundary a run draws, and a feed cannot declare its way
     * across it: a declaration of types with no anchor is type-level access
     * control wearing a step's clothing, and a feed has no anchor to give.
     */
    public boolean carriesContent() {
        return carriesContent;
    }

    /** How this domain is spelled in a registration. */
    public String spelling() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The domain to read on this tenant, or null where this tenant has none —
     * which is a face root or a projection asked for its content.
     */
    public String on(TenantFacts facts, String recordDomain) {
        if (fixed != null) {
            return fixed;
        }
        return Boolean.TRUE.equals(facts.properties()
                .get(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN)) ? recordDomain : null;
    }

    public static TenantDomain ofSpelling(String spelling) {
        for (TenantDomain domain : values()) {
            if (domain.spelling().equals(spelling)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("no such tenant domain: " + spelling);
    }
}
