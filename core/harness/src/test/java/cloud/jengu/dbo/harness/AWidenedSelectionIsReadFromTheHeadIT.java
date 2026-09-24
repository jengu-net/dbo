package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ContentDependency;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream whose selection widens reads its feed from the head again.
 *
 * <p>A derived dependency asks the upstream for the canonicals its closure
 * reaches, and that closure grows: a profile published to a face can refer to
 * a definition that was outside the closure when it was first walked. The
 * manifest is recomputed, so the dependent starts asking for it.
 *
 * <p><b>And asking is not enough, because a cursor moves PAST what a selection
 * excluded rather than around it.</b> A definition skipped at the moment it was
 * read is behind the position for good: the feed has nothing left to say about
 * it, and a dependent waiting for it waits forever. Nothing about that state
 * looks wrong — the face simply stopped being complete, while every write is
 * still judged and every answer still arrives.
 *
 * <p>So a selection that gained a name is read from the head. Applying is
 * idempotent and dedupes on the version, which is what makes re-reading a cost
 * rather than a second write.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AWidenedSelectionIsReadFromTheHeadIT {

    private static final String EARLY = "https://ee.ee/ValueSet/Varem";
    private static final String LATER = "https://ee.ee/ValueSet/Hiljem";

    static PGSimpleDataSource upstreamDs;
    static PGSimpleDataSource downstreamDs;
    static R4Store upstream;
    static PgObjectStore downstreamEngine;
    static ChangeFeed feed;

    @BeforeAll
    void up() throws Exception {
        PostgreSQLContainer<?> postgres = SharedPostgres.get();
        String jdbcUrl = SharedPostgres.urlFor("AWidenedSelectionIsReadFromTheHeadIT");
        try (Connection c = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE widened_up");
            st.execute("CREATE DATABASE widened_down");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        upstreamDs = ds(base + "widened_up", postgres);
        downstreamDs = ds(base + "widened_down", postgres);

        // An operational type beside the canonical one: the outbox and its
        // consumer are made for a domain that has something to publish, and a
        // store holding only replicated records has nothing of its own.
        List<FhirTypeConfig> types = List.of(
                FhirTypeConfig.canonical("ValueSet"),
                FhirTypeConfig.internal("Encounter"));
        R4Personality up = new R4Personality(types);
        R4Personality down = new R4Personality(types);
        PgObjectStore upstreamEngine = new PgObjectStore(upstreamDs, up.registrations());
        downstreamEngine = new PgObjectStore(downstreamDs, down.registrations());
        upstream = new R4Store(upstreamEngine, up, "https://upstream.test");
        // A value set moves on the feed a FACE moves on, not the one records
        // move on: the narrowing this is about is the definitions feed's.
        feed = new PgChangeFeed(upstreamDs, cloud.jengu.dbo.core.api.Domains.DEFINITIONS);

        // THE ONE NOBODY NAMES YET GOES FIRST, and the order is the whole
        // test. A cursor stops at the last item DELIVERED, so an excluded item
        // ahead of that point is simply not reached yet and arrives on its own
        // the moment it is asked for. Only one sitting BEHIND a delivered item
        // is passed over, and passed over is forever.
        upstream.putCanonical(valueSet(LATER));
        upstream.putCanonical(valueSet(EARLY));
    }

    @Test
    @DisplayName("what a narrower selection skipped arrives once the selection names it, because "
            + "the stream is read from the head rather than from where it had got to")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void whatWasSkippedArrivesWhenItIsAskedFor() {
        AtomicReference<Set<String>> asking = new AtomicReference<>(Set.of(EARLY));
        ContentSyncEngine stream = new ContentSyncEngine(
                new ContentDependency("upstream", Set.of("ValueSet"), asking::get),
                feed, downstreamEngine, downstreamDs,
                cloud.jengu.dbo.core.api.Domains.DEFINITIONS, "4.0", List.of());

        // The narrow round. The later value set is excluded, and the cursor
        // moves past it: this is the state the rewind exists for.
        while (stream.syncOnce(100) > 0) { }
        assertTrue(held(EARLY), "the named value set did not arrive at all");
        assertTrue(!held(LATER), "a canonical nobody named arrived, so nothing was narrowed");

        // The closure grew. Without the rewind the feed has nothing left to
        // say — the position is past it — and the dependent waits forever for
        // a definition it is now asking for by name.
        asking.set(Set.of(EARLY, LATER));
        while (stream.syncOnce(100) > 0) { }
        assertTrue(held(LATER),
                "a selection that gained a name was not sent what it had skipped, so this face "
                        + "stopped being complete and nothing about it looks wrong");
    }

    /** Whether the downstream holds the value set with this url. */
    private boolean held(String canonical) {
        for (cloud.jengu.dbo.core.api.Held one
                : downstreamEngine.inventory("ValueSet", List.of())) {
            var stored = downstreamEngine.get("ValueSet", one.id()).orElse(null);
            if (stored != null && new String(stored.payload(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("\"" + canonical + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static String valueSet(String url) {
        return """
                {"resourceType":"ValueSet","url":"%s","status":"active","name":"Nimi"}"""
                .formatted(url);
    }

    private static PGSimpleDataSource ds(String url, PostgreSQLContainer<?> postgres) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
