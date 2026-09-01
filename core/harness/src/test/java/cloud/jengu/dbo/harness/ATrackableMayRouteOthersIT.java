package cloud.jengu.dbo.harness;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trackable may route other trackables.
 *
 * <p>The topology is a tree and only its root has a cursor: a connector talks
 * to the store, appliances sit behind it, instruments sit behind those. All of
 * them are things whose state is worth knowing, and the claim under test is
 * that the store holds one row per trackable however deep it sits — so the
 * rule about what a state is exists once rather than once per router.
 *
 * <p>No FHIR personality here: a trackable is an engine record and the word a
 * face would render it as is deliberately not in this repository.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATrackableMayRouteOthersIT {

    static PgObjectStore store;
    static Trackables trackables;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ATrackableMayRouteOthersIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        List<cloud.jengu.dbo.core.api.TypeRegistration> declarations =
                new ArrayList<>(WorkModel.registrations());
        declarations.addAll(TrackableModel.registrations());
        store = new PgObjectStore(ds, declarations);
        trackables = new Trackables(store);
    }

    @Test
    @DisplayName("a connector reports a tree, and every trackable in it is one row — the "
            + "instrument two hops down is stored exactly as the connector is")
    @Proving(DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS)
    void stateIsNormalisedAtAnyDepth() {
        trackables.reports(Trackable.reporting("connector-1", "connector",
                Map.of("link", "up")));
        trackables.routes("connector-1", List.of(
                Trackable.routed("bench-7", "appliance", "connector-1",
                        Map.of("power", "on")),
                Trackable.routed("analyser-3", "instrument", "bench-7",
                        Map.of("state", "running", "reagent", "low"))));

        Trackable deep = trackables.byId("analyser-3").orElseThrow();
        assertEquals("bench-7", deep.routedBy(), "the tree is an edge per row");
        assertEquals("low", deep.state().get("reagent"),
                "and its state is stored the same way the connector's is");
        // Scoped to this connector rather than counting the store: sibling
        // tests share it, and a global count would pass or fail on execution
        // order rather than on the claim.
        for (String id : List.of("connector-1", "bench-7", "analyser-3")) {
            assertTrue(trackables.byId(id).isPresent(),
                    id + " is a row of its own, whatever the depth");
        }

        // The two questions an operator actually asks, and they are different.
        assertEquals(List.of("analyser-3"),
                trackables.behind("bench-7").stream().map(Trackable::id).toList(),
                "what is behind this box");
        assertEquals(2, trackables.observedBy("connector-1").size(),
                "what does this connector account for at all — which is not the same set");
        assertEquals(2, trackables.subtree("connector-1").size(),
                "and the whole subtree, walked one edge at a time");
    }

    @Test
    @DisplayName("the observer is the worker that reported, not the parent — knowing which "
            + "hop last saw something is what tells an operator where to look")
    @Proving(DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS)
    void theAttestationCarriesItsObserver() {
        trackables.routes("connector-2", List.of(
                Trackable.routed("bench-9", "appliance", "connector-2", Map.of()),
                Trackable.routed("analyser-9", "instrument", "bench-9", Map.of())));

        Trackable instrument = trackables.byId("analyser-9").orElseThrow();
        assertEquals("bench-9", instrument.routedBy(), "where it sits");
        assertEquals("connector-2", instrument.attested().observedBy(),
                "who to ask about it — two hops up, and the parent is not the answer");
        assertNotNull(instrument.attested().at());

        // Something that speaks for itself carries no attestation: its presence
        // is derived from its cursor, which is a different and better fact.
        trackables.reports(Trackable.reporting("connector-2", "connector", Map.of()));
        assertNull(trackables.byId("connector-2").orElseThrow().attested(),
                "presence stays derived where there is a cursor to derive it from");
    }

    @Test
    @DisplayName("a report replaces what was there: a state record does not become the "
            + "metrics history it is not")
    @Proving(DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS)
    void aReportReplaces() {
        trackables.routes("connector-3", List.of(
                Trackable.routed("probe-1", "instrument", "connector-3",
                        Map.of("state", "idle"))));
        trackables.routes("connector-3", List.of(
                Trackable.routed("probe-1", "instrument", "connector-3",
                        Map.of("state", "running"))));

        Trackable probe = trackables.byId("probe-1").orElseThrow();
        assertEquals("running", probe.state().get("state"));
        assertEquals(1, probe.state().size(),
                "replaced, not merged: a key that stopped being reported is gone, and a "
                        + "state nobody is reporting any more must not linger as though it were");
        assertEquals(1, trackables.behind("connector-3").size(), "and it is still one row");
    }

    @Test
    @DisplayName("something behind a router cannot report for itself, because the store has "
            + "no path to it and would be recording a claim with no observer")
    @Proving(DboPromises.PROC_A_TRACKABLE_MAY_ROUTE_OTHERS)
    void aRoutedTrackableCannotSpeakForItself() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> trackables.reports(new Trackable("analyser-4", "instrument",
                        "bench-7", Map.of(), null)));

        assertTrue(refused.getMessage().contains("routes()"),
                "refused by name, pointing at the call that records who saw it: "
                        + refused.getMessage());
    }
}
