package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream between any two tenants a deployment accepts has a cursor to keep.
 *
 * <p>A dependent's cursor on its upstream's feed is named for both of them —
 * {@code sync.<upstream>.<dependent>.definitions} — and the feed refused any
 * name past sixty-four characters. Two tenant codes are each allowed a
 * hundred and twenty-eight, so a pair the deployment served happily got no
 * stream at all: the dependent came up, nothing ever arrived, and the only
 * account of it was the same exception every few seconds in the log. Found
 * by the user stories, whose tenant codes carry a story and a run.
 *
 * <p>No world: the cursor is the feed's, and a feed is a database.
 */
class AStreamBetweenAnyTwoTenantsHasAConsumerIT {

    @Test
    @DisplayName("two tenants with codes as long as a deployment allows still get a stream "
            + "between them, its cursor named for both")
    @Proving(DboPromises.SYNC_SPEC_DECLARED)
    void theLongestPairStillHasACursor() throws Exception {
        String upstream = "u" + "-".repeat(126) + "u";
        String dependent = "d" + "-".repeat(126) + "d";
        assertTrue(TenantSpec.isCode(upstream) && TenantSpec.isCode(dependent),
                "the codes are not ones a deployment accepts, so this proves nothing");
        // The dependency names its upstream by code, and was bounded at half
        // of what a code may be.
        assertEquals(upstream, new cloud.jengu.dbo.sync.ContentDependency(upstream,
                java.util.Set.of("CodeSystem")).name(),
                "a dependency on a tenant the deployment serves was refused for its name");
        String consumer = "sync." + upstream + "." + dependent + ".definitions";

        PGSimpleDataSource ds = database("stream_between_long_codes");
        R4Personality personality =
                new R4Personality(List.of(FhirTypeConfig.internal("Observation")));
        PgObjectStore store = new PgObjectStore(ds, personality.registrations());
        PgChangeFeed feed = new PgChangeFeed(ds, R4Personality.DOMAIN);
        store.put(PutRequest.create("Observation",
                "{\"resourceType\":\"Observation\",\"status\":\"final\"}"
                        .getBytes(StandardCharsets.UTF_8)));

        var read = feed.readFor(consumer, 10);
        assertEquals(1, read.items().size(), "the stream delivered nothing");
        feed.ack(consumer, read.nextCursor());
        assertEquals(0, feed.readFor(consumer, 10).items().size(),
                "the cursor was not kept under the name the stream is known by");
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AStreamBetweenAnyTwoTenantsHasAConsumerIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }
}
