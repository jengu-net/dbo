package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A class's tenant databases, dropped when the class is done with them.
 *
 * <p>Every suite that brings tenants up leaves a database per tenant, and one
 * holding a face is around ninety megabytes. They all used to stay until the
 * JVM exited, so the server's high-water mark was the whole suite's worth at
 * once — a couple of gigabytes — rather than a class's. Nothing leaked, but
 * the runner had to have room for all of it simultaneously, and that number
 * grows with every suite anybody adds.
 *
 * <p><b>Off the test's thread.</b> Dropping is the server's work and the next
 * class has none of it to wait for, so it is handed to a background thread and
 * the class returns. The thread is a daemon: at the end of a run whatever is
 * still queued is dropped by the shutdown hook that has always dropped these,
 * so nothing is left behind either way.
 *
 * <p><b>Through the ordinary path.</b> Each one goes out through the same
 * deprovision the store uses to erase a tenant, rather than a DROP written
 * here — a second way to remove a tenant is a second thing that can be right
 * about a database and wrong about everything else the store keeps beside it.
 */
final class SuiteDatabases {

    /**
     * One thread. Dropping is cheap and serialising it keeps the number of
     * admin connections at one, where several suites finishing together would
     * otherwise each open their own against a server already holding a
     * connection per tenant.
     */
    private static final ExecutorService DROPPING = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dbo-suite-databases");
        thread.setDaemon(true);
        return thread;
    });

    private SuiteDatabases() {
    }

    /**
     * Closes the provisioner and drops what it made, the dropping in the
     * background.
     *
     * <p>The codes are taken before closing, because closing is what forgets
     * them.
     */
    static void retire(LocalDatabasePerTenantProvisioner provisioner) {
        if (provisioner == null) {
            return;
        }
        Set<String> made = provisioner.provisioned();
        provisioner.close();
        if (made.isEmpty()) {
            return;
        }
        DROPPING.submit(() -> {
            for (String code : made) {
                try {
                    provisioner.deprovision(code);
                } catch (RuntimeException couldNotDrop) {
                    // The run's own shutdown drops whatever is left, so this
                    // is worth saying and not worth failing a suite that has
                    // already passed.
                    System.out.println("suite cleanup: " + code + " stayed: " + couldNotDrop);
                }
            }
        });
    }
}
