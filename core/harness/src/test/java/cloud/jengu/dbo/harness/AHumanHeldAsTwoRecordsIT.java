package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One human, held as a Person and as a Patient — the ordinary shape, and one
 * the tenant declares.
 *
 * <p>A national identity number identifies a Person here, and travels on the
 * Patient. The membrane claimed exclusivity for every person type it knows,
 * keyed by the record's own id, so whichever record presented the number
 * second was refused as a conflict against the first: an error naming two ids
 * the caller had never seen, for a collision it did not cause. The tenant had
 * already declared which type that number identifies, and the declaration was
 * not read.
 *
 * <p>What the two writes need is not the same answer. Refusing a second
 * <b>Person</b> the same number is a uniqueness policy the tenant asked for.
 * Refusing a Patient that merely carries it is refusing the model.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AHumanHeldAsTwoRecordsIT {

    private static final String CLINIC = "kaksrida";
    private static final String EID = "https://ee.ee/eid";
    private static final String NID = "47101010033";
    private static final String UNDECLARED = "https://kliinik.example/kaart";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-two-records");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AHumanHeldAsTwoRecordsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // Exactly the reported shape: Person is the type identified by that
        // system; Patient is internal and merely carries the number.
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"audit":{"level":"writes"},
                 "types":[
                  {"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Practitioner","identity":"internal","handling":"operational"}]}"""
                .formatted(CLINIC, EID));
        UntilServed.scan(manager, CLINIC);
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
    @DisplayName("a Patient may carry the number that identifies a Person, and stakes no "
            + "claim on it — while a second Person is still refused")
    @Proving({DboPromises.PDI_STRUCTURAL_VAULT, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void aRecordThatCarriesTheNumberDoesNotClaimIt() throws Exception {
        String token = token();

        assertEquals(201, post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm"}]}""".formatted(EID, NID), token).statusCode());

        HttpResponse<String> patient = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm"}]}""".formatted(EID, NID), token);
        assertEquals(201, patient.statusCode(),
                "the Patient carries the number that identifies the Person and was refused as "
                        + "a conflict against it, so a human cannot be held as both — which is "
                        + "the ordinary way of holding one: " + patient.body());
        String patientId = patient.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");

        // The same write arriving in the order that made this look like an
        // update problem: a record given the number after it existed.
        String later = post("/Patient", """
                {"resourceType":"Patient","name":[{"family":"Ilves"}]}""", token)
                .headers().firstValue("Location").orElseThrow().replaceAll(".*/([^/]+)$", "$1");
        assertEquals(200, put("/Patient/" + later, """
                {"resourceType":"Patient","id":"%s",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Ilves"}]}""".formatted(later, EID, NID), token).statusCode(),
                "a record given the number later is the same write as one created with it, and "
                        + "only one of the two was refused");

        // Written back unchanged, because a record re-asserting its own claim
        // is not a second claimant. This always worked; it is asserted so it
        // keeps working.
        assertEquals(200, put("/Patient/" + patientId, """
                {"resourceType":"Patient","id":"%s",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm-Kask"}]}""".formatted(patientId, EID, NID), token)
                .statusCode());

        // And the policy the tenant DID ask for still holds: that number
        // identifies a Person, so it identifies exactly one of them.
        HttpResponse<String> twin = post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Kask"}]}""".formatted(EID, NID), token);
        assertEquals(409, twin.statusCode(),
                "a second Person claiming one human's national number was accepted, so the "
                        + "store merged two people by silence — the thing the declaration "
                        + "exists to prevent: " + twin.body());
    }

    @Test
    @DisplayName("a record the tenant links to a person is that person, so erasing them "
            + "reaches it — and a link that would join two identified people is refused")
    @Proving({DboPromises.PDI_CRYPTO_SHREDDING, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void aLinkedRecordIsTheSameHuman() throws Exception {
        String token = token();

        // A record carrying a name and no identifier of its own: identifying
        // data, sealed under a person nobody can reach by any number.
        String bare = post("/Patient", """
                {"resourceType":"Patient","name":[{"family":"Sepp"}]}""", token)
                .headers().firstValue("Location").orElseThrow().replaceAll(".*/([^/]+)$", "$1");

        // The tenant says who they are, by writing it down.
        HttpResponse<String> person = post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"38804010005"}],
                 "name":[{"family":"Sepp"}],
                 "link":[{"target":{"reference":"Patient/%s"}}]}"""
                .formatted(EID, bare), token);
        assertEquals(201, person.statusCode(), person.body());
        String personId = person.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");

        // Erasing the human, asked for by the record that names them.
        HttpResponse<String> erased = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/erasure"))
                        .header("Authorization", "Bearer " + eraser())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"subject\":\"Person/" + personId + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, erased.statusCode(), erased.body());

        // The linked record is pseudonymous too. Its identifying data was
        // sealed under a person of its own, and while a person was a row that
        // key survived an erasure nobody knew to ask for twice.
        HttpResponse<String> after = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/Patient/" + bare))
                        .header("Authorization", "Bearer " + token)
                        .header("Purpose-Of-Use", "TREAT").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertFalse(after.body().contains("Sepp"),
                "the linked record survived the erasure of the human it belongs to, so "
                        + "destroying the key left a copy readable: " + after.body());

        // And a link that would join two people who are each identified is
        // refused rather than decided here. The target is claimed because a
        // Person already named it as theirs.
        String theirs = post("/Patient", """
                {"resourceType":"Patient","name":[{"family":"Oja"}]}""", token)
                .headers().firstValue("Location").orElseThrow().replaceAll(".*/([^/]+)$", "$1");
        assertEquals(201, post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"38804010006"}],
                 "name":[{"family":"Oja"}],
                 "link":[{"target":{"reference":"Patient/%s"}}]}"""
                .formatted(EID, theirs), token).statusCode());

        HttpResponse<String> joined = post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"38804010007"}],
                 "name":[{"family":"Teine"}],
                 "link":[{"target":{"reference":"Patient/%s"}}]}"""
                .formatted(EID, theirs), token);
        assertEquals(409, joined.statusCode(),
                "a link joined two people who each hold an identity of their own, so the "
                        + "store decided which human they are: " + joined.body());
    }

    @Test
    @DisplayName("the record that carries somebody's number is found by it, even where its "
            + "type claims nothing")
    @Proving({DboPromises.PDI_EXACT_RESOLUTION, DboPromises.SRCH_HONEST_CAPABILITY})
    void theCarriedNumberStillFindsTheRecord() throws Exception {
        String token = token();
        assertEquals(201, post("/Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"39501020304"}],
                 "name":[{"family":"Leidja"}]}""".formatted(EID), token).statusCode());
        assertEquals(201, post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"39501020304"}],
                 "name":[{"family":"Leidja"}]}""".formatted(EID), token).statusCode());

        HttpResponse<String> found = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/Patient?identifier="
                                + URLEncoder.encode(EID + "|39501020304", StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token)
                        .header("Purpose-Of-Use", "TREAT").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("\"resourceType\":\"Patient\""),
                "the record carrying the number answered an empty bundle, and empty reads as "
                        + "nobody here — which is how a caller that meant to recognise "
                        + "somebody mints a second identity instead: " + found.body());
    }

    @Test
    @DisplayName("every way a person-type record can carry a value, a lookup by that value "
            + "finds it or refuses — and never comes back empty")
    @Proving({DboPromises.PDI_EXACT_RESOLUTION, DboPromises.SRCH_HONEST_CAPABILITY})
    void noneOfTheseAnswersEmpty() throws Exception {
        String token = token();
        List<String> silent = new ArrayList<>();

        // the type identified by that system, claiming the value
        check("Person", EID, "30101010001", token, silent);
        // a type declared internal, carrying a value another type is identified by
        check("Patient", EID, "30101010002", token, silent);
        check("Practitioner", EID, "30101010003", token, silent);
        // a value NOTHING here is identified by: held, indexed, and nobody's identity
        check("Patient", UNDECLARED, "card-4471", token, silent);

        assertTrue(silent.isEmpty(),
                "these are held by this store and a lookup for them answered an empty "
                        + "bundle. Empty reads as nobody here, and a caller acting on it "
                        + "creates the person again:\n  " + String.join("\n  ", silent));
    }

    /** Writes one record carrying the value, then asks for it back. */
    private void check(String type, String system, String value, String token,
            List<String> silent)
            throws Exception {
        HttpResponse<String> written = post("/" + type, """
                {"resourceType":"%s","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Otsitav"}]}""".formatted(type, system, value), token);
        assertEquals(201, written.statusCode(), written.body());
        String id = written.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");

        HttpResponse<String> found = http.send(HttpRequest.newBuilder(
                        URI.create(base() + "/fhir/" + type + "?identifier="
                                + URLEncoder.encode(system + "|" + value, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token)
                        .header("Purpose-Of-Use", "TREAT").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // A refusal is a fine answer: it says the store holds this and cannot
        // match on it, which a caller can act on. Silence is the failure.
        if (found.statusCode() == 200 && !found.body().contains(id)) {
            silent.add(type + " carrying " + system + "|" + value
                    + " (record " + id + ") -> " + found.body().replaceAll("\\s+", " "));
        }
    }

    @Test
    @DisplayName("a conditional create asks the question a lookup answers, so running it "
            + "twice leaves one record and not a copy per boot")
    @Proving({DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS, DboPromises.PDI_EXACT_RESOLUTION})
    void aConditionalCreateFindsWhatALookupWouldFind() throws Exception {
        String token = token();
        String question = "identifier="
                + URLEncoder.encode(EID + "|39002020202", StandardCharsets.UTF_8);
        String document = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"39002020202"}],
                 "name":[{"family":"Uks"}]}""".formatted(EID);

        HttpResponse<String> first = conditionalCreate("/Patient", document, question, token);
        assertEquals(201, first.statusCode(), first.body());
        String id = first.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/([^/]+)$", "$1");

        HttpResponse<String> again = conditionalCreate("/Patient", document, question, token);
        assertTrue(again.headers().firstValue("Location").orElse("").contains(id),
                "the second asking made a second record, so the store answers a question a "
                        + "lookup can answer with 'nothing here' and writes another copy — "
                        + "once per boot, for as long as it boots: "
                        + again.statusCode() + " " + again.headers()
                                .firstValue("Location").orElse("(no location)"));
    }

    private HttpResponse<String> conditionalCreate(String path, String body, String question,
            String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .header("If-None-Exist", question)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** What the erasure door admits: its own scope, not a broad write grant. */
    private String eraser() throws Exception {
        manager.authority(CLINIC).ensureClient("desk", "desk-secret", List.of("erasure"));
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=desk&client_secret="
                                        + URLEncoder.encode("desk-secret", StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static String brief(HttpResponse<String> r) {
        String b = r.body().replaceAll("\\s+", " ");
        return b.length() > 300 ? b.substring(0, 300) : b;
    }

    private String token() throws Exception {
        manager.authority(CLINIC).ensureClient("emr", "emr-secret",
                List.of("system/*.read", "system/*.write"));
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=emr&client_secret="
                                        + URLEncoder.encode("emr-secret", StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC;
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/fhir" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
