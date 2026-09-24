package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.sync.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A deployment reads what it serves from where it was told, not from a disk.
 *
 * <p>A declaration was a file in a watched directory and nothing else. That
 * is one source among several, and the interface that reads them already
 * names the others it expected: a git repository, a mounted ConfigMap, a
 * directory, a lane from a cloud. A host that embedded this framework is the
 * next one — an application whose tenants are declared where the rest of its
 * configuration is, read the same way by every replica, rather than as files
 * on one node's disk that another replica cannot see.
 *
 * <p>No database here. Everything asserted happens before a tenant is
 * provisioned: which source a pass reads, and what a pass does when the
 * source cannot be read. A world with a store in it would be answering a
 * different question, slower.
 */
class ADeploymentReadsItsDeclarationsFromWhereItWasToldTest {

    private TenantRuntimeManager manager;

    @AfterEach
    void down() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("a pass reads the source the deployment was given and never opens the watched "
            + "directory, so a tenant declared only on disk is not one this node serves")
    void aSuppliedSourceIsReadInsteadOfTheDirectory(@TempDir Path onDisk) throws Exception {
        // A tenant nobody asked for, in the place the directory source looks.
        // If anything reads it, this node takes it up and leaves its reason
        // in the trouble ledger — which is what the second assertion reads.
        Files.writeString(onDisk.resolve("from-the-disk.json"),
                "{\"code\":\"fromthedisk\",\"face\":\"r4\",\"types\":[]}");

        Declaring source = new Declaring();
        manager = new TenantRuntimeManager(onDisk, new AsksForNothing(), "127.0.0.1", 0, null)
                .declaredFrom(source);

        manager.scanOnce();

        assertEquals(1, source.reads.get(),
                "the pass did not read the source it was given, so saying where the "
                        + "declarations are has no effect on what is read");
        // The consequence, said where a deployment would feel it. A tenant
        // the pass takes up and cannot bring up leaves its reason in the
        // trouble ledger, so a ledger naming the one on disk is this node
        // having read the disk — whatever the count above says.
        assertEquals(Map.of(), manager.troubles(),
                "the pass took up " + manager.troubles().keySet() + ", which is declared on "
                        + "disk and nowhere in the source this deployment was told to read — "
                        + "so the directory is still the source, and a replica would serve "
                        + "whatever happened to be on its own volume");
    }

    @Test
    @DisplayName("a source that cannot be read stops the pass rather than being read as a "
            + "deployment that declares nobody, because that reading retracts every tenant")
    void anUnreadableSourceIsNotAnEmptyOne(@TempDir Path onDisk) {
        manager = new TenantRuntimeManager(onDisk, new AsksForNothing(), "127.0.0.1", 0, null)
                .declaredFrom(new CannotBeRead());

        // The pass fails, and that is the whole of the claim. The scan loop
        // above catches it and keeps scanning — a deployment keeps serving
        // what it already serves when its own bookkeeping cannot be read.
        // What it must never do is carry on INTO the sweep, because the sweep
        // retracts whatever the pass did not name, and a pass that named
        // nobody because it could not read retracts everybody.
        assertThrows(IllegalStateException.class, () -> manager.scanOnce(),
                "a source that could not be read was taken for a deployment that declares "
                        + "nobody, which is how a bad read becomes a withdrawal of every "
                        + "tenant on this node");
    }

    /** A source with one thing to say: nothing is declared here. */
    private static final class Declaring implements ConfigSource {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public Fetch fetch() {
            reads.incrementAndGet();
            return new Fetch(List.of(), "a-marker", true);
        }
    }

    /** The source the contract is really about: one that is not answering. */
    private static final class CannotBeRead implements ConfigSource {

        @Override
        public Fetch fetch() {
            throw new IllegalStateException("the declarations are kept somewhere this node "
                    + "cannot reach right now");
        }
    }

    /**
     * A provisioner that says who asked.
     *
     * <p>It never returns a database, because nothing in this test should
     * reach the point of wanting one — and a fake database that answered
     * would carry the test on into a bring-up nobody is asking about.
     */
    private static final class AsksForNothing implements TenantDatabaseProvisioner {

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            throw new UnsupportedOperationException(
                    "nothing in this test declares a tenant, and " + spec.code() + " was asked "
                            + "for");
        }

        @Override
        public void deprovision(String tenantCode) {
        }
    }

}
