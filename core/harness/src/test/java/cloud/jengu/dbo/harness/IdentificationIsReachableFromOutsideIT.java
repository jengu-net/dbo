package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant can identify somebody, from outside the container.
 *
 * <p>The identity toolset — claims with strength, candidates, adjudication,
 * binding, anonymity — was built, proven by seven integration tests, and
 * called by no production code at all. Its record types were registered for
 * every tenant with an authority, so its state had somewhere to live; what it
 * had was no door. That is this repository's characteristic defect, and every
 * one of those seven tests passed throughout, because a harness <b>is</b> the
 * container and constructs whatever it needs.
 *
 * <p>So this drives the real surface over real HTTP with a real token, and
 * nothing here reaches into the JVM to do it. Ordered, because identification
 * is a sequence: what the store makes of a claim, what a person decided about
 * it, and what that leaves standing.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentificationIsReachableFromOutsideIT {

    private static final String TENANT = "tuvastaja";
    private static final String EID = "https://ee.ee/eid";
    /** A person the store already holds, so a claim has something to match. */
    private static final String KNOWN = "38001010001";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI door;
    static String subjectId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-tuvastaja");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("IdentificationIsReachableFromOutsideIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT, EID));
        UntilServed.scan(manager, TENANT);
        door = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/identity");

        subjectId = manager.runtime(TENANT).orElseThrow().engine()
                .put(PutRequest.create("Person", ("""
                        {"resourceType":"Person","identifier":[{"system":"%s","value":"%s"}],
                         "name":[{"family":"Tuntud"}]}""".formatted(EID, KNOWN))
                        .getBytes(StandardCharsets.UTF_8))).id();
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("a claim presented over HTTP resolves to candidates the store actually holds")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void aClaimResolvesToCandidates() throws Exception {
        HttpResponse<String> answer = ask("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"CHECKED"}]}"""
                .formatted(EID, KNOWN));

        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains(subjectId),
                "the person this store holds was not offered as a candidate, so the door "
                        + "reaches something other than this tenant's own identities: "
                        + answer.body());
        assertTrue(answer.body().contains("\"confidence\""),
                "a candidate must carry how sure the store is, or the caller cannot tell a "
                        + "match to act on from one to show somebody: " + answer.body());
    }

    @Test
    @Order(2)
    @DisplayName("a claim nobody checked never resolves on its own, however well it matches")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void anUnverifiedClaimIsEvidenceRatherThanAnAnswer() throws Exception {
        HttpResponse<String> answer = ask("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"ASSERTED"}]}"""
                .formatted(EID, KNOWN));

        assertEquals(200, answer.statusCode(), answer.body());
        assertFalse(answer.body().contains("\"CERTAIN\""),
                "an unverified claim resolved with certainty, so a number somebody read off "
                        + "a card is enough to attach one person's care to another's record: "
                        + answer.body());
    }

    @Test
    @Order(3)
    @DisplayName("a claim nobody here holds resolves to nothing, which is an answer rather "
            + "than a failure")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void anUnknownClaimIsAnsweredEmpty() throws Exception {
        HttpResponse<String> answer = ask("resolve", """
                {"claims":[{"system":"%s","value":"49999999999","verification":"AUTHENTICATED"}]}"""
                .formatted(EID));

        assertEquals(200, answer.statusCode(),
                "nobody here must not be reported as an error — a new person is the ordinary "
                        + "case, and a caller cannot tell a fault from a stranger: "
                        + answer.body());
        assertFalse(answer.body().contains(subjectId), answer.body());
    }

    @Test
    @Order(4)
    @DisplayName("a decision is recorded, and the next resolution says it was made")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void aDecisionIsRecordedAndRecalled() throws Exception {
        HttpResponse<String> decided = ask("adjudicate", """
                {"outcome":"CREATED","subject":"uus-inimene","rejected":["%s"],
                 "decidedBy":"vastuvotja",
                 "because":"different birth year on the document",
                 "claims":[{"system":"%s","value":"%s","verification":"CHECKED"}]}"""
                .formatted(subjectId, EID, KNOWN));
        assertEquals(201, decided.statusCode(), decided.body());

        HttpResponse<String> again = ask("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"CHECKED"}]}"""
                .formatted(EID, KNOWN));
        assertTrue(again.body().contains("\"previouslyRejected\":true"),
                "the candidate somebody examined and rejected is offered again as though for "
                        + "the first time, so the adjudication record is an archive nobody "
                        + "consults: " + again.body());
    }

    @Test
    @Order(5)
    @DisplayName("binding attaches an identity and says how strongly; withdrawing it takes "
            + "the identity and leaves the care")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void bindingAndWithdrawalAreBothReachable() throws Exception {
        assertEquals(201, ask("bind", """
                {"identity":"%s","subject":"patsient-1","assurance":"SUBSTANTIAL",
                 "actor":"vastuvotja","purpose":"registration"}"""
                .formatted(subjectId)).statusCode());

        HttpResponse<String> bound = ask("subject", "{\"subject\":\"patsient-1\"}");
        assertTrue(bound.body().contains(subjectId) && bound.body().contains("SUBSTANTIAL"),
                "the binding is not visible where a caller would look for it: " + bound.body());

        assertEquals(201, ask("unbind", """
                {"identity":"%s","subject":"patsient-1","actor":"vastuvotja",
                 "purpose":"correction","because":"wrong person"}"""
                .formatted(subjectId)).statusCode());

        HttpResponse<String> after = ask("subject", "{\"subject\":\"patsient-1\"}");
        assertFalse(after.body().contains(subjectId),
                "a withdrawn binding still stands, so a wrong identification cannot be "
                        + "taken back: " + after.body());
    }

    @Test
    @Order(6)
    @DisplayName("a subject may declare anonymity, and the store then refuses to bind them")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void anonymityIsDeclaredAndHonoured() throws Exception {
        assertEquals(201, ask("anonymity", """
                {"kind":"DECLARED","subject":"patsient-2","actor":"patsient-2",
                 "basis":"asked not to be identified"}""").statusCode());

        assertTrue(ask("subject", "{\"subject\":\"patsient-2\"}").body()
                        .contains("\"anonymous\":true"),
                "the declaration is invisible to a caller, so a well-meaning workflow cannot "
                        + "tell them from somebody merely not identified yet");

        HttpResponse<String> refused = ask("bind", """
                {"identity":"%s","subject":"patsient-2","assurance":"HIGH",
                 "actor":"vastuvotja","purpose":"registration"}"""
                .formatted(subjectId));
        assertEquals(409, refused.statusCode(),
                "somebody who declared they are not to be identified was bound anyway: "
                        + refused.body());
    }

    @Test
    @Order(7)
    @DisplayName("a credential that may write every resource type still may not identify "
            + "anybody, and none is refused before anything is looked up")
    @Proving(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE)
    void theDoorHasItsOwnScope() throws Exception {
        HttpResponse<String> broad = http.send(HttpRequest.newBuilder(door.resolve(
                                door.getPath() + "/resolve"))
                        .header("Authorization", "Bearer " + token("writes-all", "system/*.write"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"claims\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, broad.statusCode(),
                "a broad write grant reached identification, so binding — which "
                        + "de-anonymises — is available to anybody who may write a resource: "
                        + broad.body());
        assertTrue(broad.body().contains("identity"),
                "and the refusal must name what would have been needed: " + broad.body());

        HttpResponse<String> none = http.send(HttpRequest.newBuilder(door.resolve(
                                door.getPath() + "/resolve"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"claims\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, none.statusCode(), none.body());
    }

    private static HttpResponse<String> ask(String verb, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(door + "/" + verb))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token("tuvastus", "identity"))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String clientId, String... scopes) throws Exception {
        String secret = clientId + "-secret";
        manager.authority(TENANT).ensureClient(clientId, secret, List.of(scopes));
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
