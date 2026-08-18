package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveManifest;
import cloud.jengu.dbo.maintenance.ArchiveVerification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An archive is attested by both parties, and an import refuses
 * anything less (ADR 0052).
 *
 * <p>Each refusal is proven on its own. "Refuses a bad archive" is one
 * sentence and several distinct failures — altered content, an archive that
 * grew an entry, a missing countersignature, a signature from the wrong key —
 * and a test that only covers the first would leave the others as beliefs.
 */
class ArchiveAttestationIT {

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] archive(String... namesAndBodies) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBodies[i]));
                zip.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** An archive carrying its own manifest, as an export would produce. */
    private static byte[] attested(String... namesAndBodies) throws Exception {
        byte[] plain = archive(namesAndBodies);
        ArchiveManifest manifest = ArchiveManifest.of(plain);
        String[] withManifest = new String[namesAndBodies.length + 2];
        System.arraycopy(namesAndBodies, 0, withManifest, 0, namesAndBodies.length);
        withManifest[namesAndBodies.length] = ArchiveManifest.MANIFEST_ENTRY;
        withManifest[namesAndBodies.length + 1] = manifest.toJson();
        return archive(withManifest);
    }

    @Test
    @DisplayName("an archive both parties signed verifies, and yields the root the "
            + "destination records")
    void aCoSignedArchiveVerifies() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        byte[] plain = attested("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");

        ArchiveAttestation attestation = ArchiveAttestation.over(ArchiveManifest.of(plain).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());

        String accepted = ArchiveVerification.verify(plain, attestation,
                vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded());

        assertEquals(ArchiveManifest.of(plain).root(), accepted,
                "the destination records what both parties said this was");
    }

    @Test
    @DisplayName("a resource altered after export is refused, and the refusal names the file")
    void alteredContentIsRefused() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        byte[] original = attested("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");
        ArchiveAttestation attestation = ArchiveAttestation.over(ArchiveManifest.of(original).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());

        // the manifest still describes the original; the file no longer matches it
        byte[] tampered = archive(
                "state/Patient.ndjson", "{\"resourceType\":\"Patient\",\"deceasedBoolean\":true}\n",
                ArchiveManifest.MANIFEST_ENTRY, manifestOf(original));

        ArchiveVerification.ArchiveRefusedException refused = assertThrows(
                ArchiveVerification.ArchiveRefusedException.class,
                () -> ArchiveVerification.verify(tampered, attestation,
                        vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded()));

        assertTrue(refused.getMessage().contains("state/Patient.ndjson"), refused.getMessage());
    }

    @Test
    @DisplayName("an archive that grew an entry after export is refused")
    void anAddedEntryIsRefused() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        byte[] original = attested("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");
        ArchiveAttestation attestation = ArchiveAttestation.over(ArchiveManifest.of(original).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());

        // every listed file still matches — the smuggled one simply is not listed
        byte[] grown = archive(
                "state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n",
                "state/Observation.ndjson", "{\"resourceType\":\"Observation\"}\n",
                ArchiveManifest.MANIFEST_ENTRY, manifestOf(original));

        assertThrows(ArchiveVerification.ArchiveRefusedException.class,
                () -> ArchiveVerification.verify(grown, attestation,
                        vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded()),
                "an archive may not carry what nobody attested");
    }

    @Test
    @DisplayName("an archive the tenant has not countersigned is refused, and says so")
    void aMissingCountersignatureIsRefused() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        byte[] plain = attested("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");

        // we signed it; the tenant never saw it
        ArchiveAttestation ours = ArchiveAttestation.over(ArchiveManifest.of(plain).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded());

        ArchiveVerification.ArchiveRefusedException refused = assertThrows(
                ArchiveVerification.ArchiveRefusedException.class,
                () -> ArchiveVerification.verify(plain, ours,
                        vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded()));

        assertTrue(refused.getMessage().contains("tenant"), refused.getMessage());
    }

    @Test
    @DisplayName("a signature from the wrong key is refused — holding one half is not enough")
    void aForgedCountersignatureIsRefused() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        KeyPair impostor = ed25519();
        byte[] plain = attested("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");

        // we hold our key and try to supply the tenant's half ourselves
        ArchiveAttestation forged = ArchiveAttestation.over(ArchiveManifest.of(plain).root())
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, impostor.getPrivate().getEncoded());

        assertThrows(ArchiveVerification.ArchiveRefusedException.class,
                () -> ArchiveVerification.verify(plain, forged,
                        vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded()),
                "co-signing is only co-signing while the second key is out of our reach");
    }

    @Test
    @DisplayName("the root is over contents, not bytes — re-packing does not invalidate it")
    void rePackingDoesNotInvalidateTheAttestation() throws Exception {
        byte[] first = archive("b.ndjson", "two\n", "a.ndjson", "one\n");
        byte[] reordered = archive("a.ndjson", "one\n", "b.ndjson", "two\n");

        assertEquals(ArchiveManifest.of(first).root(), ArchiveManifest.of(reordered).root(),
                "entry order is not a promise anybody made");
        assertNotEquals(ArchiveManifest.of(first).root(),
                ArchiveManifest.of(archive("a.ndjson", "one\n", "b.ndjson", "three\n")).root(),
                "but changed contents must change the root");
    }

    @Test
    @DisplayName("an archive with no manifest is refused rather than trusted")
    void anUnattestedArchiveIsRefused() throws Exception {
        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        byte[] bare = archive("state/Patient.ndjson", "{\"resourceType\":\"Patient\"}\n");

        assertFalse(new String(bare, StandardCharsets.ISO_8859_1).contains("manifest.json"));
        assertThrows(ArchiveVerification.ArchiveRefusedException.class,
                () -> ArchiveVerification.verify(bare,
                        ArchiveAttestation.over("whatever"),
                        vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded()));
    }

    private static String manifestOf(byte[] plainArchive) throws Exception {
        return ArchiveManifest.of(plainArchive).toJson();
    }
}
