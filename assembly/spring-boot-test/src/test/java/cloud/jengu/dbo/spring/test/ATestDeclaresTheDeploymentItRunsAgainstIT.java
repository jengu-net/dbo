package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.spring.server.DboTenants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenants come from the test, and the runtime waited for them.
 *
 * <p>Two claims in one arrangement, and the second is why the first can be
 * believed. Told to expect a shared source, the tenant activator does not
 * start until one is registered — so a deployment that serves anything at all
 * has been given one. And what it serves is a tenant that exists ONLY in this
 * test's memory: no file on any disk declares {@code katsetenant}, so a
 * directory read could not have produced it.
 *
 * <p><b>The quiet failure this is about.</b> A source that is never registered
 * does not fail; the activator waits, the application starts perfectly, and the
 * deployment serves nobody. Nothing in a log says a bean was missing. So the
 * assertion is not that a bean exists but that a tenant nobody wrote down came
 * up, which cannot be true unless the whole chain worked.
 */
@DboSpringBootTest
@SpringBootTest(classes = cloud.jengu.dbo.samples.server.ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ATestDeclaresTheDeploymentItRunsAgainstIT {

    private static final String DECLARED_HERE = "katsetenant";

    @Autowired
    DboTestContext context;

    @Autowired
    DboTenants tenants;

    @Test
    @DisplayName("a tenant declared in this test's memory comes up, and one it stops declaring "
            + "is withdrawn — with no file written anywhere")
    void whatTheTestDeclaresIsWhatTheDeploymentServes() throws Exception {
        context.world().declare(DECLARED_HERE, """
                {"code":"%s","face":"r5","audit":{"level":"none"},
                 "types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(DECLARED_HERE));

        assertTrue(untilServing(DECLARED_HERE, true),
                "a tenant this test declared never came up. Either the source was not "
                        + "registered — in which case the runtime is still waiting for one and "
                        + "says nothing about it — or it is being read and this tenant is not "
                        + "in it: " + tenants.serving());

        // And withdrawing is the same act in reverse: a deployment withdraws a
        // tenant by no longer declaring it, which is what makes `complete` on
        // the fetch load-bearing rather than decorative.
        assertTrue(context.retract(DECLARED_HERE),
                "the test retracted a tenant it had not declared, so this assertion is about "
                        + "nothing");
        assertTrue(untilServing(DECLARED_HERE, false),
                "a tenant this test stopped declaring is still being served: " + tenants.serving());
    }

    /** Waits for a tenant to be serving, or to have stopped. */
    private boolean untilServing(String code, boolean expected) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < giveUp) {
            if (tenants.serving().contains(code) == expected) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
