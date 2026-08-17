package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jengu-platform#866: an archive says what it is for, and the two kinds carry
 * different things.
 *
 * <p>A backup holds the state that exists nowhere else so a restored
 * installation can authenticate its own tenants. A portable export holds what
 * belongs to the customer, with the vocabulary their codes resolve against and
 * none of our key material.
 *
 * <p>The failure worth preventing is not a wrong file being rejected — it is a
 * wrong file being <b>accepted</b>. Restoring an export where a backup was
 * meant produces an installation that looks populated and cannot log anybody
 * in, discovered at sign-in long after the restore reported success.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveKindIT {

    private static final String DOMAIN = "kinds";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();

    /** One of each travel class, so the split is exercised rather than assumed. */
    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Note", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.operational(), PLAIN, List.of()),
            new TypeRegistration("Vocabulary", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.replicated(), PLAIN, List.of()),
            new TypeRegistration("Credential", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.storeAuthored(), PLAIN, List.of()),
            new TypeRegistration("Ledger", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.audit(), PLAIN, List.of()));

    static PGSimpleDataSource ds;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        ds = database("archive_kinds");
        PgObjectStore store = new PgObjectStore(ds, TYPES);
        for (String type : List.of("Note", "Credential", "Ledger")) {
            store.put(PutRequest.create(type,
                    ("{\"kind\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8)));
        }
        // The vocabulary is written as the lane that publishes it — the shield
        // refuses every other caller, which is the point, and a test seeding
        // it as a tenant user is a test asking to be refused.
        store.put(PutRequest.create("Vocabulary",
                        "{\"kind\":\"Vocabulary\"}".getBytes(StandardCharsets.UTF_8)),
                Handling.Authority.SOURCE_TENANT);
    }

    @Test
    @Timeout(300)
    @DisplayName("#866: a portable export carries the customer's data and the vocabulary its "
            + "codes resolve against, and no credentials")
    void aPortableExportCarriesVocabularyAndNoCredentials() throws Exception {
        TreeSet<String> entries = entriesOf(exported(TenantExport.Kind.PORTABLE_EXPORT));

        assertTrue(entries.contains("state/Note.ndjson"),
                "the customer's own records travel: " + entries);
        assertTrue(entries.contains("state/Vocabulary.ndjson"),
                "and so does the terminology — an export whose CodeSystems are absent is "
                        + "syntactically valid FHIR and semantically unreadable, which makes "
                        + "'your data is yours' true on paper only");
        assertFalse(entries.contains("state/Credential.ndjson"),
                "credentials do not: an export hands somebody a copy of their records, not "
                        + "our keys");
        assertFalse(entries.contains("state/Ledger.ndjson"),
                "nor does the audit trail, which is ours to keep and not theirs to carry");
        assertTrue(entries.stream().noneMatch(e -> e.startsWith("fidelity/")),
                "and none of this store's byte-faithful internals, least of all the vault: "
                        + entries);
    }

    @Test
    @Timeout(300)
    @DisplayName("#866: a backup carries the state that exists nowhere else, so a restored "
            + "installation can authenticate its own tenants")
    void aBackupCarriesStoreAuthoredState() throws Exception {
        TreeSet<String> entries = entriesOf(exported(TenantExport.Kind.BACKUP));

        assertTrue(entries.contains("state/Credential.ndjson"),
                "a backup without credentials restores an installation nobody can log in to");
        assertTrue(entries.contains("state/Ledger.ndjson"),
                "and the audit trail is kept, because it exists nowhere else either");
        assertTrue(entries.contains("fidelity/state." + DOMAIN + "_data.csv"),
                "with the byte-faithful element a real restore loads: " + entries);
    }

    @Test
    @Timeout(300)
    @DisplayName("#866: each archive declares its kind, so a reader does not have to infer it "
            + "from what happens to be inside")
    void theKindIsDeclared() throws Exception {
        assertTrue(manifestOf(exported(TenantExport.Kind.BACKUP))
                        .contains("\"kind\":\"backup\""),
                "a backup says so");
        assertTrue(manifestOf(exported(TenantExport.Kind.PORTABLE_EXPORT))
                        .contains("\"kind\":\"portable-export\""),
                "and so does an export");
    }

    @Test
    @Timeout(300)
    @DisplayName("#866: restoring a portable export where a backup is required is refused, and "
            + "says why rather than restoring most of an installation")
    void restoringAnExportAsABackupIsRefused() throws Exception {
        byte[] export = exported(TenantExport.Kind.PORTABLE_EXPORT);
        PGSimpleDataSource target = database("archive_kinds_target");
        new PgObjectStore(target, TYPES);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantImport.restoreFidelity(target, DOMAIN,
                        new ByteArrayInputStream(export), OWNER_KEY));

        assertTrue(refused.getMessage().contains("portable-export"), refused.getMessage());
        assertTrue(refused.getMessage().contains("authenticate"),
                "the message says what would have gone wrong, not merely that something did: "
                        + refused.getMessage());

        // and nothing was written on the way to refusing
        try (Connection c = target.getConnection();
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM state.%s_data".formatted(DOMAIN));
             var rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1),
                    "a refusal that had already loaded half the archive would be worse than "
                            + "no check at all");
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("#866: a backup still restores — the check refuses the wrong kind, it does not "
            + "obstruct the right one")
    void aBackupStillRestores() throws Exception {
        byte[] backup = exported(TenantExport.Kind.BACKUP);
        PGSimpleDataSource target = database("archive_kinds_backup_target");
        new PgObjectStore(target, TYPES);

        TenantImport.restoreFidelity(target, DOMAIN,
                new ByteArrayInputStream(backup), OWNER_KEY);

        try (Connection c = target.getConnection();
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM state.%s_data WHERE NOT deleted".formatted(DOMAIN));
             var rs = ps.executeQuery()) {
            rs.next();
            assertEquals(4, rs.getLong(1), "all four objects landed");
        }
    }

    private static byte[] exported(TenantExport.Kind kind) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // a rendering that hands back the payload with its id — enough to
        // exercise the element without pulling a FHIR personality into a test
        // about travel classes
        TenantExport.export(ds, DOMAIN, OWNER_KEY, out, TYPES, kind,
                (payload, id, versionId) -> new String(payload, StandardCharsets.UTF_8)
                        .replaceFirst("\\{", "{\"id\":\"" + id + "\","));
        return out.toByteArray();
    }

    private static TreeSet<String> entriesOf(byte[] sealed) throws Exception {
        TreeSet<String> names = new TreeSet<>();
        try (InputStream plain = SealedArchive.opening(new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String manifestOf(byte[] sealed) throws Exception {
        try (InputStream plain = SealedArchive.opening(new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("no manifest in the archive");
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("ArchiveKindIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            // driven to the expected state: the shared Postgres outlives a run
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource target = new PGSimpleDataSource();
        target.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        target.setUser(SharedPostgres.get().getUsername());
        target.setPassword(SharedPostgres.get().getPassword());
        return target;
    }
}
