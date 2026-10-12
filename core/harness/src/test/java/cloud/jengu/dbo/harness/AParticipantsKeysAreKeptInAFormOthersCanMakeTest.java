package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.ParticipantKeys;
import cloud.jengu.dbo.core.api.seal.SignedStatement;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A participant's identity in the documented form, made and used by OpenSSL
 * as well as by this store. The vectors were made by OpenSSL alone, by the
 * commands {@code docs/arc42-008-crosscutting/processes-and-work/participant-keys.md} documents.
 */
class AParticipantsKeysAreKeptInAFormOthersCanMakeTest {

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

    @TempDir
    Path work;

    @Test
    @DisplayName("keys OpenSSL made are read as the published vectors say, and a statement "
            + "signs and verifies to the published signature")
    @Proving({DboPromises.PROC_A_PARTICIPANTS_KEYS_ARE_READ_AS_STANDARD_TOOLS_MAKE_THEM,
            DboPromises.PROC_A_PARTICIPANT_SIGNS_BEFORE_IT_IS_ADMITTED})
    void theVectorsHold() throws Exception {
        Properties vectors = vectors();
        ParticipantKeys keys = ParticipantKeys.adopt(copyOfTheVectorKeys());

        assertEquals(vectors.getProperty("sealing.x"), B64U.encodeToString(keys.sealingKey().x()));
        assertEquals(vectors.getProperty("sealing.kid"), keys.sealingKey().kid());
        assertEquals(vectors.getProperty("signing.x"), B64U.encodeToString(keys.signingKey().x()));
        assertEquals(vectors.getProperty("signing.kid"), keys.signingKey().kid());

        String statement = vectors.getProperty("statement");
        SignedStatement byOpenSsl = new SignedStatement(statement, keys.signingKey().kid(),
                vectors.getProperty("statement.signature"));
        assertTrue(byOpenSsl.verifiedBy(keys.signingKey()),
                "what OpenSSL signed does not verify here");
        assertEquals(vectors.getProperty("statement.signature"),
                SignedStatement.sign(statement, keys.signingKey(), keys.signing()).signature(),
                "this store signs the same statement differently from OpenSSL");
    }

    @Test
    @DisplayName("a JWK written by another tool — members in any order, spaced, without a kid — "
            + "reads as the same key")
    @Proving(DboPromises.PROC_A_PARTICIPANTS_KEYS_ARE_READ_AS_STANDARD_TOOLS_MAKE_THEM)
    void aJwkReadsWhateverItsLayout() throws Exception {
        Properties vectors = vectors();
        SigningKey signing = SigningKey.parse("{ \"x\" : \"" + vectors.getProperty("signing.x")
                + "\",\n  \"crv\": \"Ed25519\", \"kty\":\"OKP\" }");
        ParticipantKey sealing = ParticipantKey.parse("{\"crv\" :\"X25519\",  \"x\":\""
                + vectors.getProperty("sealing.x") + "\" ,\"kty\":\"OKP\"}");

        assertEquals(vectors.getProperty("signing.kid"), signing.kid());
        assertEquals(vectors.getProperty("sealing.kid"), sealing.kid());
    }

    @Test
    @DisplayName("an identity OpenSSL makes by the documented commands is adopted, and each "
            + "side verifies what the other signs")
    @Proving({DboPromises.PROC_A_PARTICIPANTS_KEYS_ARE_READ_AS_STANDARD_TOOLS_MAKE_THEM,
            DboPromises.PROC_A_PARTICIPANT_SIGNS_BEFORE_IT_IS_ADMITTED})
    void openSslAndThisStoreAgree() throws Exception {
        Assumptions.assumeTrue(openSslIsHere(), "OpenSSL is not installed here");
        Path dir = work.resolve("made-by-openssl");
        Files.createDirectories(dir);
        run(dir, "openssl", "genpkey", "-algorithm", "X25519", "-out", "sealing.pem");
        run(dir, "openssl", "genpkey", "-algorithm", "ed25519", "-out", "signing.pem");
        Files.writeString(dir.resolve("FORMAT"), ParticipantKeys.FORMAT + "\n");

        ParticipantKeys keys = ParticipantKeys.adopt(dir);

        String statement = "reinstalling, step 2 of 5";
        Files.write(dir.resolve("said.bin"),
                (SignedStatement.SIGNED_AS + statement).getBytes(StandardCharsets.UTF_8));
        run(dir, "openssl", "pkeyutl", "-sign", "-rawin", "-inkey", "signing.pem",
                "-in", "said.bin", "-out", "said.sig");
        String byOpenSsl = B64U.encodeToString(Files.readAllBytes(dir.resolve("said.sig")));
        assertTrue(new SignedStatement(statement, keys.signingKey().kid(), byOpenSsl)
                .verifiedBy(keys.signingKey()), "what OpenSSL signed does not verify here");

        SignedStatement byThisStore = SignedStatement.sign(statement, keys.signingKey(),
                keys.signing());
        Files.write(dir.resolve("ours.sig"),
                Base64.getUrlDecoder().decode(byThisStore.signature()));
        run(dir, "openssl", "pkey", "-in", "signing.pem", "-pubout", "-out", "signing.pub");
        run(dir, "openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", "signing.pub",
                "-in", "said.bin", "-sigfile", "ours.sig");
    }

