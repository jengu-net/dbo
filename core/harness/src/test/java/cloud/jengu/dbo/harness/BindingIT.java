package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.Bindings;
import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attaching an identity to a subject, and taking it back.
 *
 * <p>Binding is the act no read control touches — an anonymous subject has no
 * identity to read, so protecting reads protects nothing until somebody binds.
 * And a wrong binding puts one person's care in another's record, which is why
 * withdrawal has to exist and has to leave the care alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindingIT {

    private static final Instant MONDAY = Instant.parse("2026-08-17T09:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-08-20T14:30:00Z");

    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("BindingIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE bindings");
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "bindings");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, IdentityModel.registrations());
    }

    @Test
    @Timeout(300)
    @DisplayName("an anonymous subject has nobody attached until somebody attaches them")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void aSubjectStartsAnonymous() {
        assertEquals(Set.of(), Bindings.current(store, "subject-untouched"),
                "care is recorded before anybody knows who the person is, and that is a complete "
                        + "state rather than a gap");
    }

    @Test
    @Timeout(300)
    @DisplayName("withdrawing removes the identity and keeps the evidence it was there")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void withdrawalKeepsTheEvidence() {
        Bindings.record(store, BindingEvent.bound("person-1", "subject-1", Assurance.SUBSTANTIAL,
                "reception-desk-7", MONDAY, "TREAT", "national eID presented"));
        assertEquals(Set.of("person-1"), Bindings.current(store, "subject-1"));

        Bindings.record(store, BindingEvent.withdrawn("person-1", "subject-1",
                "records-office", THURSDAY, "PATRQT", "identified in error — wrong twin"));

        assertEquals(Set.of(), Bindings.current(store, "subject-1"),
                "the identity is off the subject");
        assertEquals(2, Bindings.history(store, "subject-1").size(),
                "and that it was ever attached remains answerable — a withdrawal that erased the "
                        + "binding would erase exactly what somebody would want erased if the "
                        + "binding had been wrong");
    }

    @Test
    @Timeout(300)
    @DisplayName("a mistaken withdrawal is as recoverable as a mistaken binding")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void rebindingAfterAWithdrawalWorks() {
        Bindings.record(store, BindingEvent.bound("person-2", "subject-2", Assurance.SUBSTANTIAL,
                "desk", MONDAY, "TREAT", "first"));
        Bindings.record(store, BindingEvent.withdrawn("person-2", "subject-2",
                "desk", MONDAY.plusSeconds(60), "TREAT", "second thoughts"));
        Bindings.record(store, BindingEvent.bound("person-2", "subject-2", Assurance.SUBSTANTIAL,
                "desk", MONDAY.plusSeconds(120), "TREAT", "confirmed after all"));

        assertEquals(Set.of("person-2"), Bindings.current(store, "subject-2"),
                "both are decisions somebody made in a hurry, so both have to be undoable");
        assertEquals(3, Bindings.history(store, "subject-2").size());
    }

    @Test
    @Timeout(300)
    @DisplayName("binding without a purpose or a person behind it is refused")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void bindingNamesWhoAndWhy() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BindingEvent.bound("person-3", "subject-3", Assurance.SUBSTANTIAL, "  ", MONDAY, "TREAT", null))
                .getMessage().contains("who did it"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BindingEvent.bound("person-3", "subject-3", Assurance.SUBSTANTIAL, "desk", MONDAY, "", null))
                .getMessage().contains("purpose"));
    }

    @Test
    @Timeout(300)
    @DisplayName("a recorded binding cannot be rewritten into a different one")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void bindingEventsAreAppendOnly() {
        String id = Bindings.record(store, BindingEvent.bound("person-4", "subject-4", Assurance.SUBSTANTIAL,
                "desk", MONDAY, "TREAT", "eID"));

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.put(new PutRequest("BindingEvent", id, null,
                        ("{\"kind\":\"BOUND\",\"identityId\":\"person-999\","
                                + "\"subjectId\":\"subject-4\",\"actor\":\"desk\","
                                + "\"at\":\"2026-08-17T09:00:00Z\",\"purpose\":\"TREAT\"}")
                                .getBytes(StandardCharsets.UTF_8))));

        assertTrue(refused.getMessage().contains("append-only"),
                "rewriting who was attached would leave a trail saying somebody else was "
                        + "identified: " + refused.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("one subject's bindings say nothing about another's")
    @Proving(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE)
    void bindingsAreScopedToTheirSubject() {
        Bindings.record(store, BindingEvent.bound("person-5", "subject-5", Assurance.SUBSTANTIAL,
                "desk", MONDAY, "TREAT", "eID"));

        assertEquals(Set.of(), Bindings.current(store, "subject-6"));
        for (StoredObject event : Bindings.history(store, "subject-5")) {
            assertTrue(new String(event.payload(), StandardCharsets.UTF_8).contains("subject-5"));
        }
    }
}
