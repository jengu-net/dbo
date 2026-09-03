package cloud.jengu.dbo.harness;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-CLINICAL-RECORD, walked in order.
 *
 * <p>The clinic from {@code US-DBO-TENANT-OPENING} is open and Maarja is
 * working in it. This is the store doing the thing it exists for: a patient
 * who arrives twice is one patient, a visit lands whole or not at all, a code
 * means what this clinic's terminology says it means, everything can be found
 * again, and none of it can be quietly edited afterwards.
 *
 * <p><b>One clinic, one afternoon, in dependency order.</b> Each leg is set up
 * by the leg before it. The assertions are about what the previous step just
 * did — this patient, this bundle, this code — never about how many records
 * the tenant contains, because a story that counts is a story whose later
 * scenes break its earlier ones.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheClinicRecordsCareAndAccountsForItIT {

    private static final String CLINIC = "kevadravi";
    /** The national identifier the clinic knows its patients by. */
    private static final String EID = "https://ee.ee/eid";
    /** A code system this clinic holds itself, rather than one it imported. */
    private static final String LOCAL = "https://kevadkliinik.ee/fs/severity";

    private static final String TYPES = """
            [{"name":"Patient","identity":"identifier","systems":["%s"],"handling":"operational"},
             {"name":"Encounter","identity":"internal","handling":"operational"},
             {"name":"Observation","identity":"internal","handling":"operational"},
             {"name":"CodeSystem","identity":"canonical","handling":"operational"},
             {"name":"ValueSet","identity":"canonical","handling":"operational"}]""".formatted(EID);

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String token;
    static String patientId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-clinical-record");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheClinicRecordsCareAndAccountsForItIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"full"},"types":%s}"""
                .formatted(CLINIC, TYPES));
        UntilServed.scan(manager, CLINIC);
        manager.authority(CLINIC).ensureClient("kevad-emr", "emr-secret",
                List.of("system/*.read", "system/*.write"));
        token = token("kevad-emr", "emr-secret");
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

    // ── one patient, however many times she arrives ──

    @Test
    @Order(1)
    @DisplayName("a patient is written and reads back as what was written, not as something "
            + "the store reassembled from columns")
    @Proving({DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_READ_YOUR_WRITES,
            DboPromises.CORE_DECLARED_TRUTH_FORM})
    void whatWasWrittenIsWhatIsRead() throws Exception {
        HttpResponse<String> created = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(EID));
        assertEquals(201, created.statusCode(), created.body());
        patientId = created.body().replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        HttpResponse<String> read = get("/Patient/" + patientId);
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"family\":\"Tamm\""), read.body());
        assertTrue(read.body().contains("\"birthDate\":\"1990-01-01\""),
                "an element nothing indexes came back anyway, because the payload is the "
                        + "record rather than a projection of it: " + read.body());
    }

    @Test
    @Order(2)
    @DisplayName("Liis arrives a second time and is the same patient, because the identifier "
            + "her clinic knows her by decides that and nothing else does")
    @Proving({DboPromises.CORE_CONDITIONAL_UPSERT, DboPromises.CORE_EXTERNAL_IDENTIFIERS,
            DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void thesamePatientArrivingTwiceIsOnePatient() throws Exception {
        String same = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}""".formatted(EID);

        HttpResponse<String> again = http.send(HttpRequest.newBuilder(URI.create(fhir("/Patient")))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .header("If-None-Exist", "identifier=" + EID + "|49001010000")
                        .POST(HttpRequest.BodyPublishers.ofString(same)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, again.statusCode(),
                "200 rather than 201: she already exists and this is her — " + again.body());
        assertTrue(again.body().contains(patientId),
                "and it is the same record rather than a second one: " + again.body());

        // A different person at the same clinic is a different record, and no
        // amount of matching names changes that: identity is the declared
        // identifier, never a resemblance the store decided on its own.
        HttpResponse<String> namesake = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"49001010001"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}""".formatted(EID));
        assertEquals(201, namesake.statusCode(), namesake.body());
        assertFalse(namesake.body().contains("\"id\":\"" + patientId + "\""),
                "two people with one name became one record");
    }

    @Test
    @Order(3)
    @DisplayName("correcting her birth date keeps the version that was wrong, because what "
            + "the record said last year is a fact about last year")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void everyVersionIsKept() throws Exception {
        HttpResponse<String> corrected = put("/Patient/" + patientId, """
                {"resourceType":"Patient","id":"%s",
                 "identifier":[{"system":"%s","value":"49001010000"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-02"}""".formatted(patientId, EID));
        assertEquals(200, corrected.statusCode(), corrected.body());

        HttpResponse<String> history = get("/Patient/" + patientId + "/_history");
        assertEquals(200, history.statusCode(), history.body());
        assertTrue(history.body().contains("1990-01-01") && history.body().contains("1990-01-02"),
                "both the corrected value and the one it corrected are in the history: "
                        + history.body());
    }

    // ── a visit lands whole, or not at all ──

    @Test
    @Order(4)
    @DisplayName("a visit arrives as one document and lands whole, naming the patient by the "
            + "identifier the sender knows rather than by an id only this store has")
    @Proving({DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE, DboPromises.CORE_CONDITIONAL_REFERENCES,
            DboPromises.CORE_REFERENCE_EDGES})
    void aVisitLandsWhole() throws Exception {
        HttpResponse<String> visit = post("", """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"fullUrl":"urn:uuid:enc-1",
                   "resource":{"resourceType":"Encounter","status":"finished",
                     "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode",
                              "code":"AMB"},
                     "subject":{"reference":"Patient?identifier=%s|49001010000"}},
                   "request":{"method":"POST","url":"Encounter"}},
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Body temperature"},
                     "subject":{"reference":"Patient?identifier=%s|49001010000"},
                     "encounter":{"reference":"urn:uuid:enc-1"},
                     "valueQuantity":{"value":37.4}},
                   "request":{"method":"POST","url":"Observation"}}]}"""
                .formatted(EID, EID));

        assertEquals(200, visit.statusCode(), visit.body());

        // The response carries where each entry landed. What the story is
        // about is what landed IN them: the question the sender asked —
        // whoever has this identifier — is answered once, at write time, and
        // stored as a concrete reference. A record that kept the question
        // would mean something different every time it was read.
        String observation = visit.body().replaceAll(
                "(?s).*\"location\"\\s*:\\s*\"(Observation/[^/\"]+)[^\"]*\".*", "$1");
        HttpResponse<String> stored = get("/" + observation);
        assertEquals(200, stored.statusCode(), stored.body());
        assertTrue(stored.body().contains("Patient/" + patientId),
                "the identifier the sender knew was left in the record as a question rather "
                        + "than resolved to the patient this store holds: " + stored.body());
    }

    @Test
    @Order(5)
    @DisplayName("a visit the store cannot honour lands nothing at all, and a batch of "
            + "unrelated writes answers for each one separately")
    @Proving({DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE,
            DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY})
    void allOfItOrNoneOfIt() throws Exception {
        // A transaction whose second entry names a patient nobody has: the
        // first entry must not survive it.
        HttpResponse<String> doomed = post("", """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Pulse, from a transaction that must not land"},
                     "subject":{"reference":"Patient?identifier=%s|49001010000"},
                     "valueQuantity":{"value":61}},
                   "request":{"method":"POST","url":"Observation"}},
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Pulse"},
                     "subject":{"reference":"Patient?identifier=%s|nobody-has-this"},
                     "valueQuantity":{"value":62}},
                   "request":{"method":"POST","url":"Observation"}}]}"""
                .formatted(EID, EID));
        assertNotEquals(200, doomed.statusCode(),
                "the transaction was accepted despite an entry it could not honour: "
                        + doomed.body());

        HttpResponse<String> survived = get(
                "/Observation?code:text=" + URLEncoder.encode(
                        "from a transaction that must not land", StandardCharsets.UTF_8));
        assertTrue(survived.statusCode() != 200 || !survived.body().contains("must not land"),
                "the first entry of a refused transaction is in the store: " + survived.body());

        // A batch makes no such promise, and says so per entry.
        HttpResponse<String> batch = post("", """
                {"resourceType":"Bundle","type":"batch","entry":[
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Respiratory rate"},
                     "subject":{"identifier":{"system":"%s","value":"49001010000"}},
                     "valueQuantity":{"value":14}},
                   "request":{"method":"POST","url":"Observation"}},
                  {"resource":{"resourceType":"Observation"},
                   "request":{"method":"POST","url":"Observation"}}]}""".formatted(EID));
        assertEquals(200, batch.statusCode(), batch.body());
        assertTrue(batch.body().contains("\"201\"") || batch.body().contains("201 "),
                "the entry that could land, landed: " + batch.body());
        assertTrue(batch.body().contains("4") && batch.body().contains("outcome")
                        || batch.body().contains("400"),
                "and the one that could not says so in its own entry: " + batch.body());
    }

    @Test
    @Order(5)
    @DisplayName("two people editing Liis at once do not silently overwrite each other: the "
            + "second write is refused because it was made against a version that has moved")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void aWriteAgainstAStaleVersionIsRefused() throws Exception {
        String current = get("/Patient/" + patientId).body();
        String stale = "W/\"1\"";

        HttpResponse<String> late = http.send(HttpRequest.newBuilder(
                        URI.create(fhir("/Patient/" + patientId)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/fhir+json")
                .header("If-Match", stale)
                .PUT(HttpRequest.BodyPublishers.ofString(current)).build(),
                HttpResponse.BodyHandlers.ofString());

        // 409 or 412 — the store says which, and either is a refusal. What
        // matters is that it is one: a write accepted against a version that
        // has moved silently discards the edit it was based on.
        assertTrue(late.statusCode() == 409 || late.statusCode() == 412,
                "a write made against a version that has since moved was accepted, so the "
                        + "edit it was based on is gone and nobody was told: "
                        + late.statusCode() + " " + late.body());
        assertTrue(late.body().contains("conflict"),
                "and the refusal says what kind it is, so a client knows to re-read rather "
                        + "than to retry: " + late.body());
    }

    @Test
    @Order(6)
    @DisplayName("deleting a patient frees the identifier they were claiming, so somebody "
            + "registered by mistake can be registered again properly")
    @Proving({DboPromises.CORE_EXTERNAL_IDENTIFIERS, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void deletingFreesTheIdentityClaim() throws Exception {
        String mistaken = "49001019999";
        String created = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Vale","given":["Sisestus"]}]}"""
                .formatted(EID, mistaken), null).body();
        String wrongId = created.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        assertTrue(delete("/Patient/" + wrongId).statusCode() < 300, "the mistake is removed");

        // The claim went with them. Without that, a mis-registration would
        // burn an identifier for good and the correction would have to invent
        // a different one.
        HttpResponse<String> again = post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Oige","given":["Sisestus"]}]}"""
                .formatted(EID, mistaken), null);
        assertEquals(201, again.statusCode(),
                "the identifier is still claimed by a deleted record, so a mis-registration "
                        + "burns it permanently: " + again.body());
    }

    // ── what a code means here ──

    @Test
    @Order(8)
    @DisplayName("a code means what this clinic's own terminology says, and a system the "
            + "clinic does not hold is unresolvable rather than invalid")
    @Proving({DboPromises.TERM_NATIVE_FORM, DboPromises.TERM_EVERY_TENANT_ANSWERS,
            DboPromises.TERM_OPERATIONS_FROM_NATIVE_FORM,
            DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID})
    void aCodeMeansWhatThisClinicSaysItMeans() throws Exception {
        HttpResponse<String> ingested = post("/CodeSystem", """
                {"resourceType":"CodeSystem","url":"%s",
                 "status":"active","content":"complete","version":"1.0",
                 "concept":[{"code":"mild","display":"Mild"},
                            {"code":"severe","display":"Severe"}]}""".formatted(LOCAL));
        assertTrue(ingested.statusCode() == 200 || ingested.statusCode() == 201,
                ingested.body());

        HttpResponse<String> lookup = get("/CodeSystem/$lookup?system="
                + URLEncoder.encode(LOCAL, StandardCharsets.UTF_8) + "&code=severe");
        assertEquals(200, lookup.statusCode(), lookup.body());
        assertTrue(lookup.body().contains("Severe"),
                "the clinic answers from its own concepts rather than from a shared server: "
                        + lookup.body());

        HttpResponse<String> foreign = get("/CodeSystem/$lookup?system="
                + URLEncoder.encode("https://example.org/never-loaded", StandardCharsets.UTF_8)
                + "&code=whatever");
        assertNotEquals(200, foreign.statusCode(),
                "a system this clinic never loaded answered as though it knew it");
        assertFalse(foreign.body().toLowerCase(java.util.Locale.ROOT).contains("invalid"),
                "unresolvable and invalid are different answers: one says this store's "
                        + "content is incomplete, the other says the caller's data is wrong — "
                        + foreign.body());
    }

    // ── finding it again ──

    @Test
    @Order(9)
    @DisplayName("the store says what it can search and refuses the rest, rather than "
            + "answering a narrower question than it was asked")
    @Proving({DboPromises.SRCH_HONEST_CAPABILITY, DboPromises.SRCH_STRICT_BY_DEFAULT})
    void theStoreSaysWhatItCanSearch() throws Exception {
        HttpResponse<String> capability = get("/metadata");
        assertEquals(200, capability.statusCode(), capability.body());
        assertTrue(capability.body().contains("\"identifier\""),
                "the capability statement names the parameters that actually work: "
                        + capability.body().substring(0, Math.min(400, capability.body().length())));

        HttpResponse<String> unsupported = get("/Patient?favourite-colour=blue");
        assertEquals(400, unsupported.statusCode(),
                "a parameter the store does not implement was ignored rather than refused, "
                        + "which answers a different question than the caller asked: "
                        + unsupported.body());
    }

    @Test
    @Order(10)
    @DisplayName("Liis is found again by the identifier she was written under, and what "
            + "belongs to her visit comes back with her")
    @Proving({DboPromises.SRCH_TIER1_PARITY, DboPromises.CORE_REFERENCE_EDGES})
    void findingHerAgain() throws Exception {
        HttpResponse<String> found = get("/Patient?identifier="
                + URLEncoder.encode(EID + "|49001010000", StandardCharsets.UTF_8));
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains(patientId),
                "she is found by the identifier her clinic knows her by: " + found.body());

        HttpResponse<String> hers = get("/Observation?subject=Patient/" + patientId);
        assertEquals(200, hers.statusCode(), hers.body());
        assertTrue(hers.body().contains("Body temperature"),
                "and the observation from her visit is reachable from her: " + hers.body());
    }

    // ── and accounting for all of it ──

    @Test
    @Order(11)
    @DisplayName("every one of those acts is in the trail, attributed to the credential that "
            + "did it, and the trail cannot be edited by anybody including its author")
    @Proving({DboPromises.POL_AUDIT_AS_RECORDS, DboPromises.POL_ACTOR_FROM_AUTHORITY,
            DboPromises.POL_FHIR_AUDIT_PROJECTION,
            DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
            DboPromises.POL_DECLARED_AT_CONFIGURATION})
    void theTrailHoldsItAndNobodyCanEditIt() throws Exception {
        HttpResponse<String> trail = get("/AuditEvent?_count=50");
        assertEquals(200, trail.statusCode(), trail.body());
        assertTrue(trail.body().contains("kevad-emr"),
                "the actor is the credential the authority validated, not the machinery's "
                        + "own name: " + trail.body().substring(0,
                        Math.min(500, trail.body().length())));

        String anEntry = trail.body().replaceAll(
                "(?s).*\"resourceType\"\\s*:\\s*\"AuditEvent\"\\s*,\\s*\"id\"\\s*:\\s*\"([^\"]+)\".*",
                "$1");
        HttpResponse<String> tamper = put("/AuditEvent/" + anEntry,
                "{\"resourceType\":\"AuditEvent\",\"id\":\"" + anEntry + "\"}");
        assertNotEquals(200, tamper.statusCode(),
                "an audit entry was updated, so the trail is a record of what somebody was "
                        + "willing to leave rather than of what happened: " + tamper.body());
    }

    @Test
    @Order(12)
    @DisplayName("one feed carries every one of those changes, and a named consumer resumes "
            + "from where it stopped rather than from the beginning")
    @Proving({DboPromises.FEED_ONE_PRIMITIVE, DboPromises.FEED_NAMED_CONSUMERS,
            DboPromises.FEED_KEYSET_CURSORS, DboPromises.EVT_TRANSACTIONAL_OUTBOX})
    void oneFeedCarriesItAll() {
        var feed = manager.runtime(CLINIC).orElseThrow().feed();

        var first = feed.readFor("kevad-report", 3);
        assertFalse(first.items().isEmpty(),
                "the writes above produced no feed events, so nothing downstream could ever "
                        + "learn about them");
        assertTrue(first.items().size() <= 3, "the page is the size that was asked for");
        feed.ack("kevad-report", first.nextCursor());

        var second = feed.readFor("kevad-report", 3);
        assertTrue(second.items().stream().noneMatch(
                        i -> first.items().stream().anyMatch(f -> f.seq() == i.seq())),
                "the consumer was handed the same events twice, so its cursor means nothing");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String fhir(String path) {
        return "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC + "/fhir" + path;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + token).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body, String unused)
            throws Exception {
        return post(path, body);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(fhir(path)))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/fhir+json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String clientId, String secret) throws Exception {
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + manager.port()
                                        + "/t/" + CLINIC + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
