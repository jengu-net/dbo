package cloud.jengu.dbo.auth;

import java.util.Map;
import java.util.Set;

/**
 * Claims an application adds about the person who signed in.
 *
 * <p>One contributor answers for the deployment and is told the tenant, the
 * client and why the claims are being minted. What it adds reaches the ID
 * token and UserInfo of that sign-in, and never the access token, which
 * reaches the store's surfaces and every service a token is exchanged for.
 *
 * <p><b>A contributor that throws stops the minting.</b> No token is issued:
 * an application that decides access from what it added must never be handed
 * a token without it.
 */
public interface UserClaims {

    /** Claims to add for this person, or an empty map for none. */
    Map<String, Object> contribute(Subject subject);

    /** What the authority itself says, and a contributor may not. */
    Set<String> RESERVED = Set.of("iss", "sub", "aud", "exp", "iat", "nonce", "auth_time",
            "amr", "scope", "fhirUser", "at_hash", "sid");
}
