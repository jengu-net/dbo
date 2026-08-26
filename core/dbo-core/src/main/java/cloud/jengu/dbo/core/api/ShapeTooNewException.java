package cloud.jengu.dbo.core.api;

/**
 * A stored object whose shape stamp is above what this tenant's pack
 * declares — refused rather than served.
 *
 * <p>The one failure mode worse than an error: a silent misreading is
 * indistinguishable from a correct read to whoever is holding the result. An
 * object written under a shape this store does not yet carry may mean
 * something other than what its bytes appear to say, and there is no honest
 * way to serve it — so it is named instead.
 *
 * <p>In the core API rather than beside a face, and for the same reason as
 * {@link HandlingRefusedException}: every surface that reads has to be able
 * to tell this apart from a fault. It is <b>not</b> an authorization failure
 * and not a malformed request — it is a conflict between the tenant's data
 * and the tenant's pack, cleared by upgrading the pack or by converting the
 * stock, and a caller who cannot tell those apart cannot act.
 */
public class ShapeTooNewException extends RuntimeException {

    private final String typeName;
    private final String id;
    private final String profile;
    private final String stamped;
    private final String declared;

    public ShapeTooNewException(String typeName, String id, String profile, String stamped,
            String declared) {
        super(typeName + "/" + id + " was written under " + profile + "|" + stamped
                + ", and this tenant's pack declares " + profile + "|" + declared
                + " — the store cannot claim to understand a shape newer than the one it "
                + "carries, and reading it as if it could would be a wrong answer wearing "
                + "the shape of a right one. Upgrade the pack, or convert the stock.");
        this.typeName = typeName;
        this.id = id;
        this.profile = profile;
        this.stamped = stamped;
        this.declared = declared;
    }

    public String typeName() {
        return typeName;
    }

    public String id() {
        return id;
    }

    public String profile() {
        return profile;
    }

    /** The version the object was written under. */
    public String stamped() {
        return stamped;
    }

    /** The version the tenant's pack carries for that shape today. */
    public String declared() {
        return declared;
    }
}
