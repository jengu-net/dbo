package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.Anonymity;
import cloud.jengu.dbo.auth.Bindings;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.core.api.identity.AnonymityEvent;
import cloud.jengu.dbo.core.api.identity.BindingEvent;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dbo#39: anonymous on purpose is a thing a subject can say, and saying it
 * stops them being identified.
 *
 * <p>The failure this prevents is nobody's fault and everybody's problem: two
 * unbound subjects look identical, one expects to be identified and the other
 * must not be, and a workflow that treats them alike helps somebody exercise
 * diligence against a person's legal right.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnonymityIT {

    private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AnonymityIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE anonymity");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "anonymity");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, IdentityModel.registrations());
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: not yet identified and anonymous on purpose are different states")
    void theTwoUnboundStatesAreDistinguishable() {
        assertFalse(Anonymity.declared(store, "trauma-patient"),
                "an unconscious patient nobody has identified is not anonymous by choice — "
                        + "binding them is expected, and somebody should be prompted");

        Anonymity.record(store, AnonymityEvent.declared("anonymous-test", "clinic-desk", NOW,
                "anonymous testing", "patient asked not to be recorded"));

        assertTrue(Anonymity.declared(store, "anonymous-test"));
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: identifying somebody anonymous by declaration is refused, not discouraged")
    void bindingIsRefusedForADeclaredAnonymousSubject() {
        Anonymity.record(store, AnonymityEvent.declared("subject-a", "clinic-desk", NOW,
                "anonymous testing", null));

        Anonymity.AnonymityRefusedException refused = assertThrows(
                Anonymity.AnonymityRefusedException.class,
                () -> Bindings.record(store, BindingEvent.bound("person-1", "subject-a",
                        "reception-desk-7", NOW, "TREAT", "recognised them")));

        assertTrue(refused.getMessage().contains("anonymous by declaration"), refused.getMessage());
        assertEquals(Set.of(), Bindings.current(store, "subject-a"));
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: an earlier identification can still be withdrawn once anonymity is declared")
    void withdrawalStaysAvailable() {
        Bindings.record(store, BindingEvent.bound("person-2", "subject-b",
                "desk", NOW, "TREAT", "eID"));
        Bindings.record(store, BindingEvent.withdrawn("person-2", "subject-b",
                "desk", NOW.plusSeconds(60), "PATRQT", "patient asked"));
        Anonymity.record(store, AnonymityEvent.declared("subject-b", "desk",
                NOW.plusSeconds(120), "patient request", null));

        Bindings.record(store, BindingEvent.withdrawn("person-3", "subject-b",
                "desk", NOW.plusSeconds(180), "PATRQT", "another stale attachment"));

        assertEquals(Set.of(), Bindings.current(store, "subject-b"),
                "detaching is the corrective direction, and somebody who has asked to be "
                        + "anonymous is exactly who most needs an earlier identification undone");
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: declaring anonymity over a standing identity is refused — withdraw first")
    void declaringOverAnIdentityIsRefused() {
        Bindings.record(store, BindingEvent.bound("person-4", "subject-c",
                "desk", NOW, "TREAT", "eID"));

        Anonymity.AnonymityRefusedException refused = assertThrows(
                Anonymity.AnonymityRefusedException.class,
                () -> Anonymity.record(store, AnonymityEvent.declared("subject-c", "desk",
                        NOW.plusSeconds(60), "patient request", null)));

        assertTrue(refused.getMessage().contains("withdraw the binding first"),
                refused.getMessage());
        assertEquals(Set.of("person-4"), Bindings.current(store, "subject-c"),
                "and the identity is untouched — silently dropping it would be a decision "
                        + "nobody made");
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: a person may change their mind, and then they can be identified")
    void liftingTheDeclarationAllowsIdentification() {
        Anonymity.record(store, AnonymityEvent.declared("subject-d", "desk", NOW,
                "anonymous testing", null));
        Anonymity.record(store, AnonymityEvent.lifted("subject-d", "desk", NOW.plusSeconds(60),
                "patient chose to be identified", "wants the result in their record"));

        assertFalse(Anonymity.declared(store, "subject-d"));
        Bindings.record(store, BindingEvent.bound("person-5", "subject-d",
                "desk", NOW.plusSeconds(120), "PATRQT", "at the patient's request"));

        assertEquals(Set.of("person-5"), Bindings.current(store, "subject-d"));
        assertEquals(2, Anonymity.history(store, "subject-d").size(),
                "that the declaration once stood must outlive its lifting — otherwise nobody can "
                        + "answer whether somebody was identified during a period they had asked "
                        + "not to be");
    }

    @Test
    @Timeout(300)
    @DisplayName("#39: a declaration states its basis, and one without is refused")
    void aDeclarationStatesItsBasis() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AnonymityEvent.declared("subject-e", "desk", NOW, "  ", null))
                .getMessage().contains("basis"),
                "'somebody ticked a box' is not something anyone can rely on later");
    }
}
