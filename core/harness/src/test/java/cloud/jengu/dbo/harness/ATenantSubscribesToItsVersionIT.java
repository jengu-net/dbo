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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant takes its version from a root the way it takes its terminology
 * from a zone — and is not served until it has.
 *
 * <p>No reconciler runs in this test. That is the proof: the only way the
 * subscriber can hold its version's definitions the instant it is served is
 * for bring-up to have drained the face chain before publishing it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ATenantSubscribesToItsVersionIT {

    private static final String ROOT = "tuum-r4";
    private static final String SUBSCRIBER = "haru";
    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static long contextBuildsBefore;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-face-chain");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantSubscribesToItsVersionIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ROOT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                .formatted(ROOT));
        Files.writeString(dir.resolve(SUBSCRIBER + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Observation","identity":"internal","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(SUBSCRIBER, ROOT));
        contextBuildsBefore = cloud.jengu.dbo.fhir.element.ElementVersion.contextBuilds();
        UntilServed.scan(manager, ROOT);
        long began = System.currentTimeMillis();
        UntilServed.scan(manager, SUBSCRIBER);
        System.out.println("MEASURED subscriber bring-up incl. face drain: "
                + (System.currentTimeMillis() - began) + "ms");
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
    @DisplayName("the instant a subscriber is served it holds its version's definitions, "
            + "because bring-up would not publish it before they had streamed")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void servedMeansTheDefinitionsAreHere() throws Exception {
        String token = token(SUBSCRIBER);
        HttpResponse<String> patient = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/StructureDefinition?url="
                                + URLEncoder.encode(PATIENT, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, patient.statusCode(), patient.body());
        assertTrue(patient.body().contains("\"type\":\"Patient\""),
                "the subscriber was served without its version's definitions — no reconciler "
                        + "runs here, so nothing else could have brought them: "
                        + patient.body());
    }

    @Test
    @DisplayName("neither the root nor its subscriber built the carried toolchain context: "
            + "they were brought up, served a read and accepted a write from their records")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void nobodyBuiltTheCarriedContext() throws Exception {
        manager.authority(SUBSCRIBER).ensureClient("writer", "writer-secret",
                List.of("system/*.read", "system/*.write"));
        String token = http.send(HttpRequest.newBuilder(URI.create(base(SUBSCRIBER) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=writer&client_secret=writer-secret"))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/Observation"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"Observation","status":"final",
                                 "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]},
                                 "valueQuantity":{"value":72,"unit":"/min"}}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, accepted.statusCode(), accepted.body());
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/Observation"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"resourceType\":\"Observation\",\"status\":\"nonesuch\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(422, refused.statusCode(),
                "the subscriber validates against nothing: " + refused.body());
        assertEquals(contextBuildsBefore, cloud.jengu.dbo.fhir.element.ElementVersion.contextBuilds(),
                "a tenant on a face built the carried toolchain context instead of reading its records");
    }

    @Test
    @DisplayName("a code outside a required binding is refused by name, answered from the "
            + "code system the subscriber took from its root — and a code in it is accepted")
    @Proving(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)
    void aBindingIsAnsweredFromTheRecordsTheSubscriberHolds() throws Exception {
        manager.authority(SUBSCRIBER).ensureClient("writer", "writer-secret",
                List.of("system/*.read", "system/*.write"));
        String token = http.send(HttpRequest.newBuilder(URI.create(base(SUBSCRIBER) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=writer&client_secret=writer-secret"))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> unicorn = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/Patient"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"resourceType\":\"Patient\",\"gender\":\"unicorn\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(422, unicorn.statusCode(),
                "a gender outside the required binding was accepted: " + unicorn.body());
        assertTrue(unicorn.body().contains("unicorn"),
                "the refusal does not name the code: " + unicorn.body());
        HttpResponse<String> female = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/Patient"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, female.statusCode(), female.body());
        assertEquals(contextBuildsBefore, cloud.jengu.dbo.fhir.element.ElementVersion.contextBuilds(),
                "the binding was answered by building the carried context");
    }

    @Test
    @DisplayName("a second tenant on the face builds no base of its own and costs the heap little: "
            + "the version's definitions are shared, the tenant's own are its own")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void aSecondTenantOnTheFaceSharesTheBase() throws Exception {
        long basesBefore = cloud.jengu.dbo.fhir.element.ElementVersion.baseBuilds();
        long contextsBefore = cloud.jengu.dbo.fhir.element.ElementVersion.contextBuilds();
        long heapBefore = settledHeapMb();
        Files.writeString(dir.resolve("teisik.json"), """
                {"code":"teisik","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(ROOT));
        UntilServed.scan(manager, "teisik");
        String token = token("teisik");
        HttpResponse<String> patient = http.send(HttpRequest.newBuilder(
                        URI.create(base("teisik") + "/fhir/Patient?gender=female"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, patient.statusCode(), patient.body());
        // Printed, not asserted: a heap delta after a collection still counts
        // what is softly reachable — the drain's validator pools and toolchain
        // caches — and was measured at +134 MB that vanished under pressure.
        // What is asserted is the structure the number would have caught.
        System.out.println("MEASURED second tenant on the face: heap +" + (settledHeapMb() - heapBefore)
                + "MB settled, bases built " + (cloud.jengu.dbo.fhir.element.ElementVersion.baseBuilds() - basesBefore));
        assertEquals(basesBefore, cloud.jengu.dbo.fhir.element.ElementVersion.baseBuilds(),
                "a second tenant on the face built a base of its own");
        assertEquals(contextsBefore, cloud.jengu.dbo.fhir.element.ElementVersion.contextBuilds(),
                "a second tenant on the face built the carried toolchain context");
    }

    private static long settledHeapMb() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    @Test
    @DisplayName("the version's terminology reaches a subscriber through the chain, and the "
            + "subscriber is given nothing from the carried packages")
    @Proving(DboPromises.TERM_BINDINGS_ANSWERED_FROM_RECORDS)
    void theTerminologyBaselineArrivesThroughTheChain() throws Exception {
        String token = token(SUBSCRIBER);
        HttpResponse<String> lookup = http.send(HttpRequest.newBuilder(
                        URI.create(base(SUBSCRIBER) + "/fhir/CodeSystem/$lookup?system="
                                + URLEncoder.encode("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus",
                                        StandardCharsets.UTF_8) + "&code=M"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, lookup.statusCode(), lookup.body());
        assertTrue(lookup.body().contains("Married"),
                "a code system from the terminology package is not held by the subscriber: " + lookup.body());
        // and it came from the root, not from a package read here: the
        // baseline import leaves a marker system behind, and there is none
        String url = SharedPostgres.urlFor("x").replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + SUBSCRIBER);
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "select count(*) from definitions.term_system where url like 'urn:dbo:terminology-baseline:%'");
             java.sql.ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getLong(1),
                    "the subscriber imported the terminology baseline from a carried package");
        }
    }

    @Test
    @DisplayName("a face chain that does not carry the code systems is refused before anything "
            + "is drained, naming what it lacks")
    @Proving(DboPromises.TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED)
    void aChainWithoutTheCodeSystemsIsRefusedByName() throws Exception {
        Files.writeString(dir.resolve("poolik.json"), """
                {"code":"poolik","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter","ValueSet"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"}]}"""
                .formatted(ROOT));
        manager.scanOnce();
        String trouble = manager.troubles().get("poolik");
        assertTrue(trouble != null && trouble.contains("CodeSystem"),
                "a chain without code systems was accepted, or refused for another reason: "
                        + manager.troubles());
    }

    @Test
    @DisplayName("a tenant on one face subscribing to a root of another is refused as a "
            + "declaration disagreeing with itself — a face chain does not convert")
    void aRootOfAnotherFaceIsRefused() throws Exception {
        Files.writeString(dir.resolve("vale.json"), """
                {"code":"vale","face":"r5","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","face":true,
                                  "types":["StructureDefinition","SearchParameter"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"}]}"""
                .formatted(ROOT));
        manager.scanOnce();
        String trouble = manager.troubles().get("vale");
        assertTrue(trouble != null && trouble.contains("does not convert"),
                "an r5 tenant took its definitions from an r4 root, or failed for some other "
                        + "reason than the one that matters: " + manager.troubles());
    }

    @Test
    @DisplayName("two dependencies declared as the face chain are refused before anything "
            + "is built, because a tenant is one version")
    void twoFaceChainsAreRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> cloud.jengu.dbo.tenant.TenantSpec.parse("""
                        {"code":"kaks","face":"r4","audit":{"level":"none"},
                         "dependencies":[
                           {"name":"a","face":true,"types":["StructureDefinition"]},
                           {"name":"b","face":true,"types":["StructureDefinition"]}],
                         "types":[
                          {"name":"StructureDefinition","identity":"canonical","handling":"replicated"}]}"""));
    }

    private static String token(String code) throws Exception {
        manager.authority(code).ensureClient("reader", "reader-secret", List.of("system/*.read"));
        return http.send(HttpRequest.newBuilder(URI.create(base(code) + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=reader&client_secret="
                                        + "reader-secret")).build(),
                HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static String base(String code) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + code;
    }
}
