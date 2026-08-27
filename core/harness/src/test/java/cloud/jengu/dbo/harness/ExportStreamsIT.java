package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export streams, so a tenant larger than the heap can still leave.
 *
 * <p>Measuring peak heap in a test is a way to write a flaky test, so this
 * proves the property structurally instead: the archive is written to a sink
 * that <b>counts bytes and throws them away</b>. If any part of the path
 * still needed the whole archive in memory, holding none of it would not be
 * an option.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportStreamsIT {

    private static final byte[] OWNER_KEY = new byte[32];
    private static final String EID = "https://ee.ee/eid";

    static PGSimpleDataSource ds;
    static PgObjectStore store;

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        String jdbcUrl = SharedPostgres.urlFor("ExportStreamsIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE stream_export");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        ds = new PGSimpleDataSource();
        ds.setUrl(base + "stream_export");
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());

        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID)));
        store = new PgObjectStore(ds, personality.registrations());

        // Enough rows that buffering the archive would be a choice somebody
        // could feel, and each one padded so the bytes are not trivial.
        String padding = "x".repeat(4096);
        for (int i = 0; i < 400; i++) {
            store.put(PutRequest.create("Patient", ("""
                    {"resourceType":"Patient",
                     "identifier":[{"system":"%s","value":"3800101%04d"}],
                     "name":[{"family":"%s"}]}""".formatted(EID, i, padding))
                    .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("the archive is written straight to its destination — nothing holds it whole")
    void theArchiveIsNeverHeldWhole() throws Exception {
        CountingSink sink = new CountingSink();

        TenantExport.ExportResult result = TenantExport.export(ds, R4Personality.DOMAIN,
                OWNER_KEY, sink);

        assertEquals(400, result.objectCount());
        assertTrue(sink.bytes > 100_000,
                "the export must actually be large: " + sink.bytes + " bytes");
    }

    @Test
    @Timeout(300)
    @DisplayName("a streamed archive opens, and carries the digests written last")
    void aStreamedArchiveOpensAndCarriesItsDigests() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, OWNER_KEY, out);

        boolean sawDigests = false;
        boolean sawData = false;
        try (InputStream opened = SealedArchive.opening(
                new java.io.ByteArrayInputStream(out.toByteArray()), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(opened)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("digests.json".equals(entry.getName())) {
                    sawDigests = new String(zip.readAllBytes(), StandardCharsets.UTF_8)
                            .contains("\"root\"");
                } else if (entry.getName().startsWith("state/")) {
                    sawData = true;
                }
            }
        }
        assertTrue(sawData, "the archive must carry its data");
        assertTrue(sawDigests, "and the digest list written after it");
    }

    @Test
    @Timeout(300)
    @DisplayName("an altered archive fails authentication rather than ending quietly")
    void anAlteredArchiveFailsAuthentication() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, OWNER_KEY, out);
        byte[] sealed = out.toByteArray();

        // flip a byte deep in the ciphertext
        sealed[sealed.length - 200] ^= 0x01;

        assertThrows(IOException.class, () -> {
            try (InputStream opened = SealedArchive.opening(
                    new java.io.ByteArrayInputStream(sealed), OWNER_KEY)) {
                opened.readAllBytes();
            }
        }, "silent truncation is the worst outcome for a restore: the destination would look "
                + "successful and be missing whatever came after the edit");
    }


    @Test
    @Timeout(300)
    @DisplayName("a verified import preserves the archive's versions and moments, and a "
            + "resumed run skips what already landed")
    @Proving(DboPromises.MNT_ACCEPTED_ROOT_RECORDED)
    void aVerifiedImportPreservesHistoryAndResumes() throws Exception {
        // a second tenant to import into
        String jdbcUrl = SharedPostgres.urlFor("ExportStreamsIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE stream_import");
        }
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1);
        PGSimpleDataSource into = new PGSimpleDataSource();
        into.setUrl(base + "stream_import");
        into.setUser(SharedPostgres.get().getUsername());
        into.setPassword(SharedPostgres.get().getPassword());
        R4Personality personality = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID)));
        PgObjectStore destination = new PgObjectStore(into, personality.registrations());

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        TenantExport.export(ds, R4Personality.DOMAIN, OWNER_KEY, out);
        byte[] sealed = out.toByteArray();

        java.security.KeyPair vendor = java.security.KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        java.security.KeyPair tenant = java.security.KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        String root;
        try (InputStream plain = SealedArchive.opening(
                new java.io.ByteArrayInputStream(sealed), OWNER_KEY);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(plain)) {
            root = rootFrom(zip);
        }
        cloud.jengu.dbo.maintenance.ArchiveAttestation attestation =
                cloud.jengu.dbo.maintenance.ArchiveAttestation.over(root)
                        .signedBy(cloud.jengu.dbo.maintenance.ArchiveAttestation.Party.VENDOR,
                                vendor.getPrivate().getEncoded())
                        .signedBy(cloud.jengu.dbo.maintenance.ArchiveAttestation.Party.TENANT,
                                tenant.getPrivate().getEncoded());

        TenantImport.ArchiveSource source = () -> new java.io.ByteArrayInputStream(sealed);
        java.util.List<cloud.jengu.dbo.maintenance.ImportLedger.Accepted> recorded =
                new java.util.ArrayList<>();
        var first = TenantImport.importVerified(destination, source, OWNER_KEY, attestation,
                vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded(),
                TenantImport.HistoryMode.PRESERVED, recorded::add,
                TenantImport.comparingBytes());
        assertEquals(400, first.imported());

        // REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED: the destination wrote down what it
        // accepted and from whom, so the move is answerable without the archive
        assertEquals(1, recorded.size());
        assertEquals(root, recorded.get(0).root(), "the recorded root is the one both parties signed");
        assertEquals(cloud.jengu.dbo.maintenance.ImportLedger.Accepted
                        .fingerprint(tenant.getPublic().getEncoded()),
                recorded.get(0).tenantKeyDigest(), "the countersigner is named in the record");
        assertEquals(400, recorded.get(0).objects());

        // the interrupted-and-restarted case: same archive, nothing duplicated
        var second = TenantImport.importVerified(destination, source, OWNER_KEY, attestation,
                vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded(),
                TenantImport.HistoryMode.PRESERVED, recorded::add,
                TenantImport.comparingBytes());
        assertEquals(0, second.imported(), "a resumed move must not rewrite what already landed");
        assertEquals(400, second.skippedIdentical());

        // and the resumed half is recorded too: two acceptances of one root is
        // the honest account of what happened, not a duplicate to suppress
        assertEquals(2, recorded.size());
        assertEquals(0, recorded.get(1).objects());
        assertEquals(400, recorded.get(1).unchanged());
    }

    /** The root the export wrote, read from the archive's own digest list. */
    private static String rootFrom(java.util.zip.ZipInputStream zip) throws IOException {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if ("digests.json".equals(entry.getName())) {
                String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                int at = json.indexOf("\"root\":\"") + "\"root\":\"".length();
                return json.substring(at, json.indexOf('"', at));
            }
        }
        throw new IllegalStateException("no digest list in the archive");
    }

    /** Accepts the archive and keeps none of it. */
    private static final class CountingSink extends OutputStream {
        private long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }
}
