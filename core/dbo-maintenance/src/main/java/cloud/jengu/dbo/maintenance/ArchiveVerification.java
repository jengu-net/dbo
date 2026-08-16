package cloud.jengu.dbo.maintenance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks an archive against what both parties attested, before a single row
 * of it is written (#34, ADR 0052 §5).
 *
 * <p>Refusal is whole. A partially-applied archive is worse than a rejected
 * one: the tenant is left in a state neither party attested, and nobody can
 * say which half is which.
 */
public final class ArchiveVerification {

    private ArchiveVerification() {
    }

    /**
     * @param plainArchive     the opened (unsealed) archive
     * @param attestation      the detached attestation that travelled with it
     * @param vendorPublicKey  X.509 public key of the party that exported
     * @param tenantPublicKey  X.509 public key of the tenant whose data it is
     * @return the root both parties signed — recorded by the caller, so that
     *         "what did we import, and what did both parties say it was" is
     *         answerable later without the archive
     * @throws ArchiveRefusedException when anything fails to line up
     */
    public static String verify(byte[] plainArchive, ArchiveAttestation attestation,
            byte[] vendorPublicKey, byte[] tenantPublicKey) throws IOException {
        String manifestJson = entry(plainArchive, ArchiveManifest.MANIFEST_ENTRY);
        if (manifestJson == null) {
            throw new ArchiveRefusedException("the archive carries no manifest — nothing in it "
                    + "was attested, so nothing in it can be trusted");
        }

        // Recompute from the bytes actually present, never from what the
        // manifest says about itself.
        ArchiveManifest recomputed = ArchiveManifest.of(plainArchive);
        for (ArchiveManifest.Entry declared : parseEntries(manifestJson)) {
            String actual = recomputed.entries().stream()
                    .filter(e -> e.name().equals(declared.name()))
                    .map(ArchiveManifest.Entry::sha256)
                    .findFirst().orElse(null);
            if (actual == null) {
                throw new ArchiveRefusedException("the archive is missing " + declared.name()
                        + ", which the manifest says it contains");
            }
            if (!actual.equals(declared.sha256())) {
                throw new ArchiveRefusedException(declared.name()
                        + " does not match the manifest — its contents changed after export");
            }
        }
        if (recomputed.entries().size() != parseEntries(manifestJson).size()) {
            throw new ArchiveRefusedException("the archive carries entries the manifest does not "
                    + "list — something was added after export");
        }
        if (!recomputed.root().equals(attestation.root())) {
            throw new ArchiveRefusedException("the archive's contents do not produce the root "
                    + "that was signed");
        }

        // Both, always. One signature proves the archive was produced; two
        // prove it was not produced by one party alone, which is the only
        // property worth having here.
        if (!attestation.hasSignature(ArchiveAttestation.Party.VENDOR)) {
            throw new ArchiveRefusedException("no vendor signature — refusing an archive nobody "
                    + "admits to producing");
        }
        if (!attestation.hasSignature(ArchiveAttestation.Party.TENANT)) {
            throw new ArchiveRefusedException("no tenant countersignature — refusing an archive "
                    + "the tenant has not seen");
        }
        if (!attestation.verifiedBy(ArchiveAttestation.Party.VENDOR, vendorPublicKey)) {
            throw new ArchiveRefusedException("the vendor signature does not verify");
        }
        if (!attestation.verifiedBy(ArchiveAttestation.Party.TENANT, tenantPublicKey)) {
            throw new ArchiveRefusedException("the tenant signature does not verify");
        }
        return attestation.root();
    }

    private static java.util.List<ArchiveManifest.Entry> parseEntries(String manifestJson) {
        java.util.List<ArchiveManifest.Entry> entries = new java.util.ArrayList<>();
        int at = 0;
        while ((at = manifestJson.indexOf("{\"name\":\"", at)) >= 0) {
            int nameStart = at + "{\"name\":\"".length();
            int nameEnd = manifestJson.indexOf('"', nameStart);
            int digestStart = manifestJson.indexOf("\"sha256\":\"", nameEnd) + "\"sha256\":\"".length();
            int digestEnd = manifestJson.indexOf('"', digestStart);
            entries.add(new ArchiveManifest.Entry(manifestJson.substring(nameStart, nameEnd),
                    manifestJson.substring(digestStart, digestEnd)));
            at = digestEnd;
        }
        return entries;
    }

    private static String entry(byte[] plainArchive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plainArchive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** The archive was not imported, and this says why. */
    public static class ArchiveRefusedException extends RuntimeException {
        public ArchiveRefusedException(String message) {
            super("archive refused: " + message);
        }
    }
}
