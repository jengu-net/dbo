package cloud.jengu.dbo.core.api.seal;

import cloud.jengu.dbo.core.wire.RecordWire;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something a participant says, signed with its own key — checkable by
 * whoever holds its public half, before it has been admitted anywhere.
 *
 * <p>A process beside the store says what the participant is doing while the
 * store is not running: being installed, reinstalled, failing to start. The
 * side deciding whether to admit it already holds the signing key the
 * participant asked to be admitted with, and checks the statement against it.
 *
 * <p><b>What is signed is the statement behind a prefix</b>: the UTF-8 bytes
 * of {@value #SIGNED_AS} followed by the text. A participant signs other
 * things with the same key — each ask on a lane, each link of a run's trail —
 * and the prefix is what keeps a signature over one from being presented as
 * a signature over another.
 *
 * <p>The form is documented, with the OpenSSL commands that make it, in
 * {@code docs/arc42-008-crosscutting/processes-and-work/participant-keys.md}.
 *
 * @param statement what is said, as text
 * @param kid       the thumbprint of the key it is signed with
 * @param signature Ed25519 over the prefixed text, base64url without padding
 */
public record SignedStatement(String statement, String kid, String signature) {

    /** What precedes a statement in the bytes signed. */
    public static final String SIGNED_AS = "dbo-statement/1\n";

    /** The holder's side: the statement, signed. */
    public static SignedStatement sign(String statement, SigningKey as, PrivateKey with) {
        return new SignedStatement(statement, as.kid(), SigningKey.sign(signed(statement), with));
    }

    /** Whether this was signed by that key: named by it, and its signature over what was said. */
    public boolean verifiedBy(SigningKey key) {
        return statement != null && key.kid().equals(kid)
                && key.verifies(signed(statement), signature);
    }

    /** {@code {"statement":…,"kid":…,"signature":…}}. */
    public String render() {
        Map<String, Object> said = new LinkedHashMap<>();
        said.put("statement", statement);
        said.put("kid", kid);
        said.put("signature", signature);
        return RecordWire.write(said);
    }

    /**
     * A statement as it arrived.
     *
     * @throws IllegalArgumentException for text that is not one
     */
    public static SignedStatement parse(String json) {
        Object read = RecordWire.read(json);
        if (!(read instanceof Map<?, ?> said)
                || !(said.get("statement") instanceof String statement)
                || !(said.get("kid") instanceof String kid)
                || !(said.get("signature") instanceof String signature)) {
            throw new IllegalArgumentException("a signed statement carries a statement, a kid "
                    + "and a signature, as text");
        }
        return new SignedStatement(statement, kid, signature);
    }

    private static byte[] signed(String statement) {
        return (SIGNED_AS + statement).getBytes(StandardCharsets.UTF_8);
    }
}
