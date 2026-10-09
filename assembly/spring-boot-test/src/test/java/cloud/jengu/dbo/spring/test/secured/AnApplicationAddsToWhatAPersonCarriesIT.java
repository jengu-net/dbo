package cloud.jengu.dbo.spring.test.secured;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an application adds to a person's claims, and what the authority does
 * when adding goes wrong.
 *
 * <p>The application's contributor answers each client differently, so each
 * leg signs the same person in through a different client and reads what the
 * authority made of it.
 *
 * <p><b>Here rather than on the sample world</b>, because the ways a
 * contributor fails are this test's to stage: the sample application's
 * contributor is one an integrator would copy, and teaching it to throw on
 * request would put that in front of them.
 */
@DboSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnApplicationAddsToWhatAPersonCarriesIT {

    private static final String WARD = "ward";
    private static final String REDIRECT = "http://127.0.0.1/cb";

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @BeforeAll
    void aNurseWorksOnTheWard() {
        dbo.declare(WARD, """
                {"code":"ward","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Person","identity":"internal","handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"},
                  {"name":"PractitionerRole","identity":"internal","handling":"operational"},
                  {"name":"Organization","identity":"internal","handling":"operational"}]}""");
        assertTrue(dbo.until(WARD, true, Duration.ofMinutes(5)), "the ward never served");
        String ward = dbo.write(WARD, "Organization",
                "{\"resourceType\":\"Organization\",\"name\":\"Ward 7\"}").idOrFail();
        String practitioner = dbo.write(WARD, "Practitioner",
                "{\"resourceType\":\"Practitioner\",\"name\":[{\"family\":\"Nightingale\"}]}")
                .idOrFail();
        String person = dbo.write(WARD, "Person", """
                {"resourceType":"Person",
                 "link":[{"target":{"reference":"Practitioner/%s"}}]}""".formatted(practitioner))
                .idOrFail();
        dbo.write(WARD, "PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "organization":{"reference":"Organization/%s"},
                 "code":[{"coding":[{"system":"urn:example:role","code":"nurse"}]}]}"""
                .formatted(practitioner, ward)).idOrFail();
        var authority = tenants.authority(WARD).orElseThrow();
        authority.ensureRoleGrant("nurse", List.of("user/*.read"));
        authority.ensureLocalCredential("florence", "lamp-1854", person);
        for (String client : List.of("reads", "breaks", "oversteps", "overflows")) {
            authority.ensureClient(client, client + "-secret", List.of("user/*.read"),
                    "confidential", List.of(REDIRECT));
        }
    }

    private HttpResponse<String> signedInThrough(String client) {
        HttpResponse<String> login = form("/authorize/login", "client_id=" + client
                + "&redirect_uri=" + encoded(REDIRECT) + "&login=florence&password=lamp-1854");
        String location = login.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("no code: " + login.statusCode() + " " + login.body()));
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");
        return form("/token", "grant_type=authorization_code&client_id=" + client
                + "&client_secret=" + client + "-secret&code=" + code
                + "&redirect_uri=" + encoded(REDIRECT));
    }

    private HttpResponse<String> form(String path, String body) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(WARD) + "/oidc" + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), null);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String idClaims(HttpResponse<String> tokens) {
        String idToken = tokens.body().replaceAll("(?s).*\"id_token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the application is handed the person as the authority loaded them, roles and "
            + "the organisations they are held at included, and what it adds is signed in")
    @Proving(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES)
    void theApplicationIsHandedThePersonAsLoaded() {
        HttpResponse<String> tokens = signedInThrough("reads");
        assertEquals(200, tokens.statusCode(), tokens.body());
        String id = idClaims(tokens);
        Proves.that(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES,
                id.contains("\"practitioners\":1") && id.contains("\"held_at\":[\"Ward 7\"]"),
                "the application was not handed the person's practitioner and the "
                        + "organisation their role is held at: " + id);
    }

    @Test
    @DisplayName("an application that fails while adding stops the sign-in, rather than a token "
            + "going out without what it relies on")
    @Proving(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES)
    void aFailingApplicationStopsTheSignIn() {
        HttpResponse<String> tokens = signedInThrough("breaks");
        Proves.that(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES,
                tokens.statusCode() == 503 && tokens.body().contains("temporarily_unavailable")
                        && !tokens.body().contains("access_token"),
                "a token went out although the application's claims could not be made: "
                        + tokens.statusCode() + " " + tokens.body());
    }

    @Test
    @DisplayName("an application cannot say what only the authority says")
    @Proving(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES)
    void anApplicationCannotSpeakForTheAuthority() {
        HttpResponse<String> tokens = signedInThrough("oversteps");
        Proves.that(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES,
                tokens.statusCode() == 400 && tokens.body().contains("'sub'")
                        && !tokens.body().contains("access_token"),
                "an application was let say who the token is about: " + tokens.statusCode()
                        + " " + tokens.body());
    }

    @Test
    @DisplayName("claims over the bound are refused when they are minted, not cut short "
            + "wherever they first run out of room")
    @Proving(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES)
    void claimsOverTheBoundAreRefused() {
        HttpResponse<String> tokens = signedInThrough("overflows");
        Proves.that(DboPromises.AUTH_AN_APPLICATION_ADDS_TO_WHAT_A_PERSON_CARRIES,
                tokens.statusCode() == 400 && tokens.body().contains("over the bound")
                        && !tokens.body().contains("access_token"),
                "claims over the bound were minted: " + tokens.statusCode() + " "
                        + tokens.body());
    }
}
