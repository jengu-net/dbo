package cloud.jengu.dbo.core.api.seal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.XECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * A participant's identity, kept in a directory in a form this store
 * promises, so a process beside it — one not on the JVM, with standard
 * tools — can make the identity before the store first runs, and sign as
 * the participant while the store is not running.
 *
 * <p><b>Version 1</b>, as {@code docs/arc42-008-crosscutting/processes-and-work/participant-keys.md}
 * documents it:
 * <pre>
 * &lt;dir&gt;/FORMAT        dbo-participant-keys/1
 * &lt;dir&gt;/sealing.pem   X25519 private key, PKCS#8 PEM
 * &lt;dir&gt;/signing.pem   Ed25519 private key, PKCS#8 PEM
 * </pre>
 * What {@code openssl genpkey} writes, so a shell makes it with no encoding
 * of its own. No public halves are kept: they are derived from the private
 * ones and can never disagree with them.
 *
 * <p><b>Adopted, or made where there is none.</b> Whichever side starts first
 * makes the identity and the other adopts it. A directory that holds part of
 * one, or one in a form this store does not know, is refused by name: made
 * over, it would be an identity silently replaced.
 *
 * @param sealing    the private half that opens what is sealed to the participant
 * @param signing    the private half the participant signs with
 * @param sealingKey the public half others seal to
 * @param signingKey the public half others verify with
 */
public record ParticipantKeys(PrivateKey sealing, PrivateKey signing, ParticipantKey sealingKey,
        SigningKey signingKey) {

    /** The form this store reads and writes, said in the directory's FORMAT file. */
    public static final String FORMAT = "dbo-participant-keys/1";

    private static final String FORMAT_FILE = "FORMAT";
    private static final String SEALING_FILE = "sealing.pem";
    private static final String SIGNING_FILE = "signing.pem";

    /**
     * The identity kept in that directory, made there first if it holds none.
     *
     * @throws IllegalStateException naming what is wrong, for a directory
     *         holding part of an identity or one in a form this store does
     *         not know
     */
    public static ParticipantKeys adoptOrMake(Path dir) {
        try {
            if (!Files.isDirectory(dir) || isEmpty(dir)) {
                make(dir);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("a participant's identity could not be made in "
                    + dir, failed);
        }
        return adopt(dir);
    }

    /**
     * The identity kept in that directory, which must be whole.
     *
     * @throws IllegalStateException naming what is wrong
     */
    public static ParticipantKeys adopt(Path dir) {
        Path format = dir.resolve(FORMAT_FILE);
        if (!Files.isRegularFile(format)) {
            throw new IllegalStateException(dir + " holds no " + FORMAT_FILE + " file, so it is "
                    + "not a participant's identity in a form this store knows — and, holding "
                    + "something, it is not made over");
        }
        String said;
        try {
            said = Files.readString(format, StandardCharsets.UTF_8).strip();
        } catch (IOException unreadable) {
            throw new IllegalStateException(format + " could not be read", unreadable);
        }
        if (!FORMAT.equals(said)) {
            throw new IllegalStateException(dir + " is a participant's identity in the form '"
                    + said + "', and this store reads '" + FORMAT + "'");
        }
        PrivateKey sealing = privateKey(dir.resolve(SEALING_FILE), "X25519");
        PrivateKey signing = privateKey(dir.resolve(SIGNING_FILE), "Ed25519");
        return new ParticipantKeys(sealing, signing,
                ParticipantKey.of(publicOf(sealing)), SigningKey.of(publicOf(signing)));
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Both keys, then the FORMAT that says the directory is whole: a directory without it is not one. */
    private static void make(Path dir) throws IOException {
        Files.createDirectories(dir);
        ownerOnly(dir, "rwx------");
        write(dir.resolve(SEALING_FILE), pem(fresh("X25519").getPrivate()));
        write(dir.resolve(SIGNING_FILE), pem(fresh("Ed25519").getPrivate()));
        write(dir.resolve(FORMAT_FILE), FORMAT + "\n");
    }

    private static KeyPair fresh(String algorithm) {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(algorithm + " is part of the platform", impossible);
        }
    }

    /** Written beside, readable by its owner alone, then moved into place whole. */
    private static void write(Path file, String content) throws IOException {
        Path beside = file.resolveSibling(file.getFileName() + ".making");
        Files.writeString(beside, content, StandardCharsets.UTF_8);
        ownerOnly(beside, "rw-------");
        try {
            Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notOnThisDisk) {
            Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ownerOnly(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException notPosix) {
            // A filesystem with no POSIX permissions has its own access
            // control, which the host keeps; nothing here can say it.
        }
    }

    private static String pem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static PrivateKey privateKey(Path file, String algorithm) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(file + " is missing, so the participant's identity "
                    + "in " + file.getParent() + " is not whole");
        }
        try {
            String text = Files.readString(file, StandardCharsets.US_ASCII);
            int begin = text.indexOf("-----BEGIN PRIVATE KEY-----");
            int end = text.indexOf("-----END PRIVATE KEY-----");
            if (begin < 0 || end < begin) {
                throw new IllegalStateException(file + " is not a PKCS#8 PEM private key");
            }
            byte[] der = Base64.getMimeDecoder().decode(
                    text.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end));
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new IllegalStateException(file + " could not be read as a PKCS#8 PEM private "
                    + "key", unreadable);
        } catch (GeneralSecurityException notThatKind) {
            throw new IllegalStateException(file + " does not hold an " + algorithm
                    + " private key", notThatKind);
        }
    }

    /**
     * The public half of a private key, by generating the same pair again.
     *
     * <p>The platform derives a public half only as it generates a pair, from
     * the bytes its randomness hands it; handed the private key's own bytes,
     * it derives that key's public half. Ed25519's private key is its seed and
     * X25519's its scalar, which is all the generation reads.
     */
    private static java.security.PublicKey publicOf(PrivateKey key) {
        try {
            byte[] secret;
            NamedParameterSpec curve;
            if (key instanceof EdECPrivateKey ed) {
                secret = ed.getBytes().orElseThrow();
                curve = NamedParameterSpec.ED25519;
            } else if (key instanceof XECPrivateKey xec) {
                secret = xec.getScalar().orElseThrow();
                curve = NamedParameterSpec.X25519;
            } else {
                throw new IllegalStateException("not a participant's key: " + key.getAlgorithm());
            }
            KeyPairGenerator again = KeyPairGenerator.getInstance(curve.getName());
            again.initialize(curve, new Handing(secret));
            return again.generateKeyPair().getPublic();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("the public half could not be derived", impossible);
        }
    }

    /** Randomness that hands over one secret, so a generation reproduces a known pair. */
    private static final class Handing extends SecureRandom {

        private final byte[] secret;

        Handing(byte[] secret) {
            this.secret = secret.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != secret.length) {
                throw new IllegalStateException("asked for " + bytes.length + " bytes of a "
                        + secret.length + "-byte key");
            }
            System.arraycopy(secret, 0, bytes, 0, bytes.length);
        }
    }
}
