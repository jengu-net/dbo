package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.Bindings;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.core.api.identity.Assurance;
import cloud.jengu.dbo.core.api.identity.BindingEvent;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A strong identity cannot be reached through a weak door.
 *
 * <p>A person holds several identifiers of different strengths and any of them
 * resolves the same record. Without this rule, whoever can assert the weakest
 * obtains everything the strongest earned — and nothing reports a problem,
 * because the lookup succeeds, the session issues and the grants apply. That
 * silence is why the rule belongs in code rather than in a review.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssuranceIT {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AssuranceIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE assurance");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "assurance");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, IdentityModel.registrations());
    }

    @Test
    @DisplayName("the chain is as strong as its weakest step")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void theWeakerBounds() {
        assertEquals(Assurance.LOW, Assurance.weakerOf(Assurance.HIGH, Assurance.LOW));
        assertEquals(Assurance.LOW, Assurance.weakerOf(Assurance.LOW, Assurance.HIGH));
        assertEquals(Assurance.SUBSTANTIAL,
                Assurance.weakerOf(Assurance.SUBSTANTIAL, Assurance.HIGH));
        assertEquals(Assurance.NONE, Assurance.weakerOf(Assurance.NONE, Assurance.HIGH),
                "no binding at all is the weakest step there is");
    }

    @Test
    @Timeout(300)
    @DisplayName("a national eID today does not upgrade an identification made from a photocopy")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void aStrongAssertionDoesNotUpgradeAWeakBinding() {
        Bindings.record(store, BindingEvent.bound("person-1", "subject-1", Assurance.LOW,
                "records-office", NOW, "TREAT", "photocopy of a licence, filed last year"));

        Assurance link = Bindings.assuranceOf(store, "subject-1", "person-1");
        Assurance effective = Assurance.weakerOf(Assurance.HIGH, link);

        assertEquals(Assurance.LOW, link);
        assertEquals(Assurance.LOW, effective,
                "presenting a strong credential proves who is at the keyboard, not that the "
                        + "identification behind the record was ever sound");
    }

    @Test
    @Timeout(300)
    @DisplayName("a weak assertion over a strong binding is bounded too")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void aWeakAssertionDoesNotInheritAStrongBinding() {
        Bindings.record(store, BindingEvent.bound("person-2", "subject-2", Assurance.HIGH,
                "desk", NOW, "TREAT", "national eID"));

        Assurance effective = Assurance.weakerOf(Assurance.LOW,
                Bindings.assuranceOf(store, "subject-2", "person-2"));

        assertEquals(Assurance.LOW, effective,
                "whoever can assert the weakest identifier must not obtain what the strongest "
                        + "earned — this is the silent failure the rule exists for");
    }

    @Test
    @Timeout(300)
    @DisplayName("re-identifying at a higher standard raises it, and the history keeps both")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void aBetterIdentificationRaisesIt() {
        Bindings.record(store, BindingEvent.bound("person-3", "subject-3", Assurance.LOW,
                "desk", NOW, "TREAT", "took their word for it"));
        Bindings.record(store, BindingEvent.bound("person-3", "subject-3", Assurance.HIGH,
                "desk", NOW.plusSeconds(3600), "TREAT", "eID presented at the counter"));

        assertEquals(Assurance.HIGH, Bindings.assuranceOf(store, "subject-3", "person-3"));
        assertEquals(2, Bindings.history(store, "subject-3").size(),
                "and what it was before remains answerable — access granted under the weaker "
                        + "identification happened, whatever is true now");
    }

    @Test
    @Timeout(300)
    @DisplayName("withdrawing leaves nothing to inherit")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void withdrawalDropsTheAssurance() {
        Bindings.record(store, BindingEvent.bound("person-4", "subject-4", Assurance.HIGH,
                "desk", NOW, "TREAT", "eID"));
        Bindings.record(store, BindingEvent.withdrawn("person-4", "subject-4",
                "desk", NOW.plusSeconds(60), "PATRQT", "wrong person"));

        assertEquals(Assurance.NONE, Bindings.assuranceOf(store, "subject-4", "person-4"));
        assertEquals(Assurance.NONE,
                Assurance.weakerOf(Assurance.HIGH,
                        Bindings.assuranceOf(store, "subject-4", "person-4")),
                "a withdrawn identification grants nothing, however strongly somebody "
                        + "authenticates");
    }

    @Test
    @DisplayName("an identification that established nothing is not an identification")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void bindingAtNoneIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BindingEvent.bound("person-5", "subject-5", Assurance.NONE,
                        "desk", NOW, "TREAT", null))
                .getMessage().contains("absence of a binding"));
    }

    @Test
    @Timeout(300)
    @DisplayName("one identity's assurance says nothing about another's on the same subject")
    @Proving(DboPromises.IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO)
    void assuranceIsPerIdentity() {
        Bindings.record(store, BindingEvent.bound("person-6", "subject-6", Assurance.HIGH,
                "desk", NOW, "TREAT", "eID"));

        assertEquals(Assurance.NONE, Bindings.assuranceOf(store, "subject-6", "person-7"));
    }
}
