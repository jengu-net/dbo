package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One dependency, and the application is a node.
 *
 * <p>What is asserted is the wiring an application gets for free: the
 * container comes up with the serving bundle set, the surfaces have somewhere
 * to mount, the runtime is told to wait for it, and a deployment with no
 * authority is refused the way the serving distribution refuses it.
 *
 * <p><b>No tenant comes up here</b>, and none is meant to. A tenant needs a
 * database, a spec and a bring-up, and what those would prove — that a read
 * of a tenant's records is answered on this application's port — is the
 * claim that ties the whole chain together and is made where all three are
 * available. This is the chain up to that point.
 */
class AddingThisJarMakesTheApplicationANodeIT {

    private final WebApplicationContextRunner application = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DboServerAutoConfiguration.class))
            // No authority, said out loud, because no tenant comes up here and
            // the alternative is a key in a test that looks like a key.
            .withPropertyValues("dbo.auth.disabled=true");

    @Test
    @DisplayName("the serving bundle set comes up, the surfaces have somewhere to mount, and "
            + "the filter that offers requests to them is in the application's chain")
    void theApplicationGetsAContainerAndSomewhereToServe() {
        application.run(context -> {
            assertNull(context.getStartupFailure(),
                    "adding this jar did not start: " + context.getStartupFailure());

            EmbeddedRuntime runtime = context.getBean(EmbeddedRuntime.class);
            assertTrue(runtime.running(), "the container is not running, so this application "
                    + "has the store's jars and none of its behaviour");
            Map<String, String> states = runtime.states();
            assertTrue(states.size() > 20,
                    "the serving set is " + states.size() + " bundles, which is not the set the "
                            + "serving distribution installs");
            states.forEach((name, state) -> assertTrue(
                    state.equals("running") || state.equals("attached"),
                    name + " is '" + state + "', so the container resolved it and could not "
                            + "run it"));

            assertNotNull(context.getBean(SpringHttpServer.class),
                    "there is nowhere for a tenant to mount its doors");
            assertNotNull(context.getBean(FilterRegistrationBean.class),
                    "no filter offers requests to the surfaces, so every tenant path would be "
                            + "routed by the application and answered 404");
        });
    }

    @Test
    @DisplayName("a deployment with no authority is refused, in the same sentence the serving "
            + "distribution refuses it — one rule, not two artefacts with different ones")
    void aDeploymentWithoutAnAuthorityIsRefused() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DboServerAutoConfiguration.class))
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "this application started serving tenants with no authority, which "
                                    + "the serving distribution exits rather than do");
                    assertTrue(rootOf(context.getStartupFailure()).getMessage()
                                    .contains("dbo.auth.kek"),
                            "the refusal does not say how to fix it: "
                                    + rootOf(context.getStartupFailure()).getMessage());
                });
    }

    @Test
    @DisplayName("mounting in the application's web tier tells the runtime to wait for a "
            + "server rather than bind a port of its own")
    void theRuntimeIsToldToWaitForTheApplicationsServer() {
        DboServerProperties properties = new DboServerProperties();
        properties.getAuth().setKek(Base64.getEncoder().encodeToString(new byte[32]));

        Map<String, String> framework = properties.asFrameworkProperties();

        assertEquals("true", framework.get("dbo.tenant.http.shared"),
                "the runtime was not told a host holds the web tier, so it binds a port of its "
                        + "own and every surface answers somewhere the application is not");

        properties.setMount(DboServerProperties.Mount.OWN_PORT);
        assertTrue(!properties.asFrameworkProperties().containsKey("dbo.tenant.http.shared"),
                "a deployment that asked for a listener of its own was told to wait for one it "
                        + "will never be given, so it would come up serving nothing");
    }

    @Test
    @DisplayName("a value nobody set is left out rather than passed as empty, because the "
            + "activators test for absence and an empty authority would be an authority")
    void whatWasNotSaidIsNotPassedOn() {
        Map<String, String> framework = new DboServerProperties().asFrameworkProperties();

        assertTrue(!framework.containsKey("dbo.tenant.auth.kek"),
                "an authority key nobody set was passed as " + framework.get("dbo.tenant.auth.kek")
                        + ", and the runtime tests for null");
        assertTrue(!framework.containsKey("dbo.tenant.dir"),
                "a spec directory nobody set was passed anyway");
    }

    private static Throwable rootOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
