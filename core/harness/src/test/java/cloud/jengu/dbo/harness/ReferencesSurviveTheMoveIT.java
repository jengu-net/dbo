package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.maintenance.ReferenceClosure;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record that points at another still points at the same
 * thing after the data has moved.
 *
 * <p>Proven with the closure report rather than by inspecting rows:
 * "the references resolve" is exactly the question that report answers, and
 * asking it is a stronger check than any assertion written here would be.
 *
 * <p>The referencing type is named so it sorts <b>before</b> its target, since
 * the archive is one file per type in name order. The import therefore meets
 * every reference before the thing it refers to — which is the ordering a
 * topological sort would have forbidden, and which must simply not matter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferencesSurviveTheMoveIT {

    private static final String DOMAIN = "moved";
    private static final byte[] OWNER_KEY = new byte[32];
    private static final String CODE_SYSTEM = "urn:test:code";

    private static final EnvelopeExtractor EXTRACTOR = (typeName, payload) -> {
        Envelope envelope = new Envelope();
        String json = new String(payload, StandardCharsets.UTF_8);
        int ref = json.indexOf("\"ref\":\"");
        if (ref >= 0) {
            String value = json.substring(ref + 7, json.indexOf('"', ref + 7));
            int slash = value.indexOf('/');
            envelope.reference("subject", value.substring(0, slash), value.substring(slash + 1));
        }
        int code = json.indexOf("\"code\":\"");
        if (code >= 0) {
            envelope.identifier(CODE_SYSTEM, json.substring(code + 8, json.indexOf('"', code + 8)));
        }
        return envelope;
    };

    private static final List<TypeRegistration> TYPES = List.of(
            // "Alpha" sorts before "Zulu": the referrer travels first
            new TypeRegistration("Alpha", DOMAIN, IdentityClass.INTERNAL, Set.of(),
                    Handling.operational(), EXTRACTOR, List.of()),
            new TypeRegistration("Zulu", DOMAIN, IdentityClass.IDENTIFIER,
                    Set.of(CODE_SYSTEM), Handling.operational(), EXTRACTOR, List.of()));

    static PGSimpleDataSource sourceDs;
    static PGSimpleDataSource destinationDs;
    static PgObjectStore source;
    static String subjectId;

    private static PGSimpleDataSource database(String name) throws Exception {
        String jdbcUrl = SharedPostgres.urlFor("ReferencesSurviveTheMoveIT");
        try (Connection c = DriverManager.getConnection(jdbcUrl,
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name);
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return ds;
    }

    @BeforeAll
    void up() throws Exception {
        new SecureRandom().nextBytes(OWNER_KEY);
        sourceDs = database("moved_source");
        destinationDs = database("moved_destination");
        source = new PgObjectStore(sourceDs, TYPES);

        PutResult subject = source.put(PutRequest.create("Zulu",
                "{\"code\":\"SUBJ-1\",\"who\":\"the referenced thing\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        subjectId = subject.id();

        PutResult referrer = source.put(PutRequest.create("Alpha",
                ("{\"ref\":\"Zulu/" + subjectId + "\",\"note\":\"first\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        // a second version, so the reference also exists in history
        source.put(new PutRequest("Alpha", referrer.id(), null,
                ("{\"ref\":\"Zulu/" + subjectId + "\",\"note\":\"second\"}")
                        .getBytes(StandardCharsets.UTF_8)));

        assertTrue(ReferenceClosure.check(sourceDs, DOMAIN, Set.of()).isWhole(),
                "the source must start whole, or the move proves nothing");
    }

    @Test
    @Timeout(300)
    @DisplayName("after a move every reference still resolves, though the referrer arrived first")
    void referencesResolveAfterTheMove() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(sourceDs, DOMAIN, OWNER_KEY, archive, TYPES);

        PgObjectStore destination = new PgObjectStore(destinationDs, TYPES);
        CoSignedArchive.over(archive.toByteArray(), OWNER_KEY)
                .importInto(destination, OWNER_KEY, TenantImport.HistoryMode.FRESH);

        ReferenceClosure.Report report = ReferenceClosure.check(destinationDs, DOMAIN, Set.of());

        assertTrue(report.referencesChecked() > 0, "a check of nothing proves nothing");
        assertTrue(report.isWhole(), report.describe());
    }

    @Test
    @Timeout(300)
    @DisplayName("the archive says which thing each object is, without anyone resolving our ids")
    void theArchiveCarriesIdentityCodes() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(sourceDs, DOMAIN, OWNER_KEY, archive, TYPES);

        String zulu = null;
        try (InputStream plain = SealedArchive.opening(
                new ByteArrayInputStream(archive.toByteArray()), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("state/Zulu.ndjson")) {
                    zulu = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        assertTrue(zulu != null && zulu.contains("\"ic\":[\"" + CODE_SYSTEM + "|SUBJ-1\"]"),
                "the identity code must travel beside the object: " + zulu);
        // and the payload itself is untouched: the round trip must return the
        // same bytes, because those bytes are what the version chain and both
        // signatures are over. An assertion on the archive text would only
        // restate the line above; this asks the destination.
        PgObjectStore roundTripped = new PgObjectStore(destinationDs, TYPES);
        CoSignedArchive.over(archive.toByteArray(), OWNER_KEY)
                .importInto(roundTripped, OWNER_KEY, TenantImport.HistoryMode.FRESH);
        assertArrayEquals(
                source.get("Zulu", subjectId).orElseThrow().payload(),
                roundTripped.get("Zulu", subjectId).orElseThrow().payload(),
                "the moved payload differs from the original — the chain over it no longer "
                        + "verifies, and neither signature means anything");
    }

    @Test
    @Timeout(300)
    @DisplayName("a reference recorded in an older version still resolves after the move")
    void historicalReferencesResolve() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        TenantExport.export(sourceDs, DOMAIN, OWNER_KEY, archive, TYPES);

        PGSimpleDataSource historyDs = database("moved_history");
        PgObjectStore destination = new PgObjectStore(historyDs, TYPES);
        TenantImport.ArchiveSource source = () ->
                new ByteArrayInputStream(archive.toByteArray());

        java.security.KeyPair vendor = java.security.KeyPairGenerator
                .getInstance("Ed25519").generateKeyPair();
        java.security.KeyPair tenant = java.security.KeyPairGenerator
                .getInstance("Ed25519").generateKeyPair();
        String root = rootOf(archive.toByteArray());
        var attestation = cloud.jengu.dbo.maintenance.ArchiveAttestation.over(root)
                .signedBy(cloud.jengu.dbo.maintenance.ArchiveAttestation.Party.VENDOR,
                        vendor.getPrivate().getEncoded())
                .signedBy(cloud.jengu.dbo.maintenance.ArchiveAttestation.Party.TENANT,
                        tenant.getPrivate().getEncoded());

        TenantImport.importVerified(destination, source, OWNER_KEY, attestation,
                vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded(),
                TenantImport.HistoryMode.PRESERVED);

        // the moved object kept its own version, and the target it pointed at
        // from that version is still there under the same identity
        assertEquals(2, destination.get("Alpha", alphaId(historyDs)).orElseThrow().versionId(),
                "the history moved with its own version numbers");
        assertTrue(ReferenceClosure.check(historyDs, DOMAIN, Set.of()).isWhole());
        assertTrue(destination.get("Zulu", subjectId).isPresent(),
                "the referenced object kept the id its history refers to");
    }

    private static String alphaId(PGSimpleDataSource ds) throws Exception {
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                     "SELECT id::text FROM state." + DOMAIN + "_data WHERE type = 'Alpha'");
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String rootOf(byte[] sealed) throws Exception {
        try (InputStream plain = SealedArchive.opening(new ByteArrayInputStream(sealed), OWNER_KEY);
             ZipInputStream zip = new ZipInputStream(plain)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("digests.json".equals(entry.getName())) {
                    String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    int at = json.indexOf("\"root\":\"") + "\"root\":\"".length();
                    return json.substring(at, json.indexOf('"', at));
                }
            }
        }
        throw new IllegalStateException("no digest list");
    }
}
