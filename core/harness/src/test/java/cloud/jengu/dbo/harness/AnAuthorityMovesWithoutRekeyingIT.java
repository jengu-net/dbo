package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.IdentityModel;
import cloud.jengu.dbo.auth.KeyProtector;
import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's authority belongs to the tenant, so moving the deployment under
 * it changes nothing a client can see.
 *
 * <p>The claim has two halves and the harness had been exercising neither.
 * Every other test passes a null issuer, so the issuer is always derived from
 * whatever port the server happened to bind — which works, and means the
 * configured-issuer path had no coverage at all. And the key material is only
 * ever read by the process that wrote it, so "the keys live in the tenant
 * database" was true by construction rather than by demonstration.
 *
 * <p>So this configures an issuer that is nobody's listening address, then
 * stands a <b>second</b> authority up over the same database — a different
 * pool, a different protector instance, the same tenant — and asks it to
 * honour what the first one minted. That is what moving a deployment is: the
 * keys stay where they were, and the tenant's name for itself does not depend
 * on where it is being served from.
 *
 * <p>If this ever fails, a tenant cannot be moved without re-keying, which
 * means every client that trusted it has to be told.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnAuthorityMovesWithoutRekeyingIT {

    /** Deliberately not derivable from any host or port this test binds. */
    private static final String ISSUER = "https://identiteet.example/tervishoid";

    private static final byte[] KEK = new byte[32];

    static TenantAuthority before;
    static String minted;

    @BeforeAll
    void up() {
        new SecureRandom().nextBytes(KEK);
        before = authorityOn("AnAuthorityMovesWithoutRekeyingIT");
        before.ensureSigningKey();
        before.ensureClient("kolija", "saladus", List.of("system/*.read"));

        TenantAuthority.TokenResult issued =
                before.token("kolija", "saladus", "system/*.read");
        assertTrue(issued instanceof TenantAuthority.TokenResult.Issued,
                "the fixture could not mint a token, so nothing below is testing "
                        + "portability: " + issued);
        minted = ((TenantAuthority.TokenResult.Issued) issued).accessToken();
    }

    /**
     * A fresh pool and a fresh protector over the same database: everything a
     * restart elsewhere changes, and nothing it does not.
     */
    private static TenantAuthority authorityOn(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor(database));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        return new TenantAuthority(new PgObjectStore(ds, IdentityModel.registrations()),
                ISSUER, new KeyProtector(KEK));
    }

    @Test
    @DisplayName("the issuer is what the tenant was configured with, not where it is served from")
    @Proving(DboPromises.AUTH_PORTABLE_AUTHORITY)
    void theIssuerIsConfigurationRatherThanAnAddress() {
        assertEquals(ISSUER, before.issuer());
        assertTrue(before.discoveryJson().contains(ISSUER),
                "discovery must advertise the configured issuer, or a client following "
                        + "it lands on whatever host answered: " + before.discoveryJson());
        assertTrue(claim(minted, "iss").equals(ISSUER),
                "the token says it was issued by something other than the tenant, so the "
                        + "tenant cannot present a custom domain: " + claim(minted, "iss"));
    }

    @Test
    @DisplayName("a second deployment over the same database honours the first one's tokens, "
            + "and never re-keys")
    @Proving(DboPromises.AUTH_PORTABLE_AUTHORITY)
    void movingTheDeploymentKeepsTheKeys() {
        String keysBefore = before.jwksJson();
        // Two empty documents compare equal, and two authorities that both
        // published nothing would sail through the assertion below while
        // proving the opposite of the claim.
        assertTrue(keysBefore.contains("\"kid\""),
                "the tenant published no key at all, so comparing key material proves "
                        + "nothing: " + keysBefore);

        TenantAuthority after = authorityOn("AnAuthorityMovesWithoutRekeyingIT");
        // A deployment brings itself up the way any deployment does. The point
        // is that this finds the tenant's key rather than minting one: a new
        // key here would be a silent re-keying, and every client holding a
        // valid token would start failing verification for no visible reason.
        after.ensureSigningKey();

        assertEquals(keysBefore, after.jwksJson(),
                "the moved deployment published different key material, so moving a "
                        + "tenant re-keys it and every client that trusted it must be told");
        assertTrue(after.validate(minted).isPresent(),
                "a token minted before the move no longer verifies after it");
        // And validation is doing work: one flipped character in the signature
        // must be refused, or "it verifies" means "it was not checked".
        assertTrue(after.validate(tampered(minted)).isEmpty(),
                "a tampered token verified, so the check above asserts nothing");
    }

    /**
     * The same token with one bit of its signature flipped.
     *
     * <p>Changing the last CHARACTER instead is the obvious version and it is
     * wrong: the final base64url character of a signature carries only two or
     * four significant bits, so a different character can decode to identical
     * bytes. That tamper is then no tamper, the token verifies, and the
     * assertion reports that verification is not checking anything — which is
     * true of the test rather than of the store. It passed locally and failed
     * on the first signature whose trailing bits absorbed the change.
     *
     * <p>Decoding and flipping a bit is unconditional: the signature is
     * different every time, by exactly one bit.
     */
    private static String tampered(String jwt) {
        String[] parts = jwt.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 0x01;
        return parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /** Reads one claim out of a JWS payload without a library, since we only need one. */
    private static String claim(String jwt, String name) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(payload);
        assertTrue(m.find(), "no '" + name + "' claim in " + payload);
        return m.group(1);
    }
}
