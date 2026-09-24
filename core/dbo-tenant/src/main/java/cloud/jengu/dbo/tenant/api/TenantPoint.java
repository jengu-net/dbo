package cloud.jengu.dbo.tenant.api;

/**
 * A point in a tenant's life where activities run.
 *
 * <p>Bringing a tenant up is a sequence of conditional activities, and the
 * conditions used to be written inline at each site. One of them — subscription
 * dispatching — carried no condition at all and polled a record domain that a
 * face root does not have, once a second, for the life of the deployment. The
 * failure was invisible because the poll loop swallowed it, so the only trace
 * anywhere was the database's own error log.
 *
 * <p>Naming the points is what lets an activity <em>declare</em> where it
 * applies instead of working it out. The points here are the ones the runtime
 * actually runs activities at; the rest of the sequence is named in the task
 * document and is converted one at a time, because a point nothing runs at is a
 * promise rather than a mechanism.
 */
public enum TenantPoint {

    /**
     * The tenant's store is built and its feeds exist. What runs here reads
     * or watches the tenant's own records.
     */
    DISPATCH,

    /**
     * The tenant's HTTP surfaces are mounted.
     *
     * <p>The clearest evidence that this design already existed implicitly:
     * every surface here already carried its own condition, written inline as
     * an {@code if} over the spec's shape. The conditions are the same ones;
     * what changed is that each is now declared beside the thing it governs,
     * where its absence would be a statement rather than an oversight.
     */
    SURFACES,

    /**
     * The tenant is about to be announced as serving. Everything it offers is
     * mounted; nothing after this point changes what it can be asked.
     */
    SERVING;

    /** The property a registration carries to say which point it runs at. */
    public static final String POINT = "dbo.tenant.point";

    /** The property carrying the filter that says which tenants it applies to. */
    public static final String TARGET = "dbo.tenant.target";

    /** How this point is spelled in a registration. */
    public String spelling() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
