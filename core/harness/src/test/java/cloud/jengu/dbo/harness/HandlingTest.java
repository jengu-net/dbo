package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.TypeRegistration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every type says what kind of data it is, and the
 * combinations that are always mistakes are refused where they are written
 * rather than discovered where they hurt.
 */
class HandlingTest {

    private static final EnvelopeExtractor NOTHING = (typeName, payload) -> new Envelope();

    @Test
    @DisplayName("a type declared in a SPEC without a handling is refused too — the rule "
            + "does not depend on which layer the declaration came from")
    void anUnclassifiedTypeInASpecCannotBeParsed() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                        {"code":"unsaid","face":"r4","types":[
                          {"name":"Patient","identity":"internal"}]}"""));

        assertTrue(refused.getMessage().contains("unsaid/Patient"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no declared handling"), refused.getMessage());
    }

    @Test
    @DisplayName("a misspelt handling is refused rather than falling through — a typo must "
            + "not become a classification")
    void aTypoIsNotAClassification() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                        {"code":"typo","face":"r4","types":[
                          {"name":"Patient","identity":"internal","handling":"operatoinal"}]}"""));

        assertTrue(refused.getMessage().contains("operatoinal"), refused.getMessage());
    }

    @Test
    @DisplayName("a classified spec parses, and the type carries what it declared")
    void aClassifiedSpecCarriesItsDeclaration() {
        var spec = cloud.jengu.dbo.tenant.TenantSpec.parse("""
                {"code":"said","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"}]}""");

        assertEquals(Handling.replicated(), spec.types().get(0).handling());
    }

    @Test
    @DisplayName("a type registered without a declared handling is refused, and says what to say")
    void anUnclassifiedTypeCannotBeRegistered() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TypeRegistration("Mystery", "test", IdentityClass.INTERNAL,
                        Set.of(), null, NOTHING, List.of()));

        assertTrue(refused.getMessage().contains("Mystery"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no declared handling"), refused.getMessage());
    }

    @Test
    @DisplayName("ephemeral data may not travel — a restored heartbeat asserts a lie")
    void ephemeralDataMayNotTravel() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Handling(Handling.Authority.OBSERVED, Handling.Mutability.REPLACE_IN_PLACE,
                        Handling.Durability.EPHEMERAL, Handling.Travel.BACKUP_ONLY));

        assertTrue(refused.getMessage().contains("may not travel"), refused.getMessage());
    }

    @Test
    @DisplayName("an append-only record that expires is refused — that is not append-only")
    void appendOnlyCannotExpire() {
        assertThrows(IllegalArgumentException.class,
                () -> new Handling(Handling.Authority.PLATFORM_RUNTIME,
                        Handling.Mutability.APPEND_ONLY,
                        Handling.Durability.EPHEMERAL, Handling.Travel.NEVER));
    }

    @Test
    @DisplayName("observed data may not accumulate versions — heartbeats are not history")
    void observedDataIsNotVersioned() {
        assertThrows(IllegalArgumentException.class,
                () -> new Handling(Handling.Authority.OBSERVED, Handling.Mutability.REPLACE_IN_PLACE,
                        Handling.Durability.VERSIONED, Handling.Travel.NEVER));
    }

    @Test
    @DisplayName("credentials ride a backup and never an export; clinical data rides both")
    void travelDiffersByKind() {
        assertTrue(Handling.storeAuthored().travelsInBackup(),
                "a backup without credentials cannot authenticate its own tenants");
        assertFalse(Handling.storeAuthored().travelsInPortableExport(),
                "an export carrying credentials hands somebody our keys");

        assertTrue(Handling.operational().travelsInPortableExport(),
                "a hospital's own records are the point of an export");
        assertFalse(Handling.ephemeral().travelsInBackup());
    }

    @Test
    @DisplayName("audit is append-only against everyone, the vendor included")
    void auditIsAppendOnly() {
        assertEquals(Handling.Mutability.APPEND_ONLY, Handling.audit().mutability());
        assertFalse(Handling.audit().isWritableBy(Handling.Authority.TENANT_USERS));
    }

    @Test
    @DisplayName("replicated data is writable only by the tenant that publishes it")
    void replicatedDataIsReadOnlyHere() {
        Handling replicated = Handling.replicated();
        assertFalse(replicated.isWritableBy(Handling.Authority.TENANT_USERS),
                "a hospital editing its copy of a zone's vocabulary is the defect, not a feature");
        assertTrue(replicated.isWritableBy(Handling.Authority.SOURCE_TENANT));
    }
}
