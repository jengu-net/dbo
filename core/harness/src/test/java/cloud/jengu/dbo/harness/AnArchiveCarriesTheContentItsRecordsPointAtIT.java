package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.BlobStore;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantExport;
import cloud.jengu.dbo.maintenance.TenantImport;
import cloud.jengu.dbo.postgres.PgBlobStore;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A clinic leaves, and takes the scans with it.
 *
 * <p>The export promises blob content and carried none: it walks resource
 * types, and content held whole is not one. Nothing said so, because the
 * tests that cite the promise ran over tenants that held no blobs — a promise
 * proven over an empty case reads exactly like one proven over a full one,
 * and this is the third time that shape has cost something here.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnArchiveCarriesTheContentItsRecordsPointAtIT {

    private static final String DOMAIN = "clinic";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final EnvelopeExtractor PLAIN = (typeName, payload) -> new Envelope();
    private static final List<TypeRegistration> TYPES = List.of(
            new TypeRegistration("Note", DOMAIN, cloud.jengu.dbo.core.api.IdentityClass.INTERNAL,
                    java.util.Set.of(), cloud.jengu.dbo.core.api.Handling.operational(),
                    PLAIN, List.of()));

    static PGSimpleDataSource source;
    static BlobStore blobs;
    static byte[] scan;
    static String key;

    @BeforeAll
    void up() throws Exception {
        source = database("archive_with_blobs");
        new PgObjectStore(source, TYPES);
        blobs = new PgBlobStore(source);

        // Bytes that are not text: anything that decoded and re-encoded them
        // on the way through the archive corrupts a scan silently.
        scan = new byte[3072];
        new SecureRandom().nextBytes(scan);
        scan[0] = (byte) 0xFF;
        scan[1] = (byte) 0xFE;
        key = blobs.put(scan, "image/tiff");
    }

    @Test
    @DisplayName("a portable export carries the blobs a tenant holds, and an import puts them "
            + "back byte for byte")
    @Proving(DboPromises.MNT_PORTABLE_STATE_EXPORT)
    void theContentTravels() throws Exception {
        byte[] sealed = exported();

        PGSimpleDataSource fresh = database("archive_with_blobs_target");
        ObjectStore target = new PgObjectStore(fresh, TYPES);
        BlobStore restored = new PgBlobStore(fresh);
        assertEquals(0, restored.count(), "the target was not fresh");

        importInto(target, sealed, restored);

        assertEquals(1, restored.count(),
                "the archive carried no blob, so a clinic changing vendor leaves its scans "
                        + "behind and every record pointing at one arrives broken");
        Optional<BlobStore.Blob> back = restored.get(key);
        assertTrue(back.isPresent(), "the blob did not come back under the key it left under");
        assertArrayEquals(scan, back.get().content(),
                "the bytes changed on the way through the archive, so what arrived is not "
                        + "the document that was scanned");
        assertEquals("image/tiff", back.get().media(),
                "the media type did not travel, so the content arrived as something else");
    }

    @Test
    @DisplayName("an archive carrying content refuses to import where there is nowhere to put "
            + "it, rather than restoring the records alone")
    @Proving(DboPromises.MNT_PORTABLE_STATE_EXPORT)
    void aHalfRestoreIsRefused() throws Exception {
        byte[] sealed = exported();
        PGSimpleDataSource fresh = database("archive_with_blobs_nowhere");
        ObjectStore target = new PgObjectStore(fresh, TYPES);

        // Every reference present and the thing they name missing reads as a
        // clean import, which is the failure this whole line of work is about.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> importInto(target, sealed, null));
        assertTrue(refused.getMessage().contains("blob"), refused.getMessage());
    }

    private static void importInto(ObjectStore target, byte[] sealed, BlobStore into)
            throws Exception {
        KeyPair vendor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair tenant = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String root;
        try (InputStream plain = SealedArchive.opening(
                new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            root = rootFrom(zip);
        }
        ArchiveAttestation attestation = ArchiveAttestation.over(root)
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());
        TenantImport.importVerified(target, () -> new ByteArrayInputStream(sealed),
                OWNER_KEY, attestation, vendor.getPublic().getEncoded(),
                tenant.getPublic().getEncoded(), TenantImport.HistoryMode.FRESH,
                accepted -> { }, TenantImport.comparingBytes(), null, into);
    }

    private static byte[] exported() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantExport.export(source, DOMAIN, OWNER_KEY, out, TYPES,
                TenantExport.Kind.PORTABLE_EXPORT,
                (payload, id, versionId) ->
                        new String(payload, java.nio.charset.StandardCharsets.UTF_8), null);
        return out.toByteArray();
    }

    private static String rootFrom(ZipInputStream zip) throws Exception {
        java.util.zip.ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            // The digest list, not the archive's own manifest: the root is
            // over the entries, and signing anything else signs nothing.
            if ("digests.json".equals(entry.getName())) {
                String json = new String(zip.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                int at = json.indexOf("\"root\":\"") + "\"root\":\"".length();
                return json.substring(at, json.indexOf('"', at));
            }
        }
        throw new AssertionError("the archive has no digest list");
    }

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("AnArchiveCarriesTheContentItsRecordsPointAtIT");
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
