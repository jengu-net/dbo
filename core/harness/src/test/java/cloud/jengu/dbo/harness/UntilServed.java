package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.TenantRuntimeManager;

import java.util.Set;
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

    private UntilServed() {
    }

    /**
     * Scans until {@code served} holds, and answers what is being served.
     *
     * <p>Exhaustion FAILS, naming the trouble ledger (#144): a bring-up
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
        return scan(manager, up -> up.containsAll(Set.of(codes)));
    }
}
