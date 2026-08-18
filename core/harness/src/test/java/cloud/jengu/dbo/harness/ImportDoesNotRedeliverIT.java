package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r4.R4Subscriptions;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.subscriptions.RestHookTransport;
import cloud.jengu.dbo.subscriptions.SubscriptionEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restoring a tenant must not re-enact its past.
 *
 * <p>A restore writes every object the archive carries. Every write
 * enqueues an outbox event, and a subscription reading that outbox cannot
 * tell a restored object from a newly created one — so a hospital's
 * downstream systems receive the entire tenant as fresh news, on the day
 * that hospital is already having its worst day.
 *
 * <p>The delivery is the assertion rather than a count of pending rows,
 * because the promise is about what somebody's system receives, and a
 * proxy for that would pass while the phones rang.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportDoesNotRedeliverIT {

    private static final byte[] OWNER_KEY = new byte[32];
    private static final String HOOK = "/restored";

    static HttpServer server;
    static String endpoint;
    static final List<String> delivered = new CopyOnWriteArrayList<>();

    static PGSimpleDataSource sourceDs;
    static PGSimpleDataSource destinationDs;
    static R4Store sourceFhir;

    private static R4Personality personality() {
        return new R4Personality(List.of(
                FhirTypeConfig.internal("Observation"),
                FhirTypeConfig.internal("Subscription")));
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("ImportDoesNotRedeliverIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            delivered.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        // The tenant as it was: a live subscription, and history under it.
        sourceDs = database("redeliver_source");
        R4Personality p = personality();
        sourceFhir = new R4Store(new PgObjectStore(sourceDs, p.registrations()), p,
                "https://dbo.test/fhir");
        sourceFhir.create("""
                {"resourceType":"Subscription","status":"active",
                 "reason":"restore replay test","criteria":"Observation?code=http://loinc.org|R-1",
                 "channel":{"type":"rest-hook","endpoint":"%s%s",
                            "payload":"application/fhir+json"}}"""
                .formatted(endpoint, HOOK));
        for (int i = 0; i < 20; i++) {
            sourceFhir.create("""
                    {"resourceType":"Observation","status":"final",
                     "code":{"coding":[{"system":"http://loinc.org","code":"R-1"}]}}""");
        }

        destinationDs = database("redeliver_destination");
    }

    @AfterAll
    void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("a restored tenant notifies nobody — the past is recovered, not re-enacted")
    void restoringATenantDeliversNothing() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(sourceDs, R4Personality.DOMAIN, OWNER_KEY, archive);

        R4Personality p = personality();
        PgObjectStore destination = new PgObjectStore(destinationDs, p.registrations());
        CoSignedArchive.over(archive.toByteArray(), OWNER_KEY)
                .importInto(destination, OWNER_KEY, TenantImport.HistoryMode.FRESH);

        // The restored tenant is brought up: its subscriptions are live again,
        // pointed at the endpoints they were always pointed at.
        PgChangeFeed feed = new PgChangeFeed(destinationDs, R4Personality.DOMAIN);
        try (SubscriptionEngine engine = new SubscriptionEngine(destinationDs,
                R4Personality.DOMAIN, destination, feed,
                R4Subscriptions.source(destination, p),
                R4Subscriptions.criteriaCompiler(p),
                new RestHookTransport(),
                destinationDs.getUrl(), SharedPostgres.get().getUsername(),
                SharedPostgres.get().getPassword())) {

            engine.dispatchOnce(500);
            Thread.sleep(1000); // give any delivery the chance to arrive and be caught
        }

        assertEquals(List.of(), delivered,
                "a restore must not tell anyone that twenty-odd historical observations "
                        + "just happened — the clinicians who were notified when these were "
                        + "recorded would be notified again, during a recovery");
    }
}
