package cloud.jengu.dbo.core.api;

import java.util.Set;

/**
 * The per-request organisational reach (#126): which organisations' records
 * this request may touch, or unbounded when the caller's grants are
 * tenant-wide.
 *
 * <p>A context seam beside the request, like {@link Caller} and
 * {@link Disclosure} — set by the serving surface after token validation,
 * consulted by the policy layer, cleared when the request ends. A per-request
 * fact rather than a capability, for the reason the face doctrine gives: the
 * store that uses it stays a function taking values.
 *
 * <p>The ids are the tenant's own {@code Organization} resource ids, already
 * expanded through the hierarchy at token mint — a grant at a parent covers
 * its departments, and the walk happens once where the tree is at hand rather
 * than on every read.
 */
public final class Reach {

    private static final ThreadLocal<Set<String>> ORGANISATIONS = new ThreadLocal<>();

    private Reach() {
    }

    /** Bound this request to the given organisations' records. */
    public static void bind(Set<String> organisationIds) {
        ORGANISATIONS.set(Set.copyOf(organisationIds));
    }

    /** The organisations this request may reach, or null when unbounded. */
    public static Set<String> organisations() {
        return ORGANISATIONS.get();
    }

    public static void clear() {
        ORGANISATIONS.remove();
    }
}
