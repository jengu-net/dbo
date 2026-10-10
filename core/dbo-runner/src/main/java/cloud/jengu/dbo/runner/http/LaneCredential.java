package cloud.jengu.dbo.runner.http;

import java.util.function.Supplier;

/**
 * A lane's credential that can be told the tenant stopped taking its token.
 *
 * <p>A credential that keeps its token until near its expiry otherwise offers
 * one the tenant no longer takes — its authority restarted with new keys, the
 * client was removed — on every call until it expires by itself, and every
 * verb fails meanwhile. A plain {@link Supplier} is never told, and needs not
 * be: it has nothing kept to drop.
 *
 * <p>The refused token is named because calls run beside each other: a call
 * refused an old token must not drop the one another call has just signed in
 * for.
 */
public interface LaneCredential extends Supplier<String> {

    /** The tenant answered 401 to this token. */
    void refused(String token);
}
