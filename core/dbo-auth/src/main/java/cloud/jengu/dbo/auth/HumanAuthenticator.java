package cloud.jengu.dbo.auth;

import java.util.Optional;

/**
 * The authentication seam (§16.2): production is a federated broker
 * (eeID/TARA) returning a verified national identifier; the local
 * credential form is the embedded/dev fallback. Either way the authority
 * owns AUTHORIZATION — this seam only answers "who is this person".
 */
public interface HumanAuthenticator {

    /** @return the practitioner's record id, when authentication succeeds */
    Optional<String> authenticate(String login, String secret);
}
