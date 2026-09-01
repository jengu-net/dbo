package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.PersonErasure;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * A consumer can ask for an erasure, and is answered with a run.
 *
 * <p>The erasure itself was proven long ago. What was missing was any way to
 * <b>ask</b>: the shred was reachable only from inside the runtime, so a
 * consumer reaching this store over HTTP could delete a row and call it an
 * erasure, which is the receipt nobody should ever write.
 *
 * <p>So this drives the real door over real HTTP with a real token, because a
 * process built and mounted nowhere is the failure this repository keeps
 * paying for, and because the claim under test is precisely reachability.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnErasureIsAskedForAndAnsweredIT {

    private static final String TENANT = "erasing";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI door;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-erasure");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnErasureIsAskedForAndAnsweredIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        door = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/erasure");
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
    @DisplayName("asking to erase somebody this store never held still answers with a run, "
            + "and the run says nobody was there")
    @Proving(DboPromises.PDI_ERASURE_IS_A_RUN)
    void anUnknownSubjectIsAnsweredWithARun() throws Exception {
        HttpResponse<String> answer = ask("Patient/" + java.util.UUID.randomUUID(),
                token("eraser", "erasure"));

        assertEquals(202, answer.statusCode(), answer.body());
        assertTrue(answer.body().contains("\"known\":0"),
                "the run reports that this store never held them, which is a true answer to "
                        + "the request and a different one from 'the key is destroyed': "
                        + answer.body());
        assertTrue(answer.body().contains("\"open\":false"),
                "and it closed rather than waiting for somebody: " + answer.body());
        assertTrue(answer.body().contains("dbo.erasure/shred/"),
                "the caller is given the run it can show afterwards: " + answer.body());
    }

    @Test
    @DisplayName("a credential that may write every resource type still may not erase a "
            + "person — the door has its own scope and nothing else grants it")
    @Proving(DboPromises.PDI_ERASURE_IS_A_RUN)
    void writingEverythingDoesNotIncludeErasing() throws Exception {
        HttpResponse<String> refused = ask("Patient/" + java.util.UUID.randomUUID(),
                token("writes-everything", "system/*.write"));

        assertEquals(403, refused.statusCode(),
                "a broad write grant must not reach the most consequential act here: "
                        + refused.body());
        assertTrue(refused.body().contains("erasure"),
                "and the refusal names what would have been needed: " + refused.body());
    }

    @Test
    @DisplayName("asking without any credential is refused before anything is looked up")
    @Proving(DboPromises.PDI_ERASURE_IS_A_RUN)
    void anonymousAskingIsRefused() throws Exception {
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(door)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"Patient/whoever\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("the run's key is the person and nothing else, so asking twice is the same "
            + "run rather than a second account of one erasure")
    @Proving(DboPromises.PDI_ERASURE_IS_A_RUN)
    void theRunIsKeyedByThePerson() {
        String person = java.util.UUID.randomUUID().toString();

        assertEquals(PersonErasure.key(person), PersonErasure.key(person),
                "the key is derived from the person, so a repeat finds the same run");
        assertTrue(PersonErasure.key(person).contains(person));
        assertFalse(PersonErasure.key(person).contains("Patient"),
                "and it names the person's own id rather than a face's reference");
    }

    @Test
    @DisplayName("the step declares the points a half-finished erasure can stop at, in the "
            + "order they happen")
    @Proving(DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT)
    void theStepDeclaresWhereItCanStop() {
        assertEquals(List.of("key-destroyed", "index-removed", "ledger-written"),
                PersonErasure.declaration().milestones(),
                "an erasure that destroyed the key and then failed before the ledger has done "
                        + "the irreversible half without the half that makes a restore safe, "
                        + "and an operator has to be able to see exactly that");
        assertTrue(PersonErasure.declaration().slots().containsKey("subject"),
                "the subject is a declared input rather than a parameter somebody invented");
    }

    private static HttpResponse<String> ask(String subject, String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(door)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"" + subject + "\"}")).build(),
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
