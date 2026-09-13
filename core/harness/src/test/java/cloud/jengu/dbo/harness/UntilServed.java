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

    /**
     * Long enough that a chain of dependents resolves; short of forever.
     *
     * <p>A deadline rather than a count of passes. A pass costs whatever the
     * machine is doing at the time — a tenant reading a face through the
     * chain takes fifteen seconds on a quiet laptop and longer when three
     * other classes are doing the same — so twenty passes is a different
     * amount of patience on every run, and the runs where it is least are
     * the ones with the most going on.
     */
    private static final java.time.Duration PATIENCE = java.time.Duration.ofSeconds(240);

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
        long began = System.nanoTime();
        long deadline = began + PATIENCE.toNanos();
        Set<String> up = Set.of();
        int passes = 0;
        do {
            up = manager.scanOnce();
            passes++;
            if (served.test(up)) {
                return up;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("not serving what the test needs after " + passes
                + " passes in " + java.time.Duration.ofNanos(System.nanoTime() - began).toSeconds()
                + "s; serving=" + up + " troubles=" + manager.troubles());
    }

    /** Scans until every code named is being served. */
    static Set<String> scan(TenantRuntimeManager manager, String... codes) {
        for (String code : codes) {
            classUnique(code);
        }
        return scan(manager, up -> up.containsAll(Set.of(codes)));
    }

    /** Refuses a tenant code a different class already brought up. */
    /**
     * Who took this code first, or null for whoever is taking it now.
     *
     * <p>Written down where every fork can see it, not only where this one
     * can. The suite may run as several JVMs, and a map in one of them is a
     * guard that holds for the classes that happen to share a fork and says
     * nothing about the pair that does not — which is the pair a collision
     * is most likely to be, and the one nobody would find.
     *
     * <p>The file is created atomically, so the loser of a race reads the
     * winner's name rather than overwriting it. Without a directory to write
     * in this falls back to the map, which is what a store built by hand
     * outside the build gets.
     */
    private static String claimed(String code, String here) {
        String directory = System.getProperty("dbo.tenant.claims");
        if (directory == null) {
            return BROUGHT_UP.putIfAbsent(code, here);
        }
        java.nio.file.Path claim = java.nio.file.Path.of(directory, code + ".claim");
        try {
            java.nio.file.Files.createDirectories(claim.getParent());
            java.nio.file.Files.writeString(claim, here,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            return null;
        } catch (java.nio.file.FileAlreadyExistsException taken) {
            try {
                return java.nio.file.Files.readString(claim);
            } catch (java.io.IOException unreadable) {
                return null;
            }
        } catch (java.io.IOException cannotWrite) {
            return BROUGHT_UP.putIfAbsent(code, here);
        }
    }

    private static void classUnique(String code) {
        String here = StackWalker.getInstance()
                .walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                        .filter(name -> name.endsWith("IT") || name.endsWith("Test"))
                        .findFirst()
                        .orElse("unknown"));
        String first = claimed(code, here);
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
