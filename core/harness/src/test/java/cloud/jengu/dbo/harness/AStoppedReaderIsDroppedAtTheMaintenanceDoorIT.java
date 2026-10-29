package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A reader of the work feed that stopped for good, dropped by the operator who
 * knows it is gone.
 *
 * <p>The work feed lets go only of what every reader has read past, so one
 * that stopped holds everything after it. The tenant cannot tell stopped from
 * away; this is where somebody who can says so.
 */
class AStoppedReaderIsDroppedAtTheMaintenanceDoorIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    @DisplayName("a work-feed reader that stopped holds the feed until it is dropped at the "
            + "maintenance door, and only a system-plane caller may drop it")
    @Proving(DboPromises.FEED_A_FEED_LETS_GO_OF_WHAT_EVERY_READER_HAS_READ_PAST)
    void aStoppedReaderIsDropped() throws Exception {
        SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R5);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(tenant.databaseUrl());
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        PgChangeFeed work = new PgChangeFeed(ds, WorkModel.DOMAIN);
        String gone = "a-decommissioned-ward-" + System.nanoTime();
        work.resetConsumer(gone, null);
        assertEquals(gone, work.prune(List.of(), 0).heldBy().name(),
                "a reader that has read nothing did not hold the work feed");

        String body = "{\"reader\":\"" + gone + "\"}";
        assertEquals(403, drop(tenant, tenant.token("ward-reader", "system/*.read"), body)
                .statusCode(), "a caller who may only read dropped a reader");
        HttpResponse<String> dropped = drop(tenant,
                tenant.token("ward-operator", "system/*.write"), body);
        assertEquals(200, dropped.statusCode(), dropped.body());
        assertEquals(404, drop(tenant, tenant.token("ward-operator", "system/*.write"), body)
                .statusCode(), "a reader dropped twice was found the second time");
        PgChangeFeed.Reader held = work.prune(List.of(), 0).heldBy();
        assertFalse(held != null && gone.equals(held.name()),
                "the dropped reader still holds the work feed");
    }

    private static HttpResponse<String> drop(SharedTenants.Tenant tenant, String token,
            String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(tenant.base()
                                + "/admin/work-feed/drop"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
