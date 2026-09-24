package cloud.jengu.dbo.spring.test;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what a test asked for into what the application reads.
 *
 * <p>An initializer rather than a {@code @DynamicPropertySource}, and the
 * reason is ordering: this runs after the environment is prepared — so
 * {@code dbo.test.*} from the application's own yaml is already bound and
 * readable — and before the context refreshes, which is the only window in
 * which a database can be started and the application told where it is.
 *
 * <p><b>Everything it contributes is derived.</b> A test names a world and a
 * tenant; it never names a JDBC url, a key or a port, because those are facts
 * about the deployment this creates rather than about the test. That is also
 * what keeps the applications honest: their own configuration stays the shape
 * an integrator copies, and nothing in them is written for a test.
 */
public final class ADeploymentForThisTest
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** Where this initializer's answers go, ahead of the application's own. */
    private static final String SOURCE = "dbo.test";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        DboTestProperties asked = asked(environment);

        PostgreSQLContainer<?> database = TheDatabaseForThisJvm.get(asked.image());
        Path world = Path.of(asked.world()).toAbsolutePath().normalize();
        if (!Files.isDirectory(world)) {
            throw new IllegalStateException("the world at " + world + " is not a directory, so "
                    + "this test would serve no tenants and answer 404 to everything");
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("dbo.mount", "servlet");
        derived.put("dbo.tenants.directory", world.resolve("tenants").toString());
        derived.put("dbo.management-spec", world.resolve("mom.json").toString());
        derived.put("dbo.admin.jdbc-url", database.getJdbcUrl());
        derived.put("dbo.admin.user", database.getUsername());
        derived.put("dbo.admin.password", database.getPassword());
        // From the database rather than from here: they share a lifetime, and
        // a key minted per context would leave a second context in this JVM
        // unable to read the tenants the first one sealed.
        derived.put("dbo.auth.kek", TheDatabaseForThisJvm.key());

        // THE PORT IS TAKEN HERE, not discovered at refresh. A worker's lane
        // is built when its bean is, so a port known only once the server is
        // listening is known too late for anything to point at it. Taken and
        // released: the window between is small and the alternative is an
        // ordering nobody can follow.
        int port = aFreePort();
        derived.put("server.port", String.valueOf(port));

        String tenant = asked.laneTenant();
        if (tenant != null) {
            derived.put("dbo.worker.lanes[0].tenant", tenant);
            derived.put("dbo.worker.lanes[0].base",
                    "http://127.0.0.1:" + port + "/t/" + tenant + "/");
            // The credential is named now and ISSUED later: only a tenant
            // that exists can register a client, and none does yet.
            derived.put("dbo.worker.lanes[0].token.client-id", TheTenantIsServing.WORK_CLIENT);
            derived.put("dbo.worker.lanes[0].token.client-secret", TheTenantIsServing.SECRET);
            // Left still until it has been. A runner polling a lane whose
            // credential does not exist yet fails every cycle and says so in
            // a log nobody is reading.
            derived.put("dbo.worker.auto-start", "false");
        }

        // THE DECLARATIONS COME FROM THIS TEST, not from the directory. The
        // runtime is told to expect a shared source and then waits for one to
        // be registered, which the context below does. The directory named by
        // dbo.test.world is read ONCE to seed it, and never again.
        derived.put("dbo.framework.dbo.tenant.declarations.shared", "true");
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, derived));

        TheWorldThisTestDeclares declaring = TheWorldThisTestDeclares.seededFrom(
                world.resolve("tenants"));
        if (context instanceof org.springframework.context.support.GenericApplicationContext beans) {
            beans.registerBean(TheWorldThisTestDeclares.class, () -> declaring);
            beans.registerBean(DboTestContext.class, () -> new DboTestContext(
                    declaring, beans.getBean(cloud.jengu.dbo.spring.EmbeddedRuntime.class),
                    beans.getBean(cloud.jengu.dbo.spring.server.DboTenants.class), port, beans));
            return;
        }
        throw new IllegalStateException("this context cannot be given the test's declarations: "
                + context.getClass().getName() + " is not a GenericApplicationContext, and the "
                + "runtime has already been told to wait for a source nobody would register");
    }

    private static DboTestProperties asked(ConfigurableEnvironment environment) {
        return new DboTestProperties(
                environment.getProperty(DboTestProperties.IMAGE),
                environment.getProperty(DboTestProperties.WORLD),
                environment.getProperty(DboTestProperties.LANE_TENANT),
                scopes(environment));
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopes(ConfigurableEnvironment environment) {
        String listed = environment.getProperty(DboTestProperties.LANE_SCOPES);
        if (listed == null || listed.isBlank()) {
            return List.of();
        }
        return List.of(listed.split("\\s*,\\s*"));
    }

    /**
     * A port nothing is listening on, as far as anybody can tell.
     *
     * <p>Taken by opening and closing a socket, which is the only thing a JVM
     * can do about this: between the close and the server's bind, the port is
     * anybody's. It is the same window every test framework lives with, and
     * the alternative — discovering the port after the context is up — puts
     * it out of reach of the beans that need it.
     */
    private static int aFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException noPort) {
            throw new IllegalStateException("no free port could be taken for this test", noPort);
        }
    }

}
