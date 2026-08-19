package cloud.jengu.dbo.verify;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveManifest;
import cloud.jengu.dbo.maintenance.SealedArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verifier a customer runs on their own laptop.
 *
 * <p>Every case here is one an IT director could actually meet: the archive is
 * fine, someone changed a file, one of the two signatures is missing. What the
 * tool must never do is say "verified" about any of the last two, and it must
 * say which of the three it found.
 */
class VerifyTest {

    private static final byte[] OWNER_KEY =
            "an-owner-master-key-32-bytes-!!!".getBytes(StandardCharsets.UTF_8);

    private KeyPair vendor;
    private KeyPair tenant;
    private Path dir;

    @BeforeEach
    void keys(@TempDir Path tmp) throws Exception {
        this.dir = tmp;
        KeyPairGenerator ed25519 = KeyPairGenerator.getInstance("Ed25519");
        vendor = ed25519.generateKeyPair();
        tenant = ed25519.generateKeyPair();
        Files.write(dir.resolve("vendor.pub"), vendor.getPublic().getEncoded());
        Files.write(dir.resolve("tenant.pub"), tenant.getPublic().getEncoded());
        Files.write(dir.resolve("owner.key"), OWNER_KEY);
    }

    /** An archive shaped like the real thing: NDJSON entries plus the digest list. */
    private byte[] plainArchive(String patientLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("Patient.ndjson"));
            zip.write(patientLine.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Observation.ndjson"));
            zip.write("{\"resourceType\":\"Observation\",\"id\":\"o1\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] withoutManifest = out.toByteArray();
        ArchiveManifest manifest = ArchiveManifest.of(withoutManifest);

        ByteArrayOutputStream complete = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(complete)) {
            copyEntries(withoutManifest, zip);
            zip.putNextEntry(new ZipEntry(ArchiveManifest.MANIFEST_ENTRY));
            zip.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return complete.toByteArray();
    }

    private static void copyEntries(byte[] source, ZipOutputStream into) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                into.putNextEntry(new ZipEntry(entry.getName()));
                into.write(zip.readAllBytes());
                into.closeEntry();
            }
        }
    }

    private Path seal(byte[] plain, String name) throws Exception {
        Path sealed = dir.resolve(name);
        try (OutputStream file = Files.newOutputStream(sealed);
                OutputStream sealing = SealedArchive.sealing(OWNER_KEY, file)) {
            sealing.write(plain);
        }
        return sealed;
    }

    private Path attestation(byte[] plain, String name, boolean withTenant) throws Exception {
        ArchiveAttestation signed = ArchiveAttestation.over(ArchiveManifest.of(plain).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded());
        if (withTenant) {
            signed = signed.signedBy(ArchiveAttestation.Party.TENANT,
                    tenant.getPrivate().getEncoded());
        }
        Path path = dir.resolve(name);
        Files.writeString(path, signed.toJson());
        return path;
    }

    private record Run(int code, String out, String err) {}

    private Run run(Path archive, Path attestation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Verify.run(new String[]{
                archive.toString(), attestation.toString(),
                dir.resolve("vendor.pub").toString(), dir.resolve("tenant.pub").toString(),
                dir.resolve("owner.key").toString()},
                new PrintStream(out), new PrintStream(err));
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    @DisplayName("an archive both parties signed, unaltered, verifies — and says what root it checked")
    void anIntactArchiveVerifies() throws Exception {
        byte[] plain = plainArchive("{\"resourceType\":\"Patient\",\"id\":\"p1\"}\n");
        Run result = run(seal(plain, "archive.sealed"), attestation(plain, "att.json", true));

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.out())
                .contains("VERIFIED")
                .doesNotContain("NOT VERIFIED");
        assertThat(result.out())
                .as("the root belongs in the output: it is what an auditor records and compares "
                        + "against the copy the customer was given separately")
                .contains(ArchiveManifest.of(plain).root());
    }

    @Test
    @DisplayName("a file changed after export fails, and the output names the file rather than saying 'invalid'")
    void anAlteredFileFailsAndNamesIt() throws Exception {
        byte[] exported = plainArchive("{\"resourceType\":\"Patient\",\"id\":\"p1\"}\n");
        Path attestation = attestation(exported, "att.json", true);
        // Same manifest, different bytes — what tampering actually looks like.
        byte[] altered = plainArchiveWithManifestFrom(exported,
                "{\"resourceType\":\"Patient\",\"id\":\"p1\",\"active\":false}\n");

        Run result = run(seal(altered, "altered.sealed"), attestation);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("NOT VERIFIED");
        assertThat(result.out())
                .as("'this archive is invalid' sends someone hunting; naming the file is the "
                        + "difference between a report and a search")
                .contains("Patient.ndjson");
    }

    @Test
    @DisplayName("an archive with only the vendor's signature is refused — one party signing alone is the thing this exists to catch")
    void aMissingTenantSignatureIsRefused() throws Exception {
        byte[] plain = plainArchive("{\"resourceType\":\"Patient\",\"id\":\"p1\"}\n");
        Run result = run(seal(plain, "archive.sealed"), attestation(plain, "att.json", false));

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("NOT VERIFIED");
        assertThat(result.out().toLowerCase())
                .as("which signature is missing is the whole answer — vendor-only means we "
                        + "produced it alone")
                .contains("tenant");
    }

    @Test
    @DisplayName("a file it cannot read is reported as unchecked, never as tampering")
    void anUnreadableFileIsNotAnAccusation() {
        Run result = run(dir.resolve("no-such-archive"), dir.resolve("no-such-attestation"));

        assertThat(result.code())
                .as("distinct from 1: a missing file is not evidence against anybody")
                .isEqualTo(2);
        assertThat(result.err()).contains("COULD NOT CHECK");
        assertThat(result.out()).doesNotContain("NOT VERIFIED");
    }

    @Test
    @DisplayName("run with no arguments it explains itself, and says it never reaches the network")
    void itExplainsItself() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Verify.run(new String[0], new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err));

        assertThat(code).isEqualTo(2);
        assertThat(err.toString())
                .contains("dbo-verify")
                .as("the person running this needs to know it phones nobody — that is the "
                        + "property that makes it worth running")
                .contains("network");
    }

    @Test
    @DisplayName("a byte changed inside the sealed container is reported as tampering, not as a file it could not read")
    void aBrokenSealIsAFindingNotAnExcuse() throws Exception {
        byte[] plain = plainArchive("{\"resourceType\":\"Patient\",\"id\":\"p1\"}\n");
        Path sealed = seal(plain, "archive.sealed");
        Path att = attestation(plain, "att.json", true);
        byte[] bytes = Files.readAllBytes(sealed);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(sealed, bytes);

        Run result = run(sealed, att);

        assertThat(result.code())
                .as("the seal authenticates its own contents, so this is the strongest detection "
                        + "the tool has — calling it 'could not check' would file the clearest "
                        + "evidence of tampering under 'nothing is claimed'")
                .isEqualTo(1);
        assertThat(result.out()).contains("NOT VERIFIED");
        assertThat(result.err()).doesNotContain("COULD NOT CHECK");
    }

    /** An archive whose contents changed but whose manifest still says otherwise. */
    private byte[] plainArchiveWithManifestFrom(byte[] original, String newPatientLine)
            throws Exception {
        String manifestJson;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original))) {
            ZipEntry entry;
            String found = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (ArchiveManifest.MANIFEST_ENTRY.equals(entry.getName())) {
                    found = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            manifestJson = found;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("Patient.ndjson"));
            zip.write(newPatientLine.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Observation.ndjson"));
            zip.write("{\"resourceType\":\"Observation\",\"id\":\"o1\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(ArchiveManifest.MANIFEST_ENTRY));
            zip.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
