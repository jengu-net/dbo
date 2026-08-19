package cloud.jengu.dbo.verify;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveVerification;
import cloud.jengu.dbo.maintenance.SealedArchive;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Checks an archive without asking anyone's permission.
 *
 * <p>A co-signed export is only worth something if the party receiving it can
 * check it themselves — on their own laptop, with no network, no account, and
 * nothing from the vendor but the archive and two public keys. A verifier that
 * phoned home would be asking them to trust the thing under examination.
 *
 * <p><b>It cannot sign.</b> Verification takes public keys only, so this can be
 * handed to an auditor or a regulator as-is: the worst it can do is tell them
 * something does not check out. The one secret it does take is the owner key
 * that opens the seal, and that belongs to the party running it — their key,
 * for their data. A signing key never comes near it.
 */
public final class Verify {

    private Verify() {
    }

    /** What the tool concluded, separate from how it says it. */
    public record Result(boolean verified, String root, String reason) {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 5) {
            err.println("""
                    Verify a sealed archive against the two signatures over it.

                      dbo-verify <archive> <attestation.json> <vendor-public-key> \\
                                 <tenant-public-key> <owner-key>

                      archive             the sealed archive as received
                      attestation.json    the signed root, as it travelled with the archive
                      vendor-public-key   X.509 public key of the party that exported (DER or base64)
                      tenant-public-key   X.509 public key of the tenant whose data it is
                      owner-key           the key that opens the seal — yours, for your data

                    Exit code 0 if it verifies, 1 if it does not, 2 if it could not be read.
                    Nothing here reaches the network.""");
            return 2;
        }
        try {
            Result result = verify(Path.of(args[0]), Path.of(args[1]),
                    Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            if (result.verified()) {
                out.println("VERIFIED");
                out.println();
                out.println("  Both parties signed this archive, and its contents still produce");
                out.println("  the root they signed.");
                out.println();
                out.println("  root: " + result.root());
                return 0;
            }
            out.println("NOT VERIFIED");
            out.println();
            out.println("  " + result.reason());
            out.println();
            out.println("  This archive should not be treated as the one that was exported.");
            return 1;
        } catch (IOException | RuntimeException unreadable) {
            // Distinct from "did not verify": a file that cannot be opened is
            // not evidence of tampering, and saying so would be a false alarm
            // in the direction that gets someone accused.
            err.println("COULD NOT CHECK");
            err.println();
            err.println("  " + unreadable.getMessage());
            err.println();
            err.println("  Nothing is claimed about the archive either way.");
            return 2;
        }
    }

    /**
     * Opens the seal, recomputes the root from the bytes actually present, and
     * checks both signatures over it.
     */
    public static Result verify(Path archive, Path attestationJson, Path vendorPublicKey,
            Path tenantPublicKey, Path ownerKey) throws IOException {
        ArchiveAttestation attestation = ArchiveAttestation.fromJson(
                Files.readString(attestationJson, StandardCharsets.UTF_8));
        byte[] vendor = key(vendorPublicKey);
        byte[] tenant = key(tenantPublicKey);
        byte[] owner = key(ownerKey);

        byte[] plain;
        try (InputStream sealed = Files.newInputStream(archive);
                InputStream opened = SealedArchive.opening(sealed, owner)) {
            plain = opened.readAllBytes();
        } catch (IOException sealBroken) {
            // The seal authenticates its own contents, so a failure here is a
            // FINDING, not an inability to look: something changed after the
            // archive was sealed. Reporting it as "could not check" would
            // understate the strongest detection the tool has — the outer
            // layer caught it before the digests were even reached.
            if (String.valueOf(sealBroken.getMessage()).contains("failed authentication")) {
                return new Result(false, attestation.root(), sealBroken.getMessage()
                        + " (detected by the seal itself, before its contents were examined)");
            }
            throw sealBroken;
        }
        try {
            return new Result(true,
                    ArchiveVerification.verify(plain, attestation, vendor, tenant), null);
        } catch (ArchiveVerification.ArchiveRefusedException refused) {
            // The refusal already names what diverged and where; passing it
            // through unchanged means the tool and the store give the same
            // account of the same archive.
            return new Result(false, attestation.root(), refused.getMessage());
        }
    }

    /**
     * A key file as bytes, base64 or raw. Accepting both spares the person
     * running this a conversation about encodings they did not choose.
     */
    private static byte[] key(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        String text = new String(raw, StandardCharsets.UTF_8).trim()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        if (!text.isEmpty() && text.matches("[A-Za-z0-9+/=]+")) {
            try {
                return Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException notBase64) {
                return raw;
            }
        }
        return raw;
    }
}
