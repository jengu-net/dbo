package cloud.jengu.dbo.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant hands out the thing that says who is asking.
 *
 * <p>A tenant's authority was held by the runtime and passed to each surface
 * handler it wired, and to nothing else. Everything that needed it was inside
 * this process and already had it, so nothing noticed — until a host embeds
 * the framework and wants to secure its OWN doors with the tokens its tenants
 * issue. That host has exactly one honest way to verify such a token, which
 * is to ask the authority that minted it. The alternatives are to accept a
 * second issuer's word about somebody else's records, or to reimplement the
 * verification against a published key set, and both are worse than being
 * handed the object that already knows.
 *
 * <p>So the authority is on {@code TenantRuntime}, beside the store and the
 * feed, and the container registers it per tenant the way it registers those.
 * This is the same sentence the asking vocabulary already earned: a thing
 * reachable only by whoever can already reach the engine is reachable by
 * nobody.
 *
 * <p>On the shared runtime, which has an authority and tenants already up.
 * The claim is about what a tenant PUBLISHES rather than about a tenant
 * coming up, so nothing here needs a world of its own — and a world built to
 * ask this would be measuring how loaded the runner was.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantsAuthorityIsOnWhatItPublishesIT {

    @Test
    @DisplayName("a serving tenant's runtime carries its own authority, and it is the same one "
            + "the deployment holds — so a host is handed what already verifies this tenant's "
            + "tokens rather than building a second answer to who is asking")
    void aTenantPublishesTheAuthorityThatMintsItsTokens() {
        SharedTenants.Tenant tenant = SharedTenants.of(SharedTenants.Shape.R5);

        var runtime = SharedTenants.manager().runtime(tenant.code()).orElseThrow(
                () -> new AssertionError(tenant.code() + " is not serving, so there is no "
                        + "runtime to ask what it publishes"));

        assertNotNull(runtime.authority(),
                "the tenant serves on a deployment that has an authority and publishes none, "
                        + "so a host embedding this framework has nothing to verify its "
                        + "tenants' own tokens with");
        assertSame(tenant.authority(), runtime.authority(),
                "the runtime publishes a DIFFERENT authority from the one the deployment "
                        + "holds, so two objects would be signing and verifying for one tenant "
                        + "and a token minted by either would be refused by the other");
    }

    @Test
    @DisplayName("what a tenant publishes verifies that tenant's own tokens and nobody "
            + "else's, which is the property a host inherits by being handed it")
    void theAuthorityAPublishedTenantHandsOutRefusesAnotherTenantsToken() {
        SharedTenants.Tenant one = SharedTenants.of(SharedTenants.Shape.R5);
        SharedTenants.Tenant other = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);

        var mine = SharedTenants.manager().runtime(one.code()).orElseThrow().authority();
        var theirs = SharedTenants.manager().runtime(other.code()).orElseThrow().authority();
        assertNotNull(mine, one.code() + " publishes no authority, so the cross-tenant "
                + "refusal below would be asserted about nothing");
        assertNotNull(theirs, other.code() + " publishes no authority, so there is nothing "
                + "here that could have wrongly accepted the other tenant's token");

        // Minted here rather than read from anywhere: the point is which
        // authority made it, and a token borrowed from a fixture would not
        // say. The client is named for this test, because a shared world's
        // assertions are scoped to what the test itself made.
        String client = "authority-published-" + getClass().getSimpleName();
        String secret = "a-secret-for-" + client;
        mine.ensureClient(client, secret, java.util.List.of("system/*.read"));
        cloud.jengu.dbo.auth.TenantAuthority.TokenResult issued =
                mine.token(client, secret, "system/*.read");
        assertTrue(issued instanceof cloud.jengu.dbo.auth.TenantAuthority.TokenResult.Issued,
                "the tenant's own authority would not issue a token to a client it had just "
                        + "registered, so the rest of this test would be about nothing: "
                        + issued);
        String token = ((cloud.jengu.dbo.auth.TenantAuthority.TokenResult.Issued) issued)
                .accessToken();

        assertTrue(mine.validate(token).isPresent(),
                "the authority this tenant publishes will not verify a token it minted itself, "
                        + "so a host handed it would refuse every caller the tenant admits");
        assertTrue(theirs.validate(token).isEmpty(),
                "another tenant's published authority accepted this tenant's token, so a host "
                        + "holding one of these could read a tenant it was never admitted to — "
                        + "the cross-tenant refusal is the whole reason each tenant has its own");
    }
}
