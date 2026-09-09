package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A partner is a tenant that manages other tenants, and follows their work
 * without being able to read it.
 *
 * <p>The relation is declared when the managed tenant is created and is the
 * only thing that makes its work visible to anybody outside it: the managed
 * tenant trusts the partner's own authority because of it, and answers the
 * partner as the audience it declared — runs and the trail, never a document,
 * purposes only if the tenant says so. A tenant that declared no partner
 * refuses the same credential at the door. What the partner is shown is
 * assembled outside the store: this test reads two tenants' trails one at a
 * time, which is exactly what a tracking page would do.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class APartnerFollowsWorkIT {

    private static final String PARTNER = "kaskad";
    private static final String MANAGED = "praxis";
    private static final String OTHER = "kambium";
    private static final String STEP = "dbo.lab.assay";
    private static final StepDeclaration ASSAY = StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
            .taking("specimen", "https://meristem.example/shape/specimen");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-partner");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("APartnerFollowsWorkIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(PARTNER + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(PARTNER));
        // The relation, declared at creation: praxis is managed by kaskad.
        Files.writeString(dir.resolve(MANAGED + ".json"), """
                {"code":"%s","face":"r4","managedBy":"%s","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(MANAGED, PARTNER));
        Files.writeString(dir.resolve(OTHER + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(OTHER));
        UntilServed.scan(manager, PARTNER, MANAGED, OTHER);
        manager.authority(PARTNER).ensureClient("kaskad-support", "support-secret",
                List.of("system/*.read"));
        manager.authority(MANAGED).ensureClient("bench", "bench-secret", List.of("work/" + STEP));
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
    @DisplayName("a partner credential reads a managed tenant's journey by run, is refused by a "
            + "tenant it does not manage, and never receives a document or a purpose from either")
    @Proving(DboPromises.TEN_A_PARTNER_MANAGES_TENANTS)
    void thePartnerFollowsTheJourneyAndNothingElse() throws Exception {
        // Work in the managed tenant: a document, a run naming it, a hop.
        ObjectStore praxis = manager.runtime(MANAGED).orElseThrow().engine();
        String specimen = praxis.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"specimen-3f9a\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        Runs runs = new Runs(praxis);
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "followed",
                Map.of("specimen", "Basic/" + specimen));
        HttpLane bench = HttpLane.to(laneUri(MANAGED), () -> token(MANAGED, "bench", "bench-secret"),
                MANAGED, "bench", new Executor("bench", "1.0", "cloud.jengu.test", Scope.BASELINE));
        bench.introduce(ASSAY);
        Run held = bench.claim(run, Duration.ofMinutes(5)).orElseThrow();
        bench.inputs(held);
        // A read with a stated purpose, by the practice itself: on the trail
        // with its purpose, which is the practice's to reveal.
        Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
        try {
            praxis.get("Basic", specimen);
        } finally {
            Disclosure.clear();
        }

        // The partner, with its own tenant's credential, asks the managed
        // tenant for the run's journey.
        String partnerToken = token(PARTNER, "kaskad-support", "support-secret");
        HttpResponse<String> journey = get(MANAGED, "/AuditEvent?run="
                + URLEncoder.encode(held.key(), StandardCharsets.UTF_8), partnerToken);
        assertEquals(200, journey.statusCode(), journey.body());
        assertTrue(journey.body().contains("travel"),
                "the journey is made of travel entries: " + journey.body());
        assertTrue(journey.body().contains("\"value\":\"bench\""),
                "hops name who held the run, from the credential the authority validated: "
                        + journey.body());
        assertFalse(journey.body().contains("specimen-3f9a"),
                "no document content reaches the partner: " + journey.body());
        assertFalse(journey.body().contains("TREAT"),
                "a purpose is the practice's to reveal, and it did not: " + journey.body());

        // The document itself: not the partner's to read, and answered as
        // absent rather than forbidden.
        HttpResponse<String> document = get(MANAGED, "/Basic/" + specimen, partnerToken);
        assertTrue(document.statusCode() == 404 || document.statusCode() == 403,
                "a partner never receives a document: " + document.statusCode() + " "
                        + document.body());
        assertFalse(document.body().contains("specimen-3f9a"), document.body());

        // A tenant that declared no partner does not know this credential.
        HttpResponse<String> refused = get(OTHER, "/AuditEvent?run="
                + URLEncoder.encode(held.key(), StandardCharsets.UTF_8), partnerToken);
        assertEquals(401, refused.statusCode(),
                "the relation says which tenants a partner may read at all: " + refused.body());

        // And the practice's own credential still sees its purpose: the
        // omission is the audience's, not the trail's.
        HttpResponse<String> own = get(MANAGED, "/AuditEvent?entity=" + specimen,
                token(MANAGED, "tenant-bootstrap", provisioner.bootstrapClientSecret(MANAGED)));
        assertEquals(200, own.statusCode(), own.body());
        assertTrue(own.body().contains("TREAT"), "the tenant reads its own trail whole: " + own.body());
    }

    // ------------------------------------------------------------ fixtures

    private static URI laneUri(String tenant) {
        return URI.create("http://127.0.0.1:" + manager.port() + "/t/" + tenant + "/work");
    }

    private static HttpResponse<String> get(String tenant, String path, String token)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(tenant) + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/fhir+json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String tenant, String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + tenant + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
            assertTrue(m.find(), body);
            return m.group(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
