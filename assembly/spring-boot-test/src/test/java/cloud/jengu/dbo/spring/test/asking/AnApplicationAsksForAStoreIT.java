package cloud.jengu.dbo.spring.test.asking;

import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboServerAutoConfiguration;
import cloud.jengu.dbo.spring.server.DboServerNotAskedFor;
import cloud.jengu.dbo.spring.server.DboServerProperties;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.server.EnableDboServer;
import cloud.jengu.dbo.spring.server.SpringHttpServer;
import cloud.jengu.dbo.spring.worker.DboWorker;
import cloud.jengu.dbo.spring.worker.DboWorkerAutoConfiguration;
import cloud.jengu.dbo.spring.worker.DboWorkerNotAskedFor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The jar on a classpath is not an ask for a store.
 *
 * <p>Every auto-configuration a Spring Boot application would run is here, so
 * what differs between the three contexts is only what the application said.
 */
class AnApplicationAsksForAStoreIT {

    private final WebApplicationContextRunner application = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    DboServerAutoConfiguration.class, DboServerNotAskedFor.class,
                    DboWorkerAutoConfiguration.class, DboWorkerNotAskedFor.class));

    @Test
    @DisplayName("a context that asked for nothing starts with no store, no worker and no "
            + "filter, and needs no dbo property")
    @Proving(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE)
    void nothingAskedIsNothingBooted() {
        application.run(context -> {
            assertNull(context.getStartupFailure(), "a context that asked for no store did not "
                    + "start: " + context.getStartupFailure());
            List<Class<?>> present = List.of(EmbeddedRuntime.class, DboTenants.class,
                            SpringHttpServer.class, FilterRegistrationBean.class, DboWorker.class)
                    .stream().filter(type -> !context.getBeansOfType(type).isEmpty())
                    .<Class<?>>map(type -> type).toList();
            Proves.that(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE, present.isEmpty(),
                    "a context that only shares the classpath was given " + present);
        });
    }

    @Test
    @DisplayName("a context that asked for a store and gave it no key is refused, naming the key")
    @Proving(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE)
    void anAskWithoutAKeyIsRefused() {
        application.withUserConfiguration(AskingByAnnotation.class).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "an application that asked for a store started without an "
                    + "authority, which the serving distribution exits rather than do");
            Proves.that(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE,
                    rootOf(failure).getMessage().contains("dbo.auth.kek"),
                    "the refusal does not name the key: " + rootOf(failure).getMessage());
        });
    }

    @Test
    @DisplayName("properties declared as a bean are the ask: the store is configured on that "
            + "bean, and the environment overrides only the keys it sets")
    @Proving(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE)
    void aBeanOfTheApplicationsOwnIsTheAsk() {
        application.withUserConfiguration(AskingByBean.class)
                // Never started, and on a port of its own rather than this
                // context's: what is in question is whether the bean is the
                // ask, and a store that boots once asked is the node test's
                // claim. A second container booted and closed in this JVM
                // would also be under the one the secured tests boot.
                .withBean(EmbeddedRuntime.class, () -> new EmbeddedRuntime(
                        AnApplicationAsksForAStoreIT.class.getClassLoader(), Map.of(),
                        EmbeddedRuntime.storageUnder(
                                Path.of(System.getProperty("java.io.tmpdir")), "dbo-asking")))
                .withPropertyValues("dbo.node-name=from-the-environment", "dbo.mount=own-port")
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the store was not configured on "
                            + "the application's own properties: " + context.getStartupFailure());
                    DboServerProperties used = context.getBean(DboServerProperties.class);
                    Proves.that(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE,
                            !context.getBeansOfType(DboTenants.class).isEmpty()
                                    && used == context.getBean(AskingByBean.class).declared,
                            "the store was not configured on the bean the application declared");
                    Proves.that(DboPromises.CONT_A_HOST_ASKS_FOR_THE_STORE,
                            "from-the-environment".equals(used.getNodeName())
                                    && "/from/code".equals(used.getTenants().getDirectory()),
                            "the environment did not override key by key: node name "
                                    + used.getNodeName() + ", directory "
                                    + used.getTenants().getDirectory());
                });
    }

    @EnableDboServer
    static class AskingByAnnotation {
    }

    @Configuration
    static class AskingByBean {

        final DboServerProperties declared = new DboServerProperties();

        @Bean
        DboServerProperties dboServerProperties() {
            declared.setNodeName("from-code");
            declared.getTenants().setDirectory("/from/code");
            // No authority, said in code here because a key in a test that
            // looks like a key is worse; an application says it as a property.
            declared.getAuth().setDisabled(true);
            return declared;
        }
    }

    private static Throwable rootOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
