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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing a tenant up and keeping a stream in step shared one thread, so each
 * waited for the other. A tenant catching up with a large dependency delayed
 * every other tenant's bring-up — invisibly, because it is nobody's failure —
 * and a bring-up waiting on somebody else's storage stopped every stream in
 * the deployment while it waited.
 *
 * <p>Held here by a tenant whose provisioning does not return until this test
 * says so: while the scan is stuck inside that bring-up, content written
 * upstream still has to reach the tenant that streams it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AStreamKeepsMovingWhileATenantComesUpIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static Held held;
    static TenantRuntimeManager manager;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    /** How long the held tenant stays in its bring-up. */
    static final java.time.Duration HELD_FOR = java.time.Duration.ofSeconds(120);

    /** How long the stream is given to move while it is held. Far inside it. */
    static final java.time.Duration WAITED_FOR = java.time.Duration.ofSeconds(20);

    /** A provisioner that stops on the tenant this test names, until released. */
    static final class Held implements TenantDatabaseProvisioner {

        private final LocalDatabasePerTenantProvisioner real;
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch reached = new CountDownLatch(1);
        private volatile String holding;

        Held(LocalDatabasePerTenantProvisioner real) {
            this.real = real;
        }

        void hold(String code) {
            this.holding = code;
        }

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            if (spec.code().equals(holding)) {
                reached.countDown();
                try {
                    if (!released.await(HELD_FOR.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
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
        dir = Files.createTempDirectory("dbo-two-loops");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AStreamKeepsMovingWhileATenantComesUpIT"),
                postgres.getUsername(), postgres.getPassword());
        held = new Held(provisioner);
        manager = new TenantRuntimeManager(dir, held, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        held.released.countDown();
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    private static final String TYPES = """
            [{"name":"CodeSystem","identity":"canonical","handling":"operational"}]""";

    private HttpResponse<String> post(String tenant, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(tenant) + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Proving(DboPromises.TEN_COMING_UP_AND_KEEPING_UP_ARE_NOT_ONE_QUEUE)
    void aStreamKeepsMovingWhileATenantIsStuckComingUp() throws Exception {
        Files.writeString(dir.resolve("loops-source.json"),
                "{\"code\":\"loops-source\",\"face\":\"r4\",\"types\":" + TYPES + "}");
        Files.writeString(dir.resolve("loops-reader.json"),
                "{\"code\":\"loops-reader\",\"face\":\"r4\",\"types\":" + TYPES + ","
                        + "\"dependencies\":[{\"name\":\"loops-source\","
                        + "\"types\":[\"CodeSystem\"]}]}");
        UntilServed.scan(manager, "loops-source", "loops-reader");

        // Both loops running, and a third tenant whose provisioning does not
        // return: the scan is now inside a bring-up and stays there.
        manager.start(200);
        held.hold("loops-stuck");
        Files.writeString(dir.resolve("loops-stuck.json"),
                "{\"code\":\"loops-stuck\",\"face\":\"r4\",\"types\":" + TYPES + "}");
        assertTrue(held.reached.await(30, TimeUnit.SECONDS),
                "the scan never reached the tenant this test holds");

        // Written while the scan is stuck. Sharing one thread, nothing would
        // carry it: the sync round sits behind the bring-up that is waiting.
        assertEquals(201, post("loops-source", "/CodeSystem", """
                {"resourceType":"CodeSystem","url":"https://loops.test/cs/one",
                 "status":"active","content":"complete","concept":[{"code":"kept-moving"}]}""")
                .statusCode());

        // Strictly inside the hold, and that is the whole assertion: sharing
        // one thread, nothing can arrive until the held bring-up gives up, so
        // a wait that outlived the hold would pass either way — which is what
        // the first version of this test did.
        long deadline = System.currentTimeMillis() + WAITED_FOR.toMillis();
        boolean arrived = false;
        while (!arrived && System.currentTimeMillis() < deadline) {
            // The count, not the body: a search echoes its own query in the
            // bundle's self link, so asking whether the answer mentions the
            // url is a question that says yes when the answer is empty.
            arrived = HTTP.send(HttpRequest.newBuilder(URI.create(
                                    manager.baseUrl("loops-reader")
                                            + "/CodeSystem?url=https://loops.test/cs/one"
                                            + "&_summary=count"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body().contains("\"total\":1");
            if (!arrived) {
                Thread.sleep(200);
            }
        }
        assertTrue(arrived,
                "the stream stopped because a tenant somewhere else was still coming up");
        assertTrue(held.reached.getCount() == 0 && !manager.codes().contains("loops-stuck"),
                "the held tenant came up anyway, so this proved nothing");

        held.released.countDown();
    }
}
