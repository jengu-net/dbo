package cloud.jengu.dbo.spring.test.secured;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application with an API of its own accepts its tenants' bearer tokens
 * where their own doors would, and refuses them where the doors would.
 *
 * <p>Each leg asks the application's API and the tenant's door the same
 * question with the same token, because the promise is that the two answer
 * alike: a token the store would refuse must not get in beside it, and one
 * it would accept must not be turned away there.
 *
 * <p><b>A world of its own, and the reason is security.</b> The sample
 * world's application secures none of its own endpoints, and giving it Spring
 * Security to prove this would change every story that walks it. This
 * module's world is one tenant at bootstrap; the rest are declared while the
 * application runs, because arriving after it started is half the claim.
 */
@DboSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnApplicationsApiAcceptsItsTenantsTokensIT {

    private static final String CLINIC = "clinic";
    private static final String NEIGHBOUR = "neighbour";
    private static final String PARTNER = "partner";
    private static final String MANAGED = "managed";

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    Environment environment;

    private String clinicToken;

    private HttpResponse<String> api(String tenant, String token) {
        return dbo.get("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")
                + "/api/" + tenant + "/me", token);
    }

    private HttpResponse<String> door(String tenant, String type, String token) {
        return dbo.get(dbo.at(tenant) + "/fhir/" + type, token);
    }

    private static String basic(String code, String extra) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"writes"}%s,
                 "types":[{"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(code, extra);
    }

    @Test
    @Order(1)
    @DisplayName("a clinic declared after the application started has its token accepted on "
            + "the application's API, as its own door accepts it")
    @Proving(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT)
    void aClinicThatArrivedLaterIsAccepted() {
        dbo.declare(CLINIC, basic(CLINIC, ""));
        assertTrue(dbo.until(CLINIC, true, Duration.ofMinutes(5)), "the clinic never served");
        clinicToken = dbo.token(CLINIC);

        HttpResponse<String> asked = api(CLINIC, clinicToken);
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                asked.statusCode() == 200 && asked.body().contains("tenant=" + CLINIC)
                        && asked.body().contains("SCOPE_system/*.read"),
                "a clinic that came up after the application started is not accepted on its "
                        + "API: " + asked.statusCode() + " " + asked.body());
        assertEquals(200, door(CLINIC, "Basic", clinicToken).statusCode(),
                "the clinic's own door refused the token the API was asked about");
    }

    @Test
    @Order(2)
    @DisplayName("the clinic's token is refused where its neighbour is addressed, as the "
            + "neighbour's door refuses it")
    @Proving(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT)
    void anotherTenantsTokenIsRefused() {
        assertEquals(401, door(NEIGHBOUR, "Basic", clinicToken).statusCode());
        HttpResponse<String> asked = api(NEIGHBOUR, clinicToken);
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                asked.statusCode() == 401,
                "the application's API accepted a token the neighbour's door refuses: "
                        + asked.statusCode() + " " + asked.body());
    }

    @Test
    @Order(3)
    @DisplayName("a partner's token is accepted where the clinic it manages is addressed, as "
            + "that clinic's door accepts it, and refused where it manages nothing")
    @Proving(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT)
    void aPartnersTokenIsAcceptedWhereTheRelationIs() throws Exception {
        dbo.declare(PARTNER, basic(PARTNER, ""));
        assertTrue(dbo.until(PARTNER, true, Duration.ofMinutes(5)), "the partner never served");
        dbo.declare(MANAGED, basic(MANAGED, ",\"managedBy\":\"" + PARTNER + "\""));
        assertTrue(dbo.until(MANAGED, true, Duration.ofMinutes(5)), "the clinic never served");
        tenants.authority(PARTNER).orElseThrow()
                .ensureClient("support", "support-secret", List.of("system/*.read"));
        String partnerToken = clientCredentials(PARTNER, "support", "support-secret");

        assertEquals(200, door(MANAGED, "AuditEvent", partnerToken).statusCode(),
                "the managed clinic's door refused its partner");
        HttpResponse<String> managed = api(MANAGED, partnerToken);
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                managed.statusCode() == 200 && managed.body().contains("tenant=" + MANAGED)
                        && !managed.body().contains("audience=null"),
                "the partner's token is not accepted as the relation the managed clinic "
                        + "declared: " + managed.statusCode() + " " + managed.body());

        assertEquals(401, door(NEIGHBOUR, "AuditEvent", partnerToken).statusCode());
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                api(NEIGHBOUR, partnerToken).statusCode() == 401,
                "the partner's token was accepted where it manages nothing");
    }

    @Test
    @Order(4)
    @DisplayName("an unknown tenant and a token that is not one are refused alike")
    @Proving(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT)
    void refusalsCannotBeToldApart() {
        HttpResponse<String> unknown = api("nobody", clinicToken);
        HttpResponse<String> garbage = api(CLINIC, "eyJub3QiOiJhIHRva2VuIn0.e30.c2ln");
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                unknown.statusCode() == 401 && garbage.statusCode() == 401
                        && challenge(unknown).equals(challenge(garbage)),
                "an unknown tenant is answered differently from a bad token, which tells a "
                        + "caller which tenants exist: " + challenge(unknown) + " / "
                        + challenge(garbage));
    }

    @Test
    @Order(5)
    @DisplayName("once the clinic is retracted its token is refused on the application's API, "
            + "with no restart")
    @Proving(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT)
    void aRetractedClinicIsRefused() {
        dbo.retract(CLINIC);
        assertTrue(dbo.until(CLINIC, false, Duration.ofMinutes(2)), "the clinic still serves");
        HttpResponse<String> asked = api(CLINIC, clinicToken);
        Proves.that(DboPromises.AUTH_AN_APPLICATIONS_API_ACCEPTS_WHAT_ITS_TENANTS_DOORS_ACCEPT,
                asked.statusCode() == 401,
                "a retracted clinic's token is still accepted on the application's API: "
                        + asked.statusCode() + " " + asked.body());
    }

    private static String challenge(HttpResponse<String> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private String clientCredentials(String tenant, String client, String secret)
            throws Exception {
        HttpResponse<String> issued = dbo.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(tenant) + "/oidc/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"
                        + "&client_id=" + URLEncoder.encode(client, StandardCharsets.UTF_8)
                        + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8))),
                null);
        assertEquals(200, issued.statusCode(), issued.body());
        return issued.body().replaceAll("(?s).*\"access_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
