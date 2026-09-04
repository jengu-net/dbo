package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantDatabaseProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer declaring several tenants at once gets several tenants.
 *
 * <p>Bring-up used to run inline, one tenant after another, on the thread that
 * also reconciles every tenant already up. Each one costs somebody else's
 * waiting — a database, a schema, a secret that has not landed — so the queue
 * was as long as the deployment was big, and a consumer that waited a fixed
 * time per tenant got the first few and a refusal for the rest. The refusal
 * was indistinguishable from "this tenant cannot be provisioned", which is
 * what made it expensive.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeveralTenantsDeclaredAtOnceComeUpTogetherIT {

    // Enough to prove they overlap, and no more: this runs inside a suite
    // that is already four classes deep, and a bring-up holds a validator.
    // Eight of them at four met OutOfMemoryError here, which is the same
    // hazard the bound itself exists for.
    private static final int DECLARED = 4;
    private static final int AT_ONCE = 2;

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static Meeting meeting;
    static TenantRuntimeManager manager;

    /**
     * A provisioner that cannot finish alone: each bring-up waits for another
     * one to arrive. Serially the first waits for somebody who is queued
     * behind it and the barrier times out — which is the assertion, made
     * without a stopwatch.
     */
    static final class Meeting implements TenantDatabaseProvisioner {

        private final LocalDatabasePerTenantProvisioner real;
        private final CyclicBarrier pairsUp = new CyclicBarrier(2);
        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger mostAtOnce = new AtomicInteger();

        Meeting(LocalDatabasePerTenantProvisioner real) {
            this.real = real;
        }

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            int now = inside.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            try {
                pairsUp.await(30, TimeUnit.SECONDS);
            } catch (TimeoutException aloneInHere) {
                throw new IllegalStateException("nothing else was being brought up: this "
                        + "tenant waited alone, so bring-up is still a queue", aloneInHere);
            } catch (InterruptedException | BrokenBarrierException e) {
                throw new IllegalStateException(e);
            } finally {
                inside.decrementAndGet();
            }
            return real.provision(spec);
        }

        @Override
        public void deprovision(String tenantCode) {
            real.deprovision(tenantCode);
        }

        @Override
        public void release(String tenantCode) {
            real.release(tenantCode);
        }
    }

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-at-once");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("SeveralTenantsDeclaredAtOnceComeUpTogetherIT"),
                postgres.getUsername(), postgres.getPassword());
        meeting = new Meeting(provisioner);
        manager = new TenantRuntimeManager(dir, meeting, "127.0.0.1", 0, null);
        manager.broughtUpTogether(AT_ONCE);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @Proving(DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER)
    void tenantsDeclaredAtOnceAllComeUp() throws Exception {
        for (int clinic = 0; clinic < DECLARED; clinic++) {
            Files.writeString(dir.resolve("at-once-" + clinic + ".json"), """
                    {"code":"at-once-%d","face":"r4","types":[
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(clinic));
        }

        Set<String> serving = manager.scanOnce();

        assertEquals(DECLARED, serving.size(),
                "a consumer declaring " + DECLARED + " tenants got " + serving.size()
                        + "; troubles=" + manager.troubles());
        assertTrue(meeting.mostAtOnce.get() > 1,
                "every bring-up had the node to itself, which is the queue this removes");
        assertTrue(meeting.mostAtOnce.get() <= AT_ONCE,
                "a node brought up " + meeting.mostAtOnce.get() + " tenants at once, past the "
                        + AT_ONCE + " it was told it could carry");
    }
}
