package cloud.jengu.dbo.spring.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every durable layer the store runs inside an application listens on its
 * database, the scheduler of a step on a clock among them.
 *
 * <p>Each layer unwraps the connection it listens on to the driver's own
 * interface as its bundle resolves it. A layer handed connections that another
 * bundle's copy of the driver opened cannot, and retries once a second for as
 * long as the application runs — logging each time, waking by polling, and
 * nothing else failing. So the claim is counted the only way the failure
 * shows: the connections listening on the tenant's database.
 *
 * <p>A world of its own because a step on a clock opens the tenant's system
 * run, and a tenant shared with other claims would then hold work they say it
 * does not.
 */
// On a port like every application in this module: the first embedded web
// server in a JVM claims its URL handlers, and a container started before it
// has already claimed them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AClockListensOnItsTenantsDatabaseIT {

    private static final String TENANT = "clocktenant";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    DboTenants tenants;

    @DynamicPropertySource
    static void theDeployment(DynamicPropertyRegistry registry) throws IOException {
        POSTGRES.start();
        Path specs = Files.createTempDirectory("dbo-spring-clock");
        Files.writeString(specs.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}],
                 "steps":[
                  {"code":"clock.rounds.walk",
                   "starts":{"every":"0 0 * * * *","missed":"skip","keep":"P1D","where":"origin"}}]}"""
                .formatted(TENANT));

        registry.add("dbo.tenants.directory", specs::toString);
        registry.add("dbo.admin.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("dbo.admin.user", POSTGRES::getUsername);
        registry.add("dbo.admin.password", POSTGRES::getPassword);
        registry.add("dbo.auth.kek",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    @DisplayName("every durable layer the store runs in this application holds a LISTEN on its "
            + "database, the scheduler of a step on a clock among them")
    void everyDurableLayerListensOnItsDatabase() throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (!tenants.isServing(TENANT) && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        assertTrue(tenants.isServing(TENANT),
                "the tenant never came up inside this application: " + tenants.serving());

        // Two layers keep state in the tenant's database and listen there: its
        // subscriptions, and the scheduler its clock opened.
        deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        int listening;
        do {
            Thread.sleep(500);
            listening = listening();
        } while (listening < 2 && System.nanoTime() < deadline);
        assertEquals(2, listening,
                "a durable layer in the tenant's database is listening on nothing: its "
                        + "connections came from another copy of the driver than the one it "
                        + "unwraps to, so it retries every second for the life of the "
                        + "application. The JVM's listeners: " + where());
    }

    /** Where each listener is, for a failure to say which one is not listening. */
    private static String where() {
        StringBuilder found = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (thread.getName().equals("NotificationListener")) {
                found.append("\n  ").append(thread.getState());
                for (int i = 0; i < Math.min(frames.length, 6); i++) {
                    found.append("\n    at ").append(frames[i]);
                }
            }
        });
        return found.toString();
    }

    private static int listening() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet rows = c.createStatement().executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE query ILIKE 'LISTEN %' "
                             + "AND datname = 'tenant_" + TENANT + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @SpringBootApplication
    @EnableDboServer
    static class TheApplication {
    }
}
