package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;

/**
 * The keys a worker beside the store holds, made on the worker's side.
 *
 * <p>A lane over the deployment's substrate carries no token. What admits the
 * worker there is a signature the tenant can check, and what reaches it there
 * is sealed to it — so it holds two private keys, and the tenant holds their
 * public halves against the name it enrolled under.
 *
 * <p><b>The private halves never leave this side.</b> This writes two files:
 * one the worker reads and nobody else, holding the private halves as the
 * worker starter takes them, and one that is handed to whoever runs the
 * tenant, holding the participant's name and the public halves as JWKs. The
 * second is the whole of the enrolment; it is safe to send, and the first is
 * not.
 *
 * <p>Run once, before the worker first starts under the {@code substrate}
 * profile: {@code ./gradlew :samples:spring-boot-worker-app:mintEnrolment}.
 * Running it again makes a new pair, which the tenant must then be given.
 */
public final class MintingAnEnrolment {

    /** The name the worker enrolls under, distinct from any client that signs in. */
    public static final String PARTICIPANT = "sample-admissions-participant";

    private MintingAnEnrolment() {
    }

    /**
     * @param args the directory to write into, and the tenant the worker
     *             performs for; {@code build/enrolment} and {@code hogwarts}
     *             when not given
     */
    public static void main(String[] args) throws IOException {
        Path into = Path.of(args.length > 0 ? args[0] : "build/enrolment");
        String tenant = args.length > 1 ? args[1] : "hogwarts";
        KeyPair sealing = KeyWrap.newParticipantKeyPair();
        KeyPair signing = SigningKey.newKeyPair();
        Files.createDirectories(into);

        Path worker = into.resolve("worker.properties");
        Files.writeString(worker, """
                # The PRIVATE halves. Read by the worker and by nobody else.
                dbo.worker.substrate.participant=%s
                dbo.worker.substrate.sealing-key=%s
                dbo.worker.substrate.signing-key=%s
                """.formatted(PARTICIPANT, encoded(sealing), encoded(signing)));

        Path enrolment = into.resolve("tenant.properties");
        Files.writeString(enrolment, """
                # The PUBLIC halves, for whoever runs the tenant to enrol.
                clinic.enrolment.tenant=%s
                clinic.enrolment.participant=%s
                clinic.enrolment.sealing-key=%s
                clinic.enrolment.signing-key=%s
                """.formatted(tenant, PARTICIPANT,
                ParticipantKey.of(sealing.getPublic()).render(),
                SigningKey.of(signing.getPublic()).render()));

        System.out.println("the worker's keys: " + worker.toAbsolutePath());
        System.out.println("the enrolment to hand over: " + enrolment.toAbsolutePath());
    }

    /** A private half as the worker starter takes it: base64 PKCS#8. */
    private static String encoded(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
