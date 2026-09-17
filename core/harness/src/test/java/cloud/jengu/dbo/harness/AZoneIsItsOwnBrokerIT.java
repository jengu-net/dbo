package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zone that names no broker is its own, so a tenant can be in one without
 * an identity provider being stood up first.
 *
 * <p>A zone is usually declared for its rules and its terminology. Until now
 * declaring one also obliged the deployment to configure human federation
 * before anything would serve: a zone holding no {@code ZoneBroker} records
 * refused every member with "declares no brokers", and the member never came
 * up — not for machine traffic either, which has nothing to do with a login
 * ceremony.
 *
 * <p>The premise was that a broker is somewhere else. It need not be. Every
 * tenant carries an authority, a zone is a tenant, and its issuer is an
 * ordinary OIDC issuer — so a self-contained deployment federates to itself
 * rather than being unable to express what it is.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AZoneIsItsOwnBrokerIT {

    private static final String ZONE = "solo-zone";
    private static final String MEMBER = "solo-member";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        var postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-solo-zone");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AZoneIsItsOwnBrokerIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // A zone holding rules, and nothing about identity at all.
        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r5","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(ZONE));
        Files.writeString(dir.resolve(MEMBER + ".json"), """
                {"code":"%s","face":"r5","zone":"%s","types":[
                  {"name":"Patient","identity":"identifier",
                   "systems":["https://solo.example/nid"],"handling":"operational"}]}"""
                .formatted(MEMBER, ZONE));
        UntilServed.scan(manager, ZONE, MEMBER);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("a tenant in a zone that declares no identity broker comes up and serves, "
            + "which it did not: the zone is the broker")
    @Proving(DboPromises.AUTH_A_ZONE_IS_ITS_OWN_BROKER)
    void aMemberOfABrokerlessZoneServes() throws Exception {
        HttpResponse<String> metadata = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(MEMBER) + "/metadata")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metadata.statusCode(),
                "the member of a zone with no declared broker did not come up: "
                        + metadata.body());
        assertTrue(metadata.body().contains("CapabilityStatement"), metadata.body());

        // And the ceremony it federates to is the zone's own, standing where a
        // member's login is sent. Asked for the keys it signs its assertions
        // with, it answers — which is the difference between "no broker is
        // configured" and "the zone is the broker".
        HttpResponse<String> hub = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(MEMBER)
                                .replaceAll("/t/.*", "/z/" + ZONE + "/hub/jwks.json")))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hub.statusCode(),
                "the zone's own ceremony is not mounted, so nothing is the broker: "
                        + hub.body());
        assertTrue(hub.body().contains("keys"), hub.body());
    }
}
