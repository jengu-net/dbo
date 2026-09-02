package cloud.jengu.dbo.core.api;

/**
 * Who this answer is being assembled for.
 *
 * <p>A context seam beside the request, like {@link Caller}, {@link Reach} and
 * {@link Disclosure} — set by the serving surface once the request is
 * understood, read by the layer that decides what an answer contains, cleared
 * when the request ends. A per-request fact rather than a capability, for the
 * reason the face doctrine gives: the store that uses it stays a function
 * taking values.
 *
 * <p><b>Why a store needed this at all.</b> What may leave and what <em>this
 * particular recipient</em> may see are different questions, and only the first
 * had an answer. A type's declared travel says whether it may go into a backup
 * or an export; the coarsening a face supplies reduces a value the same way for
 * everybody. So a tenant sharing with two partners shared the same thing with
 * both, or declared a type unshareable and shared it with neither. The moment
 * there is a second partner who should see something different, the type-level
 * dial has run out.
 *
 * <p><b>Nobody named is the tenant itself.</b> Unset means the request is the
 * tenant working with its own records, which is what nearly every request is,
 * and nothing about it changes. An audience is something a serving surface
 * names deliberately when it knows it is answering somebody else.
 *
 * <p><b>This is not authorisation.</b> Whether a caller may ask at all is
 * settled before this is consulted. What an audience decides is what the answer
 * <em>contains</em> once they may ask — and unlike a purpose, which a caller
 * states about itself, an audience is matched against a declaration the tenant
 * wrote.
 */
public final class Audience {

    private static final ThreadLocal<String> NAMED = new ThreadLocal<>();

    private Audience() {
    }

    /** This request is being answered for the named audience. */
    public static void serving(String audience) {
        if (audience == null || audience.isBlank()) {
            NAMED.remove();
        } else {
            NAMED.set(audience);
        }
    }

    /** Who this answer is for, or null when it is the tenant's own. */
    public static String named() {
        return NAMED.get();
    }

    public static void clear() {
        NAMED.remove();
    }
}
