package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every type declares exactly one primary identity class, and the contract
 * fails closed at registration without it — the same
 * shape {@link HandlingTest} proves for the handling declaration.
 *
 * <p>"Exactly one" cuts both ways: a type that declares nothing is refused,
 * and a type whose declaration smuggles a second identity in through the
 * side door — identifier systems on a CANONICAL or INTERNAL type — is
 * refused too, because those systems would be a second answer to "what makes
 * two objects the same object".
 */
class EveryTypeDeclaresItsIdentityTest {

    private static final EnvelopeExtractor NOTHING = (typeName, payload) -> new Envelope();

    @Test
    @DisplayName("a type registered without an identity class is refused")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void noIdentityClassIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new TypeRegistration("Unsaid", "test", null,
                        Set.of(), Handling.operational(), NOTHING, List.of()));
    }

    @Test
    @DisplayName("IDENTIFIER identity without designated systems is an empty claim, refused")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void identifierIdentityRequiresItsSystems() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TypeRegistration("Device", "test", IdentityClass.IDENTIFIER,
                        Set.of(), Handling.operational(), NOTHING, List.of()));

        assertTrue(refused.getMessage().contains("Device"), refused.getMessage());
        assertTrue(refused.getMessage().contains("designated identity systems"),
                refused.getMessage());
    }

    @Test
    @DisplayName("CANONICAL identity carrying identifier systems is two identities, refused")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void canonicalIdentityCarriesNoSecondIdentity() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TypeRegistration("ValueSet", "test", IdentityClass.CANONICAL,
                        Set.of("https://issuer.example/code"), Handling.operational(),
                        NOTHING, List.of()));

        assertTrue(refused.getMessage().contains("url is the identity"), refused.getMessage());
    }

    @Test
    @DisplayName("INTERNAL identity carrying identifier systems is refused the same way")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void internalIdentityCarriesNoSecondIdentity() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TypeRegistration("Note", "test", IdentityClass.INTERNAL,
                        Set.of("https://issuer.example/code"), Handling.operational(),
                        NOTHING, List.of()));

        assertTrue(refused.getMessage().contains("INTERNAL identity carries no identifier"),
                refused.getMessage());
    }

    @Test
    @DisplayName("a spec type without an identity declaration stops the tenant coming up")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void aSpecTypeWithoutAnIdentityIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                        {"code":"salaja","face":"r4","types":[
                          {"name":"Patient","handling":"operational"}]}"""));

        assertTrue(refused.getMessage().contains("identity"), refused.getMessage());
    }

    @Test
    @DisplayName("a misspelt identity class is refused by name — there is no fourth class "
            + "and a typo must not become one")
    @Proving(DboPromises.CORE_DECLARED_IDENTITY)
    void aTypoIsNotAnIdentityClass() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                        {"code":"vale","face":"r4","types":[
                          {"name":"Patient","identity":"cannonical","handling":"operational"}]}"""));

        assertTrue(refused.getMessage().contains("cannonical"), refused.getMessage());
        assertTrue(refused.getMessage().contains("vale/Patient"), refused.getMessage());
    }
}
