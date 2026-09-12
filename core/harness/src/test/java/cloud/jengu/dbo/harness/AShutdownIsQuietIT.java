package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A shutdown that was asked for reads like one.
 *
 * <p>Stopping a node ended in a burst of stack traces about connection pools
 * that had just been closed on purpose — thirty-eight in one tier run,
 * one per item per tenant. Nothing failed. The cost was triage: a teardown
 * that logs like a crash is where a real crash goes to hide, and three
 * unrelated failures were attributed to it before anybody checked.
 *
 * <p>The cause was ordering. Ending the loops meant interrupting them, which
 * ends the sleep between rounds and does nothing to a round already reading
 * through a pool — and the pools were closed immediately afterwards.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AShutdownIsQuietIT {

    private static final String ZONE = "vaikne-zone";
    private static final String EDGE = "vaikne-edge";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-quiet");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AShutdownIsQuietIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // A dependent tenant, so the reconciler has streams to carry and a
        // round is doing real work when the close arrives.
        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(ZONE));
        Files.writeString(dir.resolve(EDGE + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","types":["CodeSystem"]}],
                 "types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(EDGE, ZONE));
        UntilServed.scan(manager, ZONE, EDGE);
    }

    @AfterAll
    void down() {
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("closing a node that is syncing says nothing about the pools it closed")
    void nothingIsSaidAboutPoolsClosedOnPurpose() throws Exception {
        // Enough for the edge to still be carrying when the close arrives: a
        // round over an empty feed is over before anything can interrupt it,
        // and a test whose window does not exist proves nothing.
        manager.authority(ZONE).ensureClient("loader", "loader-secret",
                java.util.List.of("system/*.read", "system/*.write"));
        String zoneBase = "http://127.0.0.1:" + manager.port() + "/t/" + ZONE;
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String token = http.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(zoneBase + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=loader&client_secret="
                                        + "loader-secret")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        for (int i = 0; i < 150; i++) {
            http.send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(zoneBase + "/fhir/CodeSystem"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/fhir+json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("""
                                    {"resourceType":"CodeSystem",
                                     "url":"https://vaikne.example/cs/%d","status":"active",
                                     "content":"complete",
                                     "concept":[{"code":"a"},{"code":"b"},{"code":"c"}]}"""
                                    .formatted(i))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        }

        // Rounds running against live pools, so a close lands mid-flight.
        manager.start(50);
        Thread.sleep(300);

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(said, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            manager.close();
            // whatever a straggler would have said, it has had its chance
            Thread.sleep(1500);
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }

        // Only what this node said about ITS OWN tenants. System.out is the
        // whole JVM's, and a suite has other classes logging into the same
        // stream — an assertion over all of it fails on somebody else's
        // perfectly ordinary line, which is a test that cries wolf about the
        // very thing it exists to keep quiet.
        String shutdown = said.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains(ZONE) || line.contains(EDGE))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertFalse(shutdown.contains("has been closed"),
                "a shutdown said this about a pool it closed on purpose, which is the "
                        + "noise a real failure has to be found in:\n" + shutdown);
        assertFalse(shutdown.contains("marked as broken") || shutdown.contains("PSQLException"),
                "a round was still reading through a connection when the node was taken "
                        + "down, so stopping it broke the connection under it:\n" + shutdown);
    }
}
