package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.Trackable;
import cloud.jengu.dbo.work.TrackableModel;
import cloud.jengu.dbo.work.Trackables;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A routee missing from a router's report is something the router said,
 * and the store keeps it as a statement rather than discarding it as a gap.
 *
 * <p>The router spoke — its cursor moved — and did not include the routee.
 * That is distinguishable from silence, and the distinction is exactly what
 * an operator needs: <i>the bench went away</i> and <i>the connector went
 * quiet</i> want different phone calls. So the departed row stays, its last
 * attestation stays, and the moment it stopped being reported goes beside
 * them. No freshness rule comes with it; what the silence means is the
 * caller's to judge from the hop's cadence.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADepartedRouteeIsAStatementIT {

    static Trackables trackables;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ADepartedRouteeIsAStatementIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        List<TypeRegistration> declarations = new ArrayList<>(WorkModel.registrations());
        declarations.addAll(TrackableModel.registrations());
        trackables = new Trackables(new PgObjectStore(ds, declarations));
    }

    @Test
    @DisplayName("two routees reported, then one: the missing one is still readable, says who "
            + "last saw it and when, and says it is no longer reported — and reappears clean")
    @Proving(DboPromises.PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT)
    void goneReadsAsLastSeenAndThenAbsent() {
        trackables.routes("connector-7", List.of(
                Trackable.routed("bench-a", "appliance", "connector-7", Map.of("power", "on")),
                Trackable.routed("bench-b", "appliance", "connector-7", Map.of("power", "on"))));
        Trackable seen = trackables.byId("bench-b").orElseThrow();
        Instant lastSeen = seen.attested().at();
        assertTrue(seen.reported());

        trackables.routes("connector-7", List.of(
                Trackable.routed("bench-a", "appliance", "connector-7", Map.of("power", "on"))));

        Trackable departed = trackables.byId("bench-b").orElseThrow(
                () -> new AssertionError("the departed routee was discarded, so gone reads "
                        + "exactly like a connector that went quiet"));
        assertFalse(departed.reported(), "the report left it out, and the record says so");
        assertNotNull(departed.unreported(), "with the moment the silence began");
        assertEquals("connector-7", departed.attested().observedBy(), "and who last saw it");
        assertEquals(lastSeen, departed.attested().at(),
                "the last attestation is kept, not refreshed — last seen at T, absent at T+1");
        assertFalse(departed.unreported().isBefore(lastSeen));
        assertEquals("on", departed.state().get("power"), "and its last state");

        // The tree answers with it, distinguishably rather than by omission.
        assertTrue(trackables.behind("connector-7").stream()
                        .anyMatch(t -> t.id().equals("bench-b") && !t.reported()),
                "behind: " + trackables.behind("connector-7"));
        assertTrue(trackables.subtree("connector-7").stream()
                        .anyMatch(t -> t.id().equals("bench-b") && !t.reported()));
        assertTrue(trackables.observedBy("connector-7").stream()
                        .anyMatch(t -> t.id().equals("bench-b") && !t.reported()));
        assertTrue(trackables.behind("connector-7").stream()
                        .anyMatch(t -> t.id().equals("bench-a") && t.reported()),
                "and the one still reported reads as reported");
        // What crosses a wire carries the mark, so a caller outside sees it too.
        assertTrue(RecordWire.write(RecordWire.encode(departed)).contains("\"unreported\":"));

        // A quiet router says nothing: nothing changes for what it saw.
        trackables.routes("connector-8", List.of(
                Trackable.routed("probe-x", "instrument", "connector-8", Map.of())));
        assertTrue(trackables.byId("probe-x").orElseThrow().reported());
        trackables.routes("connector-7", List.of(
                Trackable.routed("bench-a", "appliance", "connector-7", Map.of("power", "on"))));
        assertTrue(trackables.byId("probe-x").orElseThrow().reported(),
                "another router's report says nothing about what this one sees");

        // Back in the next report: reported again, freshly attested.
        trackables.routes("connector-7", List.of(
                Trackable.routed("bench-a", "appliance", "connector-7", Map.of("power", "on")),
                Trackable.routed("bench-b", "appliance", "connector-7", Map.of("power", "off"))));
        Trackable returned = trackables.byId("bench-b").orElseThrow();
        assertTrue(returned.reported());
        assertNull(returned.unreported());
        assertTrue(returned.attested().at().isAfter(lastSeen));
        assertEquals("off", returned.state().get("power"));
    }
}
