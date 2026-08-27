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
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup covers the tenant, not the domain it was asked
 * about.
 *
 * <p>A tenant is several domains at once — clinical records under the FHIR
 * personality, credentials and signing keys under {@code identity}, the trail
 * under {@code audit}. The export took one domain, so a backup of the clinical
 * domain carried no credentials at all.
 *
 * <p>That failure is silent in the worst way. The restore reports success, the
 * records are all there, and nobody can log in to see them — discovered at
 * sign-in by somebody with no reason to suspect the archive.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupCoversTheTenantIT {

    private static final String CLINICAL = "records";
    private static final String IDENTITY = "keys";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();

    private static final List<TypeRegistration> CLINICAL_TYPES = List.of(
            new TypeRegistration("Note", CLINICAL, IdentityClass.INTERNAL, Set.of(),
                    Handling.operational(), PLAIN, List.of()));
    private static final List<TypeRegistration> IDENTITY_TYPES = List.of(
            new TypeRegistration("Credential", IDENTITY, IdentityClass.INTERNAL, Set.of(),
                    Handling.storeAuthored(), PLAIN, List.of()));

    static PGSimpleDataSource ds;
    static byte[] backup;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        ds = database("backup_whole_tenant");

        // one tenant, two domains — which is what a real one is
        new PgObjectStore(ds, CLINICAL_TYPES).put(PutRequest.create("Note",
                "{\"text\":\"a clinical record\"}".getBytes(StandardCharsets.UTF_8)));
        new PgObjectStore(ds, IDENTITY_TYPES).put(PutRequest.create("Credential",
                "{\"login\":\"albus@hogwarts.scot\"}".getBytes(StandardCharsets.UTF_8)));

        // the backup is asked about the clinical domain, as a caller holding
        // the FHIR registrations naturally would
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, CLINICAL, OWNER_KEY, out, CLINICAL_TYPES,
                TenantExport.Kind.BACKUP);
        backup = out.toByteArray();
    }

    @Test
    @Timeout(300)
    @DisplayName("a backup taken of the clinical domain carries the credentials that live "
            + "in another one")
    @Proving(DboPromises.AUTH_IDENTITY_AS_RECORDS)
    void theBackupCarriesEveryDomain() throws Exception {
        TreeSet<String> entries = entriesOf(backup);

        assertTrue(entries.contains("fidelity/state." + CLINICAL + "_data.csv"),
                "the domain asked about: " + entries);
        assertTrue(entries.contains("fidelity/state." + IDENTITY + "_data.csv"),
                "and the one nobody asked about, which is where the credentials are — a "
                        + "backup without it restores an installation nobody can log in to: "
                        + entries);
        assertTrue(entries.contains("fidelity/history." + IDENTITY + "_history.csv"),
                "with its history, not only its current state");
    }

    @Test
    @Timeout(300)
    @DisplayName("the archive declares which domains it covers, so a reader is not left "
            + "inferring it from the file names")
    void theCoveredDomainsAreDeclared() throws Exception {
        String manifest = manifestOf(backup);

        assertTrue(manifest.contains("\"" + CLINICAL + "\""), manifest);
        assertTrue(manifest.contains("\"" + IDENTITY + "\""), manifest);
        assertTrue(manifest.contains("\"domains\""), manifest);
    }

    @Test
    @Timeout(300)
    @DisplayName("restoring it produces an installation that can authenticate its own "
            + "tenants — the records and the credentials both land")
    @Proving(DboPromises.AUTH_IDENTITY_AS_RECORDS)
    void theRestoreCanAuthenticate() throws Exception {
        PGSimpleDataSource target = database("backup_whole_tenant_target");
        // the target is initialised for both domains, as a real one is
        new PgObjectStore(target, CLINICAL_TYPES);
        new PgObjectStore(target, IDENTITY_TYPES);

        CoSignedArchive.over(backup, OWNER_KEY)
                .restoreFidelityInto(target, CLINICAL, OWNER_KEY);

        assertEquals(1, liveCount(target, CLINICAL), "the clinical record came back");
        assertEquals(1, liveCount(target, IDENTITY),
                "and so did the credential — the assertion that separates a restore from a "
                        + "restore nobody can sign in to");
    }

    @Test
    @Timeout(300)
    @DisplayName("a portable export still covers only what the customer owns — widening "
            + "the backup must not widen the export")
    void theExportDidNotWiden() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(ds, CLINICAL, OWNER_KEY, out, CLINICAL_TYPES,
                TenantExport.Kind.PORTABLE_EXPORT,
                (payload, id, versionId) -> new String(payload, StandardCharsets.UTF_8));
        TreeSet<String> entries = entriesOf(out.toByteArray());

        assertTrue(entries.contains("state/Note.ndjson"), entries.toString());
        assertFalse(entries.stream().anyMatch(e -> e.contains(IDENTITY)),
                "the change that makes a backup whole must not make an export leak: " + entries);
        assertFalse(entries.stream().anyMatch(e -> e.startsWith("fidelity/")),
                "and it carries none of the store's internals: " + entries);
    }

    private static long liveCount(PGSimpleDataSource on, String domain) throws Exception {
        try (Connection c = on.getConnection();
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM state.%s_data WHERE NOT deleted".formatted(domain));
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
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
        throw new IllegalStateException("no manifest");
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("BackupCoversTheTenantIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
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
