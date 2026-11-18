package cloud.jengu.dbo.core.api;

/**
 * A record held to one organisation of the tenant: read only by those who act
 * there, and by nobody else in the tenant.
 *
 * <p>The label is a security label on the record, in the system named here,
 * whose code names the {@code Organization} by an identifier it carries,
 * {@code <system>|<value>}. By identifier rather than by id, because a record
 * travels between places and an organisation's id is different on each.
 *
 * <p>Exactly that organisation, not the tree under it and not the one above.
 * Reach widens a grant to an organisation's departments because a grant is
 * about what somebody may do; a held record is about who it was written
 * for, and a note written for one department is not written for its parent.
 *
 * <p>A personality lifts the label into the envelope at {@link #PATH}, so the
 * engine can leave a held record out of a query rather than out of a page
 * after the query: a page with gaps would count what it left out.
 */
public final class HeldTo {

    /** The security label system whose code names the organisation a record is held to. */
    public static final String SYSTEM = "urn:dbo:held-to";

    /** The envelope path the label's code is lifted to. */
    public static final String PATH = "_held";

    /** The code of the trail entry a change of who holds a record leaves. */
    public static final String CODE = "held-to";

    private HeldTo() {
    }
}
