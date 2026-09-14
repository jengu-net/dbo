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
 * The store refuses what a type's class forbids.
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
    private static final String CODE_SYSTEM = "https://shields.test/code";
    /** Named by what it declares itself to be, so the lane can upsert on it. */
    private static final EnvelopeExtractor BY_CODE = (typeName, payload) -> {
        String json = new String(payload, StandardCharsets.UTF_8);
        int at = json.indexOf("\"what\":\"") + 8;
        return new Envelope().identifier(CODE_SYSTEM, json.substring(at, json.indexOf('"', at)));
    };

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Ledger", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.audit(), PLAIN, List.of()),
            new TypeRegistration("Vocabulary", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.replicated(), PLAIN, List.of()),
            new TypeRegistration("Note", DOMAIN, IdentityClass.INTERNAL, java.util.Set.of(),
                    Handling.operational(), PLAIN, List.of()),
            // A projection of somebody's declaration, keyed the way a lane
            // applying one keys it: on what the declaration says it is.
            new TypeRegistration("Projected", DOMAIN, IdentityClass.IDENTIFIER,
                    java.util.Set.of(CODE_SYSTEM), Handling.projectedConfig(), BY_CODE,
                    List.of()),
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
    @DisplayName("an audit record can be written once and never altered or removed")
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
    @DisplayName("the audit shield holds against the platform itself, not only tenant users")
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
    @DisplayName("a tenant cannot edit its copy of a vocabulary another tenant publishes")
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

    /**
     * The promise this keeps is a sentence an administrator is told: a
     * configured change goes to one place. The store was where it broke —
     * a projection was writable by anybody, so an edit made in the store
     * either survived and made the declaration a lie, or was overwritten
     * without a word by the next pass and made the store one.
     */
    @Test
    @Timeout(300)
    @DisplayName("a tenant user cannot edit configuration projected from a declaration")
    void projectedConfigurationIsTheLanesToWrite() {
        PutResult applied = store.put(PutRequest.create("Projected", body("device-a")),
                Handling.Authority.CONFIG_LANE);

        cloud.jengu.dbo.core.api.HandlingRefusedException refused = assertThrows(
                cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.put(new PutRequest("Projected", applied.id(), null,
                        body("device-a-edited"))));

        assertTrue(refused.getMessage().contains("read-only-here"), refused.getMessage());
        assertTrue(refused.getMessage().contains("CONFIG_LANE"),
                "the refusal must say which lane may write it, so the person reading it knows "
                        + "where the change belongs: " + refused.getMessage());
    }

    /**
     * The branch the lane actually takes, and the one that had no way to say
     * who it was.
     *
     * <p>Applying a declaration is an upsert keyed on identity — declaring the
     * same thing again is what a source moving forward looks like — and
     * {@code putConditional} had no authority parameter, so the lane ran as
     * the least-privileged caller on the branch it uses most. Nothing said so
     * while the type permitted everybody. The refusal below is what that
     * silence was hiding.
     */
    @Test
    @Timeout(300)
    @DisplayName("the lane's own upsert has to say it is the lane, and is refused when it does "
            + "not")
    void anUpsertSaysWhoIsMakingIt() {
        cloud.jengu.dbo.core.api.IdentityRef ref =
                cloud.jengu.dbo.core.api.IdentityRef.identifier(CODE_SYSTEM, "device-b");

        store.putConditional(ref, PutRequest.create("Projected", body("device-b")),
                Handling.Authority.CONFIG_LANE);
        // and again, which is the case that made this an upsert at all
        store.putConditional(ref, PutRequest.create("Projected", body("device-b")),
                Handling.Authority.CONFIG_LANE);

        assertThrows(cloud.jengu.dbo.core.api.HandlingRefusedException.class,
                () -> store.putConditional(ref,
                        PutRequest.create("Projected", body("device-b"))),
                "an upsert that states no authority is the least-privileged caller, and a "
                        + "projection is not that caller's to write");
    }

    @Test
    @Timeout(300)
    @DisplayName("a refusal names the rule that refused it, and the type")
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
    @DisplayName("ordinary data is unaffected — the shields refuse, they do not obstruct")
    void operationalDataIsUntouched() {
        PutResult note = store.put(PutRequest.create("Note", body("a note")));
        store.put(new PutRequest("Note", note.id(), null, body("an edited note")));
        store.delete("Note", note.id(), null);
    }

    @Test
    @Timeout(300)
    @DisplayName("data that never leaves is in no archive — neither representation carries it")
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
