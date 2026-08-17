package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jengu-platform#870: the store refuses what a type's class forbids.
 *
 * <p>Each refusal is proven on its own against a real store. "The shields
 * work" is one sentence and four unrelated mechanisms, and a test covering
 * only the first would leave the rest as beliefs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HandlingShieldsIT {

    private static final String DOMAIN = "shields";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Ledger", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.audit(), PLAIN, List.of()),
            new TypeRegistration("Vocabulary", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.replicated(), PLAIN, List.of()),
            new TypeRegistration("Note", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.operational(), PLAIN, List.of()),
            // current-state-only and it never leaves: a session, not a heartbeat —
            // heartbeats are not stored objects at all
            new TypeRegistration("Session", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    new Handling(Handling.Authority.PLATFORM_RUNTIME,
                            Handling.Mutability.REPLACE_IN_PLACE,
                            Handling.Durability.CURRENT_ONLY, Handling.Travel.NEVER),
                    PLAIN, List.of()));

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        String jdbcUrl = SharedPostgres.urlFor("HandlingShieldsIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE handling_shields");
        }
        ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + "handling_shields");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, TYPES);
    }

    private static byte[] body(String what) {
        return ("{\"what\":\"" + what + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: an audit record can be written once and never altered or removed")
    void auditCannotBeAltered() {
        PutResult written = store.put(PutRequest.create("Ledger", body("logged in")));

        cloud.jengu.dbo.core.api.HandlingRefusedException onUpdate = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.put(new PutRequest("Ledger", written.id(), null, body("logged out"))));
        cloud.jengu.dbo.core.api.HandlingRefusedException onDelete = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.delete("Ledger", written.id(), null));

        assertTrue(onUpdate.getMessage().contains("append-only"), onUpdate.getMessage());
        assertTrue(onDelete.getMessage().contains("append-only"), onDelete.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: the audit shield holds against the platform itself, not only tenant users")
    void auditShieldHoldsAgainstEveryCaller() {
        PutResult written = store.put(PutRequest.create("Ledger", body("something happened")));

        for (Handling.Authority caller : Handling.Authority.values()) {
            assertThrows(cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                    () -> store.put(new PutRequest("Ledger", written.id(), null, body("edited")),
                            caller),
                    "an append-only record altered by " + caller + " is still altered");
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: a tenant cannot edit its copy of a vocabulary another tenant publishes")
    void replicatedDataIsReadOnlyHere() {
        // the publishing lane may write it
        PutResult published = store.put(PutRequest.create("Vocabulary", body("v1")),
                Handling.Authority.SOURCE_TENANT);

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.put(new PutRequest("Vocabulary", published.id(), null, body("edited"))));

        assertTrue(refused.getMessage().contains("read-only-here"), refused.getMessage());
        assertTrue(refused.getMessage().contains("SOURCE_TENANT"),
                "the refusal must say whose data it is: " + refused.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: a refusal names the rule that refused it, and the type")
    void aRefusalNamesItsRule() {
        PutResult written = store.put(PutRequest.create("Ledger", body("x")));

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.delete("Ledger", written.id(), null));

        assertTrue(refused.getMessage().startsWith("Ledger:"), refused.getMessage());
        assertTrue(refused.getMessage().contains("refused by the"), refused.getMessage());
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: ordinary data is unaffected — the shields refuse, they do not obstruct")
    void operationalDataIsUntouched() {
        PutResult note = store.put(PutRequest.create("Note", body("a note")));
        store.put(new PutRequest("Note", note.id(), null, body("an edited note")));
        store.delete("Note", note.id(), null);
    }

    @Test
    @Timeout(300)
    @DisplayName("#870: data that never leaves is in no archive — neither representation carries it")
    void neverLeavingDataIsInNoArchive() throws Exception {
        store.put(PutRequest.create("Session", body("secret-session-marker")),
                Handling.Authority.PLATFORM_RUNTIME);
        store.put(PutRequest.create("Note", body("ordinary-note-marker")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, DOMAIN, OWNER_KEY, out, TYPES);

        StringBuilder everything = new StringBuilder();
        try (InputStream plain = SealedArchive.opening(
                new ByteArrayInputStream(out.toByteArray()), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                everything.append(entry.getName()).append('\n')
                        .append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertTrue(everything.indexOf("ordinary-note-marker") >= 0,
                "the archive must carry what does travel, or this proves nothing");
        assertFalse(everything.indexOf("secret-session-marker") >= 0,
                "data declared as never leaving appeared in the archive — and the byte-faithful "
                        + "dumps are where it would hide, because they are whole tables");
        assertFalse(everything.indexOf("state/Session.ndjson") >= 0,
                "nor may it have an entry of its own");
    }
}
