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
    static final int PASSES = 20;

    /**
     * And how long to keep going while the runtime says a tenant is still on
     * its way.
     *
     * <p>Passes are not a wait. A pass is one reconciliation, and what it
     * costs depends on how many tenants the runtime is carrying and what else
     * the machine is doing — so twenty of them is a generous wait on an idle
     * laptop and a short one on a CI runner bringing up two dozen tenants at
     * once. The projection a tenant a release behind needs takes about half a
     * minute to come up on its own, and everything declaring that zone waits
     * behind it.
     *
     * <p>So the count is a floor and this is the ceiling, and what decides
     * between them is the runtime's own ledger: a tenant with nothing
     * recorded against it has not failed, it has not finished, and the
     * promise is that the wait ends rather than that a pass ends it. A
     * tenant whose trouble IS recorded fails immediately, however much time
     * is left — that failure is the whole reason this class exists and
     * waiting on it would only make it arrive later.
     */
    private static final java.time.Duration WHILE_COMING_UP = java.time.Duration.ofMinutes(4);

    /**
     * What each wait actually took, so the ceiling above can be judged rather
     * than argued about.
     *
     * <p>The loop below leaves only when BOTH the floor and the ceiling are
     * spent, so the four minutes keep a tenant alive past where the old
     * twenty-pass rule stopped in exactly one case: a wait that needed more
     * than {@link #PASSES} passes. Whether that ever happens is a count, and
     * a count is cheaper and more honest than running the suite twice and
     * comparing two peaks that differ for a dozen other reasons.
     *
     * <p>And if one does exceed the floor, the finding is not that a peak
     * rose. Under the old rule that class went RED; it did not hold memory.
     * What the change traded is a failure for an occupancy, and the count
     * says which.
     *
     * <p><b>Only waits that ended in service are recorded</b>, which bounds
     * what the count can say. A wait that never gets what it asked for throws
     * from below and adds nothing here — and that is the wait the ceiling was
     * lengthened for. So a run where everything came up says the ceiling was
     * never reached; it cannot say what the ceiling costs on a run where
     * something does not arrive, because on such a run this file is the
     * evidence that is missing rather than the evidence that is taken.
     */
    record Waited(String codes, int passes, long millis) {}

    /** Every wait this JVM has finished, in the order they finished. */
    static final java.util.List<Waited> WAITS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
     * unguarded here — that half is covered by
     * {@link ATenantCodeBelongsToOneClassTest}, which reads the sources and so
     * needs neither this door nor a full run. And it holds one map in one JVM: the suite runs as a single
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

    /**
     * Scans until every code named is being served.
     *
     * <p><b>Names the tenants that did not make it, and what the runtime
     * recorded about them.</b> A bring-up that throws is caught into the
     * manager's ledger and the tenant simply never serves — which at the
     * surface is indistinguishable from a tenant nobody declared. A test that
     * carried on therefore met a 404 and said whatever its next assertion was
     * about, and the one thing it could not say was that a bring-up had
     * failed. That reading is the failure this exists to prevent: the
     * assertion that follows a bare pass blames the store for refusing a
     * write it never saw.
     */
    static Set<String> scan(TenantRuntimeManager manager, String... codes) {
        for (String code : codes) {
            classUnique(code);
        }
        Set<String> wanted = Set.of(codes);
        Set<String> up = Set.of();
        long began = System.currentTimeMillis();
        long deadline = began + WHILE_COMING_UP.toMillis();
        for (int pass = 0; ; pass++) {
            up = manager.scanOnce();
            if (up.containsAll(wanted)) {
                WAITS.add(new Waited(String.join(",", new java.util.TreeSet<>(wanted)),
                        pass + 1, System.currentTimeMillis() - began));
                return up;
            }
            // The runtime's own word on it, not the trouble ledger. A tenant
            // waiting for its upstream has a trouble recorded — "not up yet,
            // waiting for the next scan" — and is COMING_UP, which is the
            // ordinary way a chain of dependents resolves. Reading the ledger
            // as failure fails every dependent that was merely early.
            Set<String> failed = new java.util.TreeSet<>();
            for (cloud.jengu.dbo.tenant.TenantState state : manager.tenantStates()) {
                if (wanted.contains(state.code())
                        && state.state() == cloud.jengu.dbo.tenant.TenantState.State.FAILED) {
                    failed.add(state.code());
                }
            }
            if (!failed.isEmpty()) {
                throw new AssertionError("bring-up FAILED for: " + failed + " — so anything "
                        + "asked of them answers 404, which is also what a tenant nobody "
                        + "declared answers. What the runtime recorded about it: "
                        + manager.troubles() + "; serving=" + up);
            }
            if (pass + 1 >= PASSES && System.currentTimeMillis() >= deadline) {
                break;
            }
        }
        Set<String> missing = new java.util.TreeSet<>(wanted);
        missing.removeAll(up);
        throw new AssertionError("declared and still not serving after " + PASSES
                + " passes and " + WHILE_COMING_UP.toMinutes() + " minutes: " + missing
                + " — and the runtime recorded no trouble for them, so they were coming up "
                + "the whole time and did not arrive. Nothing here is a failed bring-up; "
                + "something is slower than this is willing to wait. troubles="
                + manager.troubles() + "; serving=" + up);
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
