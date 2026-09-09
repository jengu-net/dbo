package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.TenantRuntimeManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Scans until the tenants a test needs are being served.
 *
 * <p>A scan walks the spec directory in whatever order the filesystem returns
 * and brings up what it can, so a dependent met before its upstream waits for
 * the next pass — the runtime promises that the wait ends, not that one pass
 * ends it. A test that scans once and asserts on the result is therefore
 * asserting something stronger than the promise, and passes or fails on an
 * ordering nobody chose.
 *
 * <p>This is not a sleep with extra steps: it waits for the condition the test
 * actually needs, and gives up loudly rather than hanging.
 */
final class UntilServed {

    /** Enough passes that a chain of dependents resolves; short of forever. */
    private static final int PASSES = 20;

    /**
     * Which class brought up which tenant, for this JVM.
     *
     * <p>The Postgres container is shared by the whole suite and a tenant's
     * database name is derived from its code, so two classes naming one tenant
     * address one database. Whichever provisions first stores its bootstrap
     * secret; the second finds the database already there, holds a different
     * secret, and is refused as {@code invalid_client} — a failure that names
     * neither class, appears only in a full run, and passes when either class
     * is run alone.
     *
     * <p>{@code SharedPostgres} states the rule in its own documentation and it
     * was broken anyway, which is what a convention with nothing enforcing it
     * eventually is. This is the enforcement, at the one door every class goes
     * through.
     *
     * <p>Two things it does not do, written down because a check nobody can
     * see the edges of is one somebody eventually deletes. It sees only
     * classes that come through here, so a class provisioning by hand is
     * unguarded — the failure mode is a collision going unnoticed, never a
     * false alarm. And it holds one map in one JVM: the suite runs as a single
     * fork today, and turning on a fork per class would leave this quietly
     * true and quietly useless.
     */
    private static final Map<String, String> BROUGHT_UP = new ConcurrentHashMap<>();

    private UntilServed() {
    }

    /**
     * Scans until {@code served} holds, and answers what is being served.
     *
     * <p>Exhaustion FAILS, naming the trouble ledger: a bring-up
     * failure is caught into the manager's ledger and the tenant simply
     * never serves, so a test that carried on met a bare 404 three steps
     * and eighty polled seconds away from the cause. The cause belongs
     * here, at first contact.
     */
    static Set<String> scan(TenantRuntimeManager manager, Predicate<Set<String>> served) {
        Set<String> up = Set.of();
        for (int pass = 0; pass < PASSES; pass++) {
            up = manager.scanOnce();
            if (served.test(up)) {
                return up;
            }
        }
        throw new AssertionError("not serving what the test needs after " + PASSES
                + " passes; serving=" + up + " troubles=" + manager.troubles());
    }

    /** Scans until every code named is being served. */
    static Set<String> scan(TenantRuntimeManager manager, String... codes) {
        for (String code : codes) {
            classUnique(code);
        }
        return scan(manager, up -> up.containsAll(Set.of(codes)));
    }

    /** Refuses a tenant code a different class already brought up. */
    private static void classUnique(String code) {
        String here = StackWalker.getInstance()
                .walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                        .filter(name -> name.endsWith("IT") || name.endsWith("Test"))
                        .findFirst()
                        .orElse("unknown"));
        String first = BROUGHT_UP.putIfAbsent(code, here);
        if (first != null && !first.equals(here)) {
            throw new AssertionError("tenant '" + code + "' is brought up by two classes — "
                    + first + " and " + here + ". The container is shared and a tenant's "
                    + "database name comes from its code, so both address one database: "
                    + "whichever runs first stores its bootstrap secret and the other is "
                    + "refused as invalid_client, in a full run only. Give one of them a "
                    + "code of its own.");
        }
    }
}