    @Test
    @DisplayName("a directory holding no identity is given one, kept for its owner alone, and "
            + "adopted unchanged the next time")
    @Proving(DboPromises.PROC_A_PARTICIPANTS_KEYS_ARE_KEPT_IN_A_DOCUMENTED_FORM)
    void anEmptyDirectoryIsGivenAnIdentityOnce() throws Exception {
        Path dir = work.resolve("identity");

        ParticipantKeys made = ParticipantKeys.adoptOrMake(dir);
        ParticipantKeys again = ParticipantKeys.adoptOrMake(dir);

        assertEquals(made.signingKey(), again.signingKey(), "the identity was made twice");
        assertEquals(made.sealingKey(), again.sealingKey(), "the identity was made twice");
        assertEquals(ParticipantKeys.FORMAT, Files.readString(dir.resolve("FORMAT")).strip());
        for (String file : List.of("sealing.pem", "signing.pem")) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir.resolve(file))), file);
            assertTrue(Files.readString(dir.resolve(file)).startsWith(
                    "-----BEGIN PRIVATE KEY-----"), file + " is not PKCS#8 PEM");
        }
    }

    @Test
    @DisplayName("a directory holding part of an identity, one in an unknown form, or a key of "
            + "the wrong kind is refused, and nothing is made over it")
    @Proving(DboPromises.PROC_A_PARTICIPANTS_KEYS_ARE_KEPT_IN_A_DOCUMENTED_FORM)
    void whatIsNotWholeIsRefused() throws Exception {
        Path partial = copyOfTheVectorKeys();
        Files.delete(partial.resolve("sealing.pem"));
        assertThrows(IllegalStateException.class, () -> ParticipantKeys.adoptOrMake(partial));
        assertFalse(Files.exists(partial.resolve("sealing.pem")), "a missing key was made over");

        Path unknown = copyOfTheVectorKeys();
        Files.writeString(unknown.resolve("FORMAT"), "dbo-participant-keys/9\n");
        assertThrows(IllegalStateException.class, () -> ParticipantKeys.adoptOrMake(unknown));

        Path swapped = copyOfTheVectorKeys();
        Path signing = swapped.resolve("signing.pem");
        Path sealing = swapped.resolve("sealing.pem");
        String wasSigning = Files.readString(signing);
        Files.writeString(signing, Files.readString(sealing));
        Files.writeString(sealing, wasSigning);
        assertThrows(IllegalStateException.class, () -> ParticipantKeys.adoptOrMake(swapped));

        Path stray = work.resolve("stray");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("notes.txt"), "not an identity");
        assertThrows(IllegalStateException.class, () -> ParticipantKeys.adoptOrMake(stray));
        assertFalse(Files.exists(stray.resolve("signing.pem")),
                "an identity was made over a directory holding something else");
    }

    @Test
    @DisplayName("a statement verifies against the key it was signed with and no other, not once "
            + "altered, and a plain signature over the same text is not one")
    @Proving(DboPromises.PROC_A_PARTICIPANT_SIGNS_BEFORE_IT_IS_ADMITTED)
    void aStatementIsTheSignersAndOnlyAsSaid() throws Exception {
        ParticipantKeys keys = ParticipantKeys.adopt(copyOfTheVectorKeys());
        ParticipantKeys other = ParticipantKeys.adoptOrMake(work.resolve("other"));
        SignedStatement said = SignedStatement.parse(SignedStatement.sign("installing",
                keys.signingKey(), keys.signing()).render());

        assertTrue(said.verifiedBy(keys.signingKey()));
        assertFalse(said.verifiedBy(other.signingKey()), "another participant's key verified it");
        assertFalse(new SignedStatement("installed", said.kid(), said.signature())
                .verifiedBy(keys.signingKey()), "an altered statement verified");
        String plain = SigningKey.sign("installing".getBytes(StandardCharsets.UTF_8),
                keys.signing());
        assertFalse(new SignedStatement("installing", said.kid(), plain)
                .verifiedBy(keys.signingKey()),
                "a signature over the bare text passed as a statement");
    }

    private Path copyOfTheVectorKeys() throws Exception {
        Path dir = Files.createTempDirectory(work, "vector-keys");
        for (String file : List.of("FORMAT", "sealing.pem", "signing.pem")) {
            try (InputStream in = getClass().getResourceAsStream(
                    "/participant-keys/v1/" + file)) {
                Files.copy(in, dir.resolve(file));
            }
        }
        return dir;
    }

    private Properties vectors() throws Exception {
        Properties vectors = new Properties();
        try (InputStream in = getClass().getResourceAsStream(
                "/participant-keys/v1.vectors.properties")) {
            vectors.load(in);
        }
        return vectors;
    }

    private static boolean openSslIsHere() {
        try {
            Process probe = new ProcessBuilder("openssl", "version").redirectErrorStream(true)
                    .start();
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception notHere) {
            return false;
        }
    }

    private static void run(Path in, String... command) throws Exception {
        Process done = new ProcessBuilder(command).directory(in.toFile())
                .redirectErrorStream(true).start();
        String said = new String(done.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(done.waitFor(30, TimeUnit.SECONDS), String.join(" ", command));
        assertEquals(0, done.exitValue(), String.join(" ", command) + ": " + said);
    }
}
