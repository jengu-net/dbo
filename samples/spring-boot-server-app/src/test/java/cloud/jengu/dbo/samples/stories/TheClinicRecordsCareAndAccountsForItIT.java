package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-CLINICAL-RECORD, walked in Rowling Land, the sample world.
 *
 * <p>Maarja works at St Jerome, the clinic of the sample world, which keys its
 * patients by its own record number. This is the store doing what it exists
 * for: a patient who arrives twice is one patient, a visit lands whole or not
 * at all, a code means what the clinic's terminology says it means,
 * everything can be found again, and none of it can be quietly edited
 * afterwards.
 *
 * <p><b>One clinic, one afternoon, in dependency order.</b> Each leg is set up
 * by the leg before it, and asserts on what that leg made: this patient, this
 * visit, this code.
 *
 * <p><b>The clinic is shared with every other story running.</b> The record
 * numbers, the code system and the trail entries this story makes carry its
 * prefix and this run's mark, and every question it asks is about those. What
 * the clinic holds besides them is somebody else's.
 */
@AUserStory
class TheClinicRecordsCareAndAccountsForItIT {

    private static final String CLINIC = "st-jerome";

    /** Rowling Land, the zone St Jerome takes its code systems from. */
    private static final String ZONE = "rl";

    /** The system St Jerome keys its patients by, in the sample world's spec. */
    private static final String MRN = "urn:st-jerome:mrn";

    private final StoryNames names = StoryNames.of(DboStories.CLINICAL_RECORD);

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    org.springframework.core.env.Environment environment;

    private ATenantsDoor clinic;
    private String liis;

    @BeforeAll
    void theClinicsDoor() {
        clinic = new ATenantsDoor(dbo, CLINIC);
    }

    /** Liis's record number at St Jerome: this story's, and this run's. */
    private String hers() {
        return names.value("liis");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── one patient, however many times she arrives ──

    @Test
    @Order(1)
    @DisplayName("a patient is written and reads back as what was written, an element nothing "
            + "indexes included")
    @Proving({DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_READ_YOUR_WRITES,
            DboPromises.CORE_DECLARED_TRUTH_FORM})
    void whatWasWrittenIsWhatIsRead() {
        var written = dbo.write(CLINIC, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(MRN, hers()));
        assertTrue(written.accepted(), "Liis was not accepted: " + written.body());
        liis = written.idOrFail();

        HttpResponse<String> read = dbo.read(CLINIC, "Patient", liis);
        assertEquals(200, read.statusCode(), read.body());
        var record = dbo.says(read);
        Proves.that(DboPromises.CORE_READ_YOUR_WRITES,
                record.at("name.family").contains("Tamm"),
                "the write had returned and the read straight after it did not see it: "
                        + read.body());
        Proves.that(DboPromises.CORE_PAYLOAD_IS_TRUTH,
                record.one("birthDate").equals(Optional.of("1990-01-01")),
                "an element nothing indexes did not come back as written, so the record was "
                        + "rebuilt from something other than its payload: " + read.body());
    }

    @Test
    @Order(2)
    @DisplayName("Liis arrives a second time and is the same patient, because the record "
            + "number her clinic knows her by decides that and nothing else does")
    @Proving({DboPromises.CORE_CONDITIONAL_UPSERT, DboPromises.CORE_EXTERNAL_IDENTIFIERS,
            DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void theSamePatientArrivingTwiceIsOnePatient() {
        HttpResponse<String> again = clinic.post("/Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}""".formatted(MRN, hers()),
                "If-None-Exist", "identifier=" + MRN + "|" + hers());
        Proves.that(DboPromises.CORE_CONDITIONAL_UPSERT, again.statusCode() == 200,
                "she already exists and this is her, which is 200 and not a second create: "
                        + again.statusCode() + " " + again.body());
        Proves.that(DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS,
                again.body().contains(liis),
                "the conditional create answered with some record other than hers: "
                        + again.body());

        // A different person at the same clinic is a different record, and no
        // amount of matching names changes that: identity is the declared
        // record number, never a resemblance the store decided on its own.
        var namesake = dbo.write(CLINIC, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}]}"""
                .formatted(MRN, names.value("namesake")));
        assertTrue(namesake.accepted(), namesake.body());
        Proves.that(DboPromises.CORE_NO_IMPLICIT_MERGE, !namesake.idOrFail().equals(liis),
                "two people with one name became one record");
    }

    @Test
    @Order(3)
    @DisplayName("correcting her birth date keeps the version that was wrong, because what "
            + "the record said last year is a fact about last year")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void everyVersionIsKept() {
        HttpResponse<String> corrected = clinic.put("/Patient/" + liis, """
                {"resourceType":"Patient","id":"%s",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-02"}""".formatted(liis, MRN, hers()));
        assertEquals(200, corrected.statusCode(), corrected.body());

        HttpResponse<String> history = clinic.get("/Patient/" + liis + "/_history");
        assertEquals(200, history.statusCode(), history.body());
        List<String> births = dbo.says(history).at("entry.resource.birthDate");
        Proves.that(DboPromises.CORE_VERSIONED_HISTORY,
                births.contains("1990-01-01") && births.contains("1990-01-02"),
                "the corrected value and the one it corrected are not both in her history: "
                        + births);
    }

    // ── a visit lands whole, or not at all ──

    @Test
    @Order(4)
    @DisplayName("a visit arrives as one document and lands whole, naming the patient by the "
            + "record number the sender knows rather than by an id only this store has")
    @Proving({DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE, DboPromises.CORE_CONDITIONAL_REFERENCES,
            DboPromises.CORE_REFERENCE_EDGES})
    void aVisitLandsWhole() {
        // Two observations, one gathering the other. St Jerome takes its
        // encounters from the hospital, so a visit recorded here is what was
        // measured, named to the patient by the number the sender holds.
        HttpResponse<String> visit = clinic.post("", """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"fullUrl":"urn:uuid:temperature",
                   "resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Body temperature"},
                     "subject":{"reference":"Patient?identifier=%s|%s"},
                     "valueQuantity":{"value":37.4}},
                   "request":{"method":"POST","url":"Observation"}},
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Visit summary"},
                     "subject":{"reference":"Patient?identifier=%s|%s"},
                     "hasMember":[{"reference":"urn:uuid:temperature"}]},
                   "request":{"method":"POST","url":"Observation"}}]}"""
                .formatted(MRN, hers(), MRN, hers()));
        Proves.that(DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE, visit.statusCode() == 200,
                "the visit did not land: " + visit.statusCode() + " " + visit.body());

        // What the story is about is what landed IN them: the question the
        // sender asked — whoever has this number — is answered once, at write
        // time, and stored as a concrete reference.
        List<String> landed = dbo.says(visit).at("entry.response.location");
        assertEquals(2, landed.size(), "the visit's two entries say where they landed: "
                + visit.body());
        String summary = landed.get(1).replaceAll("/_history/.*$", "");
        HttpResponse<String> stored = clinic.get("/" + summary);
        assertEquals(200, stored.statusCode(), stored.body());
        var said = dbo.says(stored);
        Proves.that(DboPromises.CORE_CONDITIONAL_REFERENCES,
                said.one("subject.reference").equals(Optional.of("Patient/" + liis)),
                "the record number the sender knew was left as a question rather than "
                        + "resolved to the patient this store holds: " + stored.body());
        String temperature = landed.get(0).replaceAll("/_history/.*$", "");
        Proves.that(DboPromises.CORE_REFERENCE_EDGES,
                said.at("hasMember.reference").equals(List.of(temperature)),
                "the reference between two entries of one visit did not land on the record "
                        + "the other entry became: " + stored.body());
    }

    @Test
    @Order(5)
    @DisplayName("a visit the store cannot honour lands nothing at all, and a batch of "
            + "unrelated writes answers for each one separately")
    @Proving({DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE,
            DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY})
    void allOfItOrNoneOfIt() {
        // A transaction whose second entry names a patient nobody has: the
        // first entry must not survive it.
        String doomedText = names.value("must-not-land");
        HttpResponse<String> doomed = clinic.post("", """
                {"resourceType":"Bundle","type":"transaction","entry":[
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"%s"},
                     "subject":{"reference":"Patient?identifier=%s|%s"},
                     "valueQuantity":{"value":61}},
                   "request":{"method":"POST","url":"Observation"}},
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Pulse"},
                     "subject":{"reference":"Patient?identifier=%s|%s"},
                     "valueQuantity":{"value":62}},
                   "request":{"method":"POST","url":"Observation"}}]}"""
                .formatted(doomedText, MRN, hers(), MRN, names.value("nobody-has-this")));
        assertNotEquals(200, doomed.statusCode(),
                "the transaction was accepted despite an entry it could not honour: "
                        + doomed.body());

        HttpResponse<String> hers = clinic.get("/Observation?subject=Patient/" + liis);
        assertEquals(200, hers.statusCode(), hers.body());
        Proves.that(DboPromises.CORE_ATOMIC_TRANSACTION_BUNDLE,
                !dbo.says(hers).at("entry.resource.code.text").contains(doomedText),
                "the first entry of a refused transaction is in the store: " + hers.body());

        // A batch makes no such promise, and says so per entry.
        HttpResponse<String> batch = clinic.post("", """
                {"resourceType":"Bundle","type":"batch","entry":[
                  {"resource":{"resourceType":"Observation","status":"final",
                     "code":{"text":"Respiratory rate"},
                     "subject":{"reference":"Patient/%s"},
                     "valueQuantity":{"value":14}},
                   "request":{"method":"POST","url":"Observation"}},
                  {"resource":{"resourceType":"Observation"},
                   "request":{"method":"POST","url":"Observation"}}]}""".formatted(liis));
        assertEquals(200, batch.statusCode(), batch.body());
        List<String> statuses = dbo.says(batch).at("entry.response.status");
        Proves.that(DboPromises.CORE_BATCH_ANSWERS_PER_ENTRY,
                statuses.size() == 2 && statuses.get(0).startsWith("201")
                        && statuses.get(1).startsWith("4"),
                "a batch answers each entry for itself — the one that could land landed, and "
                        + "the one that could not says so in its own entry: " + statuses);
    }

    @Test
    @Order(6)
    @DisplayName("two people editing Liis at once do not silently overwrite each other: the "
            + "second write is refused because it was made against a version that has moved")
    @Proving(DboPromises.CORE_VERSIONED_HISTORY)
    void aWriteAgainstAStaleVersionIsRefused() {
        String current = clinic.get("/Patient/" + liis).body();

        HttpResponse<String> late = clinic.put("/Patient/" + liis, current,
                "If-Match", "W/\"1\"");

        // 409 or 412 — the store says which, and either is a refusal. What
        // matters is that it is one: a write accepted against a version that
        // has moved silently discards the edit it was based on.
        Proves.that(DboPromises.CORE_VERSIONED_HISTORY,
                late.statusCode() == 409 || late.statusCode() == 412,
                "a write made against a version that has since moved was accepted, so the "
                        + "edit it was based on is gone and nobody was told: "
                        + late.statusCode() + " " + late.body());
        assertTrue(late.body().contains("conflict"),
                "and the refusal says what kind it is, so a client knows to re-read rather "
                        + "than to retry: " + late.body());
    }

    @Test
    @Order(7)
    @DisplayName("deleting a patient frees the record number they were claiming, so somebody "
            + "registered by mistake can be registered again properly")
    @Proving({DboPromises.CORE_EXTERNAL_IDENTIFIERS, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void deletingFreesTheIdentityClaim() {
        String mistaken = names.value("mistaken");
        var wrong = dbo.write(CLINIC, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Vale","given":["Sisestus"]}]}""".formatted(MRN, mistaken));
        assertTrue(wrong.accepted(), wrong.body());

        assertTrue(clinic.delete("/Patient/" + wrong.idOrFail()).statusCode() < 300,
                "the mistake is removed");

        // The claim went with them. Without that, a mis-registration would
        // burn a record number for good and the correction would have to
        // invent a different one.
        var again = dbo.write(CLINIC, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Oige","given":["Sisestus"]}]}""".formatted(MRN, mistaken));
        Proves.that(DboPromises.CORE_EXTERNAL_IDENTIFIERS, again.accepted(),
                "the record number is still claimed by a deleted record, so a "
                        + "mis-registration burns it permanently: " + again.body());
    }

    // ── what a code means here ──

    @Test
    @Order(8)
    @DisplayName("a code means what the clinic's own terminology says, and a system the "
            + "clinic does not hold is unresolvable rather than invalid")
    @Proving({DboPromises.TERM_NATIVE_FORM, DboPromises.TERM_EVERY_TENANT_ANSWERS,
            DboPromises.TERM_OPERATIONS_FROM_NATIVE_FORM,
            DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID})
    void aCodeMeansWhatTheClinicsTerminologySays() throws InterruptedException {
        // Rowling Land publishes it, and St Jerome takes its code systems.
        // What the clinic answers from is its own copy.
        String severity = names.canonical("severity");
        HttpResponse<String> published = new ATenantsDoor(dbo, ZONE).post("/CodeSystem", """
                {"resourceType":"CodeSystem","url":"%s",
                 "status":"active","content":"complete","version":"1.0",
                 "concept":[{"code":"mild","display":"Mild"},
                            {"code":"severe","display":"Severe"}]}""".formatted(severity));
        assertTrue(published.statusCode() == 200 || published.statusCode() == 201,
                published.body());

        String lookup = "/CodeSystem/$lookup?system=" + encoded(severity) + "&code=severe";
        HttpResponse<String> answered = clinic.get(lookup);
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (answered.statusCode() != 200 && System.nanoTime() < giveUp) {
            Thread.sleep(1000);
            answered = clinic.get(lookup);
        }
        if (answered.statusCode() != 200) {
            // Which half did not happen: the copy arriving, or the copy
            // answering. They are different defects with different owners.
            HttpResponse<String> atTheZone = new ATenantsDoor(dbo, ZONE).get(lookup);
            HttpResponse<String> held = clinic.get("/CodeSystem?url=" + encoded(severity));
            Proves.that(DboPromises.TERM_EVERY_TENANT_ANSWERS, false,
                    "the clinic never answered for a code system its zone published: "
                            + answered.statusCode() + " " + answered.body()
                            + "\n  the zone answers: " + atTheZone.statusCode() + " "
                            + atTheZone.body()
                            + "\n  the clinic holds: " + held.statusCode() + " " + held.body());
        }
        Proves.that(DboPromises.TERM_OPERATIONS_FROM_NATIVE_FORM,
                answered.body().contains("Severe"),
                "the clinic answered without the concept's display, so it is not answering "
                        + "from the concepts it holds: " + answered.body());

        HttpResponse<String> foreign = clinic.get("/CodeSystem/$lookup?system="
                + encoded(names.canonical("never-published")) + "&code=whatever");
        assertNotEquals(200, foreign.statusCode(),
                "a system this clinic never received answered as though it knew it");
        Proves.that(DboPromises.VAL_UNRESOLVABLE_IS_NOT_INVALID,
                !foreign.body().toLowerCase(Locale.ROOT).contains("invalid"),
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
    void theStoreSaysWhatItCanSearch() {
        var says = dbo.capability(CLINIC);
        Proves.that(DboPromises.SRCH_HONEST_CAPABILITY,
                says.searchableBy("Patient").contains("identifier"),
                "the capability statement does not name a parameter that works: "
                        + says.searchableBy("Patient"));

        HttpResponse<String> unsupported = clinic.get("/Patient?favourite-colour=blue");
        Proves.that(DboPromises.SRCH_STRICT_BY_DEFAULT, unsupported.statusCode() == 400,
                "a parameter the store does not implement was ignored rather than refused, "
                        + "which answers a different question than the caller asked: "
                        + unsupported.statusCode() + " " + unsupported.body());
    }

    @Test
    @Order(10)
    @DisplayName("Liis is found again by the record number she was written under, and nobody "
            + "else is, and what belongs to her visit comes back with her")
    @Proving({DboPromises.SRCH_TIER1_PARITY, DboPromises.CORE_REFERENCE_EDGES})
    void findingHerAgain() {
        HttpResponse<String> found = dbo.search(CLINIC, "Patient",
                "identifier=" + MRN + "|" + hers());
        assertEquals(200, found.statusCode(), found.body());
        List<String> ids = dbo.says(found).at("entry.resource.id");
        Proves.that(DboPromises.SRCH_TIER1_PARITY, ids.equals(List.of(liis)),
                "her record number should find her and only her, and found " + ids
                        + " where she is " + liis + ": " + found.body());

        HttpResponse<String> visit = dbo.search(CLINIC, "Observation", "subject=Patient/" + liis);
        assertEquals(200, visit.statusCode(), visit.body());
        Proves.that(DboPromises.CORE_REFERENCE_EDGES,
                dbo.says(visit).at("entry.resource.code.text").contains("Body temperature"),
                "the observation from her visit is not reachable from her: " + visit.body());
    }

    // ── and accounting for all of it ──

    @Test
    @Order(11)
    @DisplayName("what was done to Liis is in the trail, attributed to the credential that did "
            + "it, and the trail cannot be edited by anybody including its author")
    @Proving({DboPromises.POL_AUDIT_AS_RECORDS, DboPromises.POL_ACTOR_FROM_AUTHORITY,
            DboPromises.POL_FHIR_AUDIT_PROJECTION,
            DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
            DboPromises.POL_DECLARED_AT_CONFIGURATION})
    void theTrailHoldsItAndNobodyCanEditIt() {
        HttpResponse<String> trail = clinic.get("/AuditEvent?entity=Patient/" + liis);
        assertEquals(200, trail.statusCode(), trail.body());
        List<String> ids = dbo.says(trail).at("entry.resource.id");
        Proves.that(DboPromises.POL_AUDIT_AS_RECORDS, !ids.isEmpty(),
                "St Jerome audits writes, and nothing written to Liis is in its trail: "
                        + trail.body());
        Proves.that(DboPromises.POL_ACTOR_FROM_AUTHORITY,
                trail.body().contains("dbo-test-worker"),
                "the actor is not the credential the clinic's authority validated: "
                        + trail.body());

        HttpResponse<String> tamper = clinic.put("/AuditEvent/" + ids.get(0),
                "{\"resourceType\":\"AuditEvent\",\"id\":\"" + ids.get(0) + "\"}");
        Proves.that(DboPromises.POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY,
                tamper.statusCode() != 200,
                "an audit entry was updated, so the trail is a record of what somebody was "
                        + "willing to leave rather than of what happened: " + tamper.body());
    }

    @Test
    @Order(12)
    @DisplayName("the trail answers what kind of event, so a rare one is not buried under "
            + "everything that happened since")
    @Proving({DboPromises.POL_FHIR_AUDIT_PROJECTION, DboPromises.SRCH_HONEST_CAPABILITY})
    void theTrailIsSearchableByWhatKindOfEventItWas() {
        // One event of a kind only this story writes, then enough ordinary
        // traffic to bury it.
        String rare = names.code("suspended");
        HttpResponse<String> recorded = clinic.post("/AuditEvent", """
                {"resourceType":"AuditEvent",
                 "type":{"system":"urn:example:audit-type","code":"%s"},
                 "recorded":"2020-01-01T00:00:00Z",
                 "agent":[{"requestor":true}],
                 "source":{"site":"north"}}""".formatted(rare));
        assertEquals(201, recorded.statusCode(), recorded.body());
        String noise = names.value("noise");
        for (int i = 0; i < 12; i++) {
            assertEquals(201, clinic.post("/Observation", """
                    {"resourceType":"Observation","status":"final",
                     "code":{"text":"%s %d"},
                     "subject":{"reference":"Patient/%s"}}""".formatted(noise, i, liis))
                    .statusCode());
        }

        HttpResponse<String> recent = clinic.get("/AuditEvent?_count=5&_sort=-date");
        assertFalse(recent.body().contains(rare),
                "the arrangement proves nothing unless the entry really is out of reach of a "
                        + "bounded read: " + recent.body());

        HttpResponse<String> narrowed = clinic.get("/AuditEvent?_count=5&type=" + rare);
        assertEquals(200, narrowed.statusCode(), narrowed.body());
        Proves.that(DboPromises.POL_FHIR_AUDIT_PROJECTION, narrowed.body().contains(rare),
                "the kind of event an operator came to ask about is not findable, so a "
                        + "compliance question can only be answered by reading the whole "
                        + "trail: " + narrowed.body());
        assertFalse(narrowed.body().contains(noise),
                "narrowing by type returned entries of other kinds: " + narrowed.body());
    }

    @Test
    @Order(13)
    @DisplayName("a system the trail never indexed is refused with the search that would "
            + "work, rather than answered wider than it was asked")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void aSystemQualifiedTypeIsRefusedWithTheAlternative() {
        String rare = names.code("suspended");
        HttpResponse<String> refused = clinic.get("/AuditEvent?type="
                + encoded("urn:example:audit-type|" + rare));
        assertEquals(400, refused.statusCode(), refused.body());
        Proves.that(DboPromises.SRCH_HONEST_CAPABILITY,
                refused.body().contains("type=" + rare),
                "the refusal has to carry the search that would work — a caller who is told "
                        + "only that this is unsupported can do nothing but guess: "
                        + refused.body());
        assertFalse(refused.body().contains("unsupported search parameter"),
                "type IS supported; refusing it as an unknown parameter tells the caller to "
                        + "stop asking rather than to ask differently: " + refused.body());
    }

    @Test
    @Order(14)
    @DisplayName("one feed carries every one of those changes, and a named consumer resumes "
            + "from where it stopped rather than from the beginning")
    @Proving({DboPromises.FEED_ONE_PRIMITIVE, DboPromises.FEED_NAMED_CONSUMERS,
            DboPromises.FEED_KEYSET_CURSORS, DboPromises.EVT_TRANSACTIONAL_OUTBOX})
    void oneFeedCarriesItAll() {
        var feed = tenants.changes(CLINIC).orElseThrow(
                () -> new AssertionError("St Jerome has no change feed: " + tenants.serving()));
        // A consumer of this story's own: the feed is the clinic's, and every
        // story writing there shares it, but where a consumer stands is its
        // own.
        String reporting = names.code("report");

        FeedChunk<FeedItem> first = feed.readFor(reporting, 3);
        Proves.that(DboPromises.EVT_TRANSACTIONAL_OUTBOX, !first.items().isEmpty(),
                "the writes above produced no feed events, so nothing downstream could ever "
                        + "learn about them");
        assertTrue(first.items().size() <= 3, "the page is the size that was asked for");
        feed.ack(reporting, first.nextCursor());

        FeedChunk<FeedItem> second = feed.readFor(reporting, 3);
        Proves.that(DboPromises.FEED_NAMED_CONSUMERS,
                second.items().stream().noneMatch(i -> first.items().stream()
                        .anyMatch(f -> f.seq() == i.seq())),
                "the consumer was handed the same events twice, so its cursor means nothing");
    }

    // ── and what the clinic keeps that is not a record ──

    @Test
    @Order(15)
    @DisplayName("a scanned referral put over the wire comes back as the bytes that were sent, "
            + "with the type its writer gave it, and the clinic says how much it took")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void contentComesBackAsItWasSent() throws Exception {
        byte[] scan = new byte[5000];
        new java.security.SecureRandom().nextBytes(scan);
        HttpResponse<String> put = putContent(scan, "image/tiff", dbo.token(CLINIC));
        assertEquals(201, put.statusCode(), put.body());
        referral = put.headers().firstValue("Location").orElseThrow();
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                put.body().contains("\"size\":5000"),
                "the door did not say how much it took, so a writer cannot tell a truncated "
                        + "upload from a whole one: " + put.body());

        HttpResponse<byte[]> got = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(serverUri(referral))
                        .header("Authorization", "Bearer " + reader()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                got.statusCode() == 200 && java.util.Arrays.equals(scan, got.body())
                        && "image/tiff".equals(got.headers().firstValue("Content-Type")
                                .orElse("")),
                "the content came back changed, or without the type its writer gave it");
    }

    @Test
    @Order(16)
    @DisplayName("two puts of the same bytes are two pieces of content, because the key is the "
            + "store's to choose")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void theStoreChoosesTheKey() throws Exception {
        byte[] same = names.value("the-same-bytes").getBytes(StandardCharsets.UTF_8);
        String first = putContent(same, "text/plain", dbo.token(CLINIC)).headers()
                .firstValue("Location").orElseThrow();
        String second = putContent(same, "text/plain", dbo.token(CLINIC)).headers()
                .firstValue("Location").orElseThrow();
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA, !first.equals(second),
                "two writers of the same content were given one key, so either can delete "
                        + "the other's");
    }

    @Test
    @DisplayName("forgetting content says whether there was any, and a key that names nothing "
            + "is not found")
    @Order(17)
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void droppingSaysWhetherThereWasAnything() throws Exception {
        for (String stranger : List.of("01920000-0000-7000-8000-000000000000", "not-a-key")) {
            assertEquals(404, dbo.get(dbo.at(CLINIC) + "/blob/" + stranger, reader())
                    .statusCode(), "a key this store never issued found something: " + stranger);
        }
        String location = putContent(names.value("gone-shortly").getBytes(StandardCharsets.UTF_8),
                "text/plain", dbo.token(CLINIC)).headers().firstValue("Location").orElseThrow();
        var delete = java.net.http.HttpRequest.newBuilder(serverUri(location)).DELETE();
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                dbo.send(delete, dbo.token(CLINIC)).statusCode() == 204
                        && dbo.send(java.net.http.HttpRequest.newBuilder(serverUri(location))
                                .DELETE(), dbo.token(CLINIC)).statusCode() == 404
                        && dbo.get(serverUri(location).toString(), reader()).statusCode() == 404,
                "dropping content did not say whether it was there, or forgotten content is "
                        + "still readable");
    }

    @Test
    @Order(18)
    @DisplayName("a credential that may read may not write content, and none at all reaches "
            + "nothing")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void theScopeIsTheOneForContent() throws Exception {
        byte[] content = names.value("not-yours").getBytes(StandardCharsets.UTF_8);
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                putContent(content, "text/plain", reader()).statusCode() == 403
                        && putContent(content, "text/plain", null).statusCode() == 401,
                "content was written with a reading credential, or with none at all");
    }

    @Test
    @Order(19)
    @DisplayName("a clinic that holds no keys refuses content named for a person, rather than "
            + "keeping it in the clear while its writer believes it is sealed")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void whatCannotBeSealedIsNotQuietlyKept() {
        HttpResponse<String> refused = dbo.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(CLINIC) + "/blob?person=" + liis))
                .header("Content-Type", "audio/ogg")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[] {1, 2, 3})),
                dbo.token(CLINIC));
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                refused.statusCode() == 400 && refused.body().contains("seal"),
                "content named for a person was taken by a clinic with no keys, so it is kept in "
                        + "the clear: " + refused.statusCode() + " " + refused.body());
    }


    // ── and the clinic tells others when something they care about changes ──

    @Test
    @Order(20)
    @DisplayName("the insurer subscribes to coverage changes and is told one happened, by a "
            + "tenant nobody wired by hand, with the name and not the record")
    @Proving(DboPromises.EVT_A_TENANT_DELIVERS)
    void aSubscriberIsToldSomethingChanged() throws Exception {
        String insurer = "gringotts";
        String endpoint = subscriber("/coverage");
        HttpResponse<String> subscribed = new ATenantsDoor(dbo, insurer).post("/Subscription", """
                {"resourceType":"Subscription","status":"active",
                 "reason":"a coverage changed","criteria":"Coverage?status=active",
                 "channel":{"type":"rest-hook","endpoint":"%s"}}""".formatted(endpoint));
        assertEquals(201, subscribed.statusCode(), subscribed.body());

        String period = names.value("coverage-period");
        var member = dbo.write(insurer, "Patient", """
                {"resourceType":"Patient","name":[{"family":"Tamm","given":["Liis"]}]}""");
        assertTrue(member.accepted(), "the insurer did not take its member: " + member.body());
        var coverage = dbo.write(insurer, "Coverage", """
                {"resourceType":"Coverage","status":"active",
                 "identifier":[{"system":"urn:%s","value":"%s"}],
                 "beneficiary":{"reference":"Patient/%s"},
                 "payor":[{"display":"Gringotts"}]}"""
                .formatted(names.prefix(), period, member.idOrFail()));
        assertTrue(coverage.accepted(), coverage.body());
        String coverageId = coverage.idOrFail();

        String told = waitFor("/coverage", n -> n.contains("Coverage/" + coverageId));
        Proves.that(DboPromises.EVT_A_TENANT_DELIVERS, told != null,
                "the insurer was never told its coverage changed, so a tenant holds a "
                        + "subscription it never acts on: " + received("/coverage"));
        Proves.that(DboPromises.EVT_A_TENANT_DELIVERS,
                !told.contains(period) && told.contains("Subscription/"),
                "the record travelled although no payload was asked for, or the notification "
                        + "does not say which subscription it answers: " + told);
    }

    @Test
    @Order(21)
    @DisplayName("the clinic subscribes its lab system to final results by topic, and the R5 "
            + "notification names the topic and carries no record")
    @Proving(DboPromises.EVT_FHIR_SUBSCRIPTIONS)
    void aTopicSubscriptionDelivers() throws Exception {
        String topic = names.canonical("SubscriptionTopic/final-results");
        assertEquals(201, clinic.post("/SubscriptionTopic", """
                {"resourceType":"SubscriptionTopic","url":"%s","status":"active",
                 "resourceTrigger":[{"resource":"Observation",
                                     "supportedInteraction":["create","update"]}],
                 "canFilterBy":[{"filterParameter":"status"}]}""".formatted(topic)).statusCode());
        assertEquals(201, clinic.post("/Subscription", """
                {"resourceType":"Subscription","status":"active","topic":"%s",
                 "channelType":{"code":"rest-hook"},"endpoint":"%s","content":"id-only",
                 "filterBy":[{"filterParameter":"status","value":"final"}]}"""
                .formatted(topic, subscriber("/results"))).statusCode());

        String result = names.value("final-result");
        String observation = dbo.write(CLINIC, "Observation", """
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"%s"},"subject":{"reference":"Patient/%s"}}"""
                .formatted(result, liis)).idOrFail();

        String told = waitFor("/results", n -> n.contains(observation));
        Proves.that(DboPromises.EVT_FHIR_SUBSCRIPTIONS,
                told != null && told.contains("subscription-notification")
                        && told.contains(topic) && !told.contains(result),
                "the topic subscription did not deliver an R5 notification naming its topic "
                        + "and nothing more: " + received("/results"));
    }

    // ── a subscriber outside the store ──

    private com.sun.net.httpserver.HttpServer listening;
    private final Map<String, List<String>> notifications =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** An endpoint this story listens on, as a subscriber outside the store would. */
    private String subscriber(String path) throws Exception {
        if (listening == null) {
            listening = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            listening.start();
        }
        List<String> arrived = notifications.computeIfAbsent(path,
                p -> new java.util.concurrent.CopyOnWriteArrayList<>());
        listening.createContext(path, exchange -> {
            arrived.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        return "http://127.0.0.1:" + listening.getAddress().getPort() + path;
    }

    private List<String> received(String path) {
        return notifications.getOrDefault(path, List.of());
    }

    /** The first notification at a path that answers the question, or null after 90s. */
    private String waitFor(String path, java.util.function.Predicate<String> about)
            throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < giveUp) {
            for (String arrived : received(path)) {
                if (about.test(arrived)) {
                    return arrived;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    // ── a clinic of its own records: what it declares, what it validates, how it searches ──

    /** A clinic this story opens for the records it shapes itself. */
    private String records;
    private ATenantsDoor recordsDoor;

    private static final String PARTICIPANTS = "urn:participant";

    @Test
    @Order(22)
    @DisplayName("a declaration a clinic holds of its own is read back as written, found by "
            + "what it is called, and leaves the clinic's other types as they were")
    @Proving({DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS})
    void aClinicHoldsItsOwnDeclaration() {
        records = names.tenant("records");
        dbo.declare(records, """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                  {"name":"ParticipantDeclaration","identity":"identifier",
                   "systems":["%s"],"handling":"operational","definition":"none"},
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational",
                   "extractor":"database"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(records, PARTICIPANTS));
        assertTrue(dbo.until(records, true, Duration.ofMinutes(10)), "no records clinic");
        recordsDoor = new ATenantsDoor(dbo, records);

        String declaration = "{\"resourceType\":\"ParticipantDeclaration\",\"identifier\":"
                + "[{\"system\":\"" + PARTICIPANTS + "\",\"value\":\"holds-its-own-lab\"}],"
                + "\"zone\":\"ee\",\"repo\":\"https://git.test/cfg\"}";
        HttpResponse<String> written = recordsDoor.post("/ParticipantDeclaration", declaration);
        assertEquals(201, written.statusCode(), written.body());
        String location = written.headers().firstValue("Location").orElseThrow();
        String id = location.replaceAll(".*/ParticipantDeclaration/([^/]+).*", "$1");
        Proves.that(DboPromises.CORE_PAYLOAD_IS_TRUTH,
                recordsDoor.get("/ParticipantDeclaration/" + id).body().equals(declaration),
                "a declaration with no definition did not read back byte for byte");
        String found = recordsDoor.get("/ParticipantDeclaration?identifier="
                + encoded(PARTICIPANTS + "|holds-its-own-lab")).body();
        Proves.that(DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS,
                found.contains("fullUrl") && found.contains("holds-its-own-lab"),
                "the declaration is not found by what it is called: " + found);
        assertEquals(201, recordsDoor.post("/Patient",
                "{\"resourceType\":\"Patient\",\"active\":true}").statusCode());
        assertTrue(recordsDoor.post("/Patient",
                        "{\"resourceType\":\"Patient\",\"active\":\"not-a-boolean\"}")
                .statusCode() >= 400, "the clinic's other types stopped being validated");
    }

    @Test
    @Order(23)
    @DisplayName("a profile the clinic writes takes effect without a restart: a claim on one "
            + "it does not hold is refused, and the differential still carries the whole base")
    @Proving(DboPromises.VER_SPECIFIED_VALIDATION)
    void aProfileTheClinicWritesTakesEffect() {
        String profile = names.canonical("StructureDefinition/observed-on-somebody");
        String without = """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "meta":{"profile":["%s"]}}""".formatted(profile);
        Proves.that(DboPromises.VER_SPECIFIED_VALIDATION,
                recordsDoor.post("/Observation", without).statusCode() >= 400,
                "a claim on a profile nobody holds was taken as satisfied");
        assertEquals(201, recordsDoor.post("/StructureDefinition", """
                {"resourceType":"StructureDefinition","url":"%s","name":"ObservedOnSomebody",
                 "status":"active","kind":"resource","abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint","differential":{"element":[
                   {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
                .formatted(profile)).statusCode());
        HttpResponse<String> refused = recordsDoor.post("/Observation", without);
        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("subject"), refused.body());
        assertEquals(201, recordsDoor.post("/Observation", """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "subject":{"reference":"Patient/anyone"},"meta":{"profile":["%s"]}}"""
                .formatted(profile)).statusCode());
        HttpResponse<String> noStatus = recordsDoor.post("/Observation", """
                {"resourceType":"Observation","code":{"text":"pulse"},
                 "subject":{"reference":"Patient/anyone"},"meta":{"profile":["%s"]}}"""
                .formatted(profile));
        Proves.that(DboPromises.VER_SPECIFIED_VALIDATION,
                noStatus.statusCode() == 422 && noStatus.body().contains("status"),
                "the differential was not snapshotted, so the base stopped applying: "
                        + noStatus.body());
    }

    @Test
    @Order(24)
    @DisplayName("a type whose envelope is computed where the bytes are is searched by it, and "
            + "a reindex happens there too")
    @Proving(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED)
    void anEnvelopeComputedWhereTheBytesAre() throws java.sql.SQLException {
        String url = names.canonical("ValueSet/declared");
        assertEquals(201, recordsDoor.post("/ValueSet", """
                {"resourceType":"ValueSet","url":"%s","version":"1","status":"active",
                 "name":"Declared"}""".formatted(url)).statusCode());
        Proves.that(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED,
                fullUrls(recordsDoor.get("/ValueSet?url=" + encoded(url)).body()) == 1
                        && fullUrls(recordsDoor.get("/ValueSet?status=active&url="
                        + encoded(url)).body()) == 1
                        && fullUrls(recordsDoor.get("/ValueSet?status=draft&url="
                        + encoded(url)).body()) == 0,
                "a type with a database extractor is not searched by its envelope");

        var store = tenants.store(records).orElseThrow();
        String id = store.getByIdentifier("ValueSet", List.of(new cloud.jengu.dbo.core.api
                .Identifier(cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url)))
                .get(0).id();
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(records),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("UPDATE " + cloud.jengu.dbo.core.api.Domains.tables(
                        cloud.jengu.dbo.core.api.Domains.DEFINITIONS)
                        + "_data SET envelope = '{}'::jsonb WHERE id = ?::uuid")) {
            ps.setString(1, id);
            assertEquals(1, ps.executeUpdate(), "the envelope was not where it was looked for");
        }
        store.rebuildEnvelopes("ValueSet");
        Proves.that(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED,
                fullUrls(recordsDoor.get("/ValueSet?url=" + encoded(url)).body()) == 1,
                "a reindex did not rebuild the envelope where the bytes are");
    }

    // ── a clinic authors a search parameter of its own ──

    private static final String MARRIED = """
            {"resourceType":"Patient","name":[{"family":"Abielus"}],"maritalStatus":{"coding":[
              {"system":"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}]}}""";
    private static final String SINGLE = """
            {"resourceType":"Patient","name":[{"family":"Vallaline"}],"maritalStatus":{"coding":[
              {"system":"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"S"}]}}""";

    private String parameter(String slug, String code, String type, String expression) {
        return """
                {"resourceType":"SearchParameter","url":"%s","name":"%s","status":"active",
                 "description":"A parameter the clinic authored.",
                 "code":"%s","base":["Patient"],"type":"%s","expression":"%s"}"""
                .formatted(names.canonical("SearchParameter/" + slug), slug, code, type,
                        expression);
    }

    private String maritalParameter;

    @Test
    @Order(25)
    @DisplayName("a parameter the clinic has not written does not exist, and one whose "
            + "expression cannot be evaluated or compiled is refused on the write, by name")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS,
            DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES})
    void anUnwrittenOrUnreadableParameterIsNot() {
        assertEquals(201, recordsDoor.post("/Patient", MARRIED).statusCode());
        assertEquals(201, recordsDoor.post("/Patient", SINGLE).statusCode());
        HttpResponse<String> unknown = recordsDoor.get("/Patient?marital-status=M");
        assertTrue(unknown.statusCode() == 400
                && unknown.body().contains("unsupported search parameter"), unknown.body());
        assertFalse(recordsDoor.get("/metadata").body().contains("marital-status"));

        HttpResponse<String> unevaluable = recordsDoor.post("/SearchParameter",
                parameter("marital-broken", "marital-status", "token", "Patient.maritalStatus[[["));
        Proves.that(DboPromises.SRCH_CUSTOM_PARAMETERS,
                unevaluable.statusCode() == 422 && unevaluable.body().contains("cannot be evaluated"),
                "an expression that cannot be evaluated was taken: " + unevaluable.body());
        HttpResponse<String> uncompiled = recordsDoor.post("/SearchParameter",
                parameter("distinct-name", "distinct-name", "token",
                        "Patient.name.given.isDistinct()"));
        Proves.that(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES,
                uncompiled.statusCode() == 422 && uncompiled.body().contains("select")
                        && (uncompiled.body().contains("isDistinct")
                        || uncompiled.body().contains("Patient")),
                "a parameter that will not compile was not refused by name: "
                        + uncompiled.body());
    }

    @Test
    @Order(26)
    @DisplayName("what the clinic defines becomes searchable over its whole history, the "
            + "statement says so, and the parameter is held as a compiled row")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_HONEST_CAPABILITY,
            DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES})
    void whatTheClinicDefinesBecomesSearchable() throws Exception {
        HttpResponse<String> written = recordsDoor.post("/SearchParameter",
                parameter("marital-status", "marital-status", "token", "Patient.maritalStatus"));
        assertEquals(201, written.statusCode(), written.body());
        maritalParameter = dbo.says(written).one("id").orElseThrow();

        HttpResponse<String> married = untilAnswered("/Patient?marital-status=M", 200);
        Proves.that(DboPromises.SRCH_CUSTOM_PARAMETERS,
                married.statusCode() == 200 && married.body().contains("Abielus")
                        && !married.body().contains("Vallaline"),
                "a parameter the clinic wrote did not reach the patients written before it: "
                        + married.body());
        Proves.that(DboPromises.SRCH_HONEST_CAPABILITY,
                recordsDoor.get("/metadata").body().contains("marital-status"),
                "the statement does not say the clinic can search by its own parameter");

        String row = null;
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(records),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("SELECT kind || ' ' || expression || ' ' || "
                        + "coalesce(unenforceable, 'enforceable') || ' ' || paths::text "
                        + "FROM definitions.definition_parameter "
                        + "WHERE base = 'Patient' AND code = 'marital-status'");
                var rs = ps.executeQuery()) {
            if (rs.next()) {
                row = rs.getString(1);
            }
        }
        Proves.that(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES,
                row != null && row.startsWith("token Patient.maritalStatus enforceable")
                        && !row.endsWith("[]"),
                "the parameter is not held as an enforceable compiled row: " + row);
    }

    @Test
    @Order(27)
    @DisplayName("a date parameter the clinic writes is indexed in its database")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_DECLARED_INDEXES})
    void aDateParameterIsIndexed() throws Exception {
        assertEquals(201, recordsDoor.post("/SearchParameter",
                parameter("registered", "registered", "date", "Patient.birthDate")).statusCode());
        var store = tenants.store(records).orElseThrow();
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        boolean declared = false;
        while (!declared && System.nanoTime() < giveUp) {
            declared = store.registrationOf("Patient").indexes().stream()
                    .anyMatch(index -> index.path().equals("registered"));
            if (!declared) {
                Thread.sleep(500);
            }
        }
        String index = store.registrationOf("Patient").domain() + "_patient_registered_ix";
        boolean built;
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(records),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
            ps.setString(1, index);
            try (var rs = ps.executeQuery()) {
                built = rs.next();
            }
        }
        Proves.that(DboPromises.SRCH_DECLARED_INDEXES, declared && built,
                "a date parameter the clinic wrote is not indexed: declared=" + declared
                        + " " + index + " built=" + built);
    }

    @Test
    @Order(28)
    @DisplayName("withdrawing the parameter puts the clinic back as it was, statement and all")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_HONEST_CAPABILITY})
    void withdrawingTheParameterPutsTheClinicBack() throws Exception {
        assertTrue(recordsDoor.delete("/SearchParameter/" + maritalParameter).statusCode() < 300);
        HttpResponse<String> gone = untilAnswered("/Patient?marital-status=M", 400);
        Proves.that(DboPromises.SRCH_CUSTOM_PARAMETERS, gone.statusCode() == 400,
                "a withdrawn parameter still answers: " + gone.body());
        Proves.that(DboPromises.SRCH_HONEST_CAPABILITY,
                !recordsDoor.get("/metadata").body().contains("marital-status"),
                "the statement still offers a withdrawn parameter");
    }

    // ── and the clinic can be asked about its records from inside or across the wire ──

    private static final String ASKED = "urn:asking:test";

    @Test
    @Order(29)
    @DisplayName("asking from inside and across the wire count and walk the same records and "
            + "the same work, and refuse alike what cannot be answered")
    void bothBindingsAnswerTheSame() {
        String mine = names.value("asked");
        for (String state : List.of("final", "final", "preliminary")) {
            assertEquals(201, recordsDoor.post("/Observation", """
                    {"resourceType":"Observation","status":"%s","code":{"coding":[
                      {"system":"%s","code":"%s"}]},"subject":{"display":"nobody"}}"""
                    .formatted(state, ASKED, mine)).statusCode());
        }
        String process = names.prefix() + "-" + names.run() + ".weigh";
        String step = process + ".scale";
        String kase = names.value("case");
        var runs = new cloud.jengu.dbo.work.Runs(tenants.store(records).orElseThrow(),
                cloud.jengu.dbo.core.process.Steps.of(cloud.jengu.dbo.core.process.StepDeclaration
                        .of(step, "1", cloud.jengu.dbo.work.WorkModel.DOMAIN)));
        var weigher = new cloud.jengu.dbo.work.Executor("weigher", "1", "example",
                cloud.jengu.dbo.work.Scope.BASELINE);
        runs.held(runs.correlated(runs.pipeline(process, "scale", kase + "/a",
                List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN)), kase),
                cloud.jengu.dbo.work.Holder.PERSON);
        runs.claim(runs.correlated(runs.pipeline(process, "scale", kase + "/b",
                        List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN)), kase), weigher,
                java.time.Instant.now().plusSeconds(600));
        runs.closed(runs.claim(runs.correlated(runs.pipeline(process, "scale", kase + "/c",
                        List.of(cloud.jengu.dbo.work.WorkModel.DOMAIN)), kase), weigher,
                java.time.Instant.now().plusSeconds(600)).orElseThrow());

        var inside = dbo.asking(records);
        var across = cloud.jengu.dbo.asking.Across.through(pathAndQuery ->
                dbo.get(dbo.at(records) + "/fhir" + pathAndQuery, dbo.token(records)).body());

        assertEquals(3, inside.records("Observation").whereCoded("code", ASKED, mine).count());
        assertEquals(2, inside.records("Observation").whereCoded("code", ASKED, mine)
                .whereCoded("status", null, "final").count(), "narrowing did not narrow");
        assertEquals(inside.records("Observation").whereCoded("code", ASKED, mine).count(),
                across.records("Observation").whereCoded("code", ASKED, mine).count(),
                "the two bindings count the records differently");
        assertEquals(2, inside.work().correlated(kase).open().count());
        assertEquals(inside.work().correlated(kase).open().count(),
                across.work().correlated(kase).open().count(),
                "the two bindings count the open work differently");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                        UnsupportedOperationException.class,
                        () -> across.work().inScope("anything")).getMessage()
                .contains("no parameter on this tenant's surface"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> inside.records("Observation").where("favourite-colour", "blue").count())
                != null, "an undeclared narrowing was ignored rather than refused");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> across.records("NoSuchTypeHere").count()).getMessage()
                .contains("NoSuchTypeHere"));
    }

    @Test
    @Order(30)
    @DisplayName("a record's own trail answers about it, and a join that is declared and not "
            + "built is refused by name")
    @Proving(DboPromises.POL_AUDIT_AS_RECORDS)
    void theTrailAnswersAboutOneRecord() {
        HttpResponse<String> written = recordsDoor.post("/Observation", """
                {"resourceType":"Observation","status":"final","code":{"coding":[
                  {"system":"%s","code":"%s"}]},"subject":{"display":"nobody"}}"""
                .formatted(ASKED, names.value("trailed")));
        String id = dbo.says(written).one("id").orElseThrow();
        var trail = dbo.asking(records).trail().about("Observation", id);
        Proves.that(DboPromises.POL_AUDIT_AS_RECORDS,
                trail.count() >= 1 && trail.of("create").count() <= trail.count(),
                "a record's trail does not answer about it");
        var refused = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> dbo.asking(records).records("Observation").including("subject"));
        assertTrue(refused.getMessage().contains("declared and not built")
                && refused.getMessage().contains("subject"), refused.getMessage());
    }

    @Test
    @Order(31)
    @DisplayName("behind the membrane, a birth date is held only as coarse as the vault allows")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void aGeneralisedElementIsCoarseAtRest() throws java.sql.SQLException {
        String hospital = "hogwarts";
        String id = tenants.store(hospital).orElseThrow().put(cloud.jengu.dbo.core.api.PutRequest
                .create("Patient", ("{\"resourceType\":\"Patient\",\"birthDate\":\"1970-01-01\","
                        + "\"name\":[{\"family\":\"" + names.value("coarse") + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        String atRest;
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(hospital),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement("SELECT convert_from(payload, 'UTF8') FROM "
                        + "state.r5_data WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                atRest = rs.next() ? rs.getString(1) : null;
            }
        }
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT,
                atRest != null && atRest.contains("\"birthDate\":\"1970\"")
                        && !atRest.contains("1970-01-01"),
                "a birth date was held finer than the vault allows: " + atRest);
    }

    // ── and what a record is found by is extracted where its bytes are ──

    private static final String ROOT = "fhir-r4";

    /** One value from a query against a tenant's database, as text. */
    private String one(String tenant, String sql, String... parameters)
            throws java.sql.SQLException {
        try (var c = java.sql.DriverManager.getConnection(tenantDatabase(tenant),
                        environment.getRequiredProperty("dbo.admin.user"),
                        environment.getRequiredProperty("dbo.admin.password"));
                var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean pairs(String kind, String hit, String expected) throws java.sql.SQLException {
        return "true".equals(one(ROOT, "SELECT (COALESCE(jsonb_object_agg(key, vs), '{}'::jsonb) "
                + "= ?::jsonb)::text FROM (SELECT key, jsonb_agg(value) AS vs FROM "
                + "dbo.envelope_pairs('k', ?, ?::jsonb) GROUP BY key) one", expected, kind, hit));
    }

    @Test
    @Order(32)
    @DisplayName("each kind of search parameter is extracted in the database into the shape a "
            + "search asks by")
    @Proving(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE)
    void eachKindIsExtractedWhereTheBytesAre() throws java.sql.SQLException {
        Proves.that(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE,
                pairs("token", "{\"coding\":[{\"system\":\"urn:s\",\"code\":\"c\"}]}",
                        "{\"k\":[{\"t\":\"tok\",\"s\":\"urn:s\",\"v\":\"c\"},"
                                + "{\"t\":\"toks\",\"v\":\"urn:s\"},{\"t\":\"tokc\",\"v\":\"c\"}]}")
                        && pairs("token", "\"final\"", "{\"k\":[{\"t\":\"tokc\",\"v\":\"final\"}]}")
                        && pairs("string", "\"AbA\"", "{\"k\":[{\"t\":\"str\",\"v\":\"aba\"}],"
                                + "\"k_xct\":[{\"t\":\"str\",\"v\":\"AbA\"}]}")
                        && pairs("uri", "\"urn:X\"", "{\"k\":[{\"t\":\"str\",\"v\":\"urn:X\"}]}")
                        && pairs("date", "\"2020-03\"",
                                "{\"k\":[{\"t\":\"date\",\"v\":\"2020-03-01T00:00:00.000Z\"}]}")
                        && pairs("date", "{\"start\":\"2021-05-06\",\"end\":\"2021-06-01\"}",
                                "{\"k\":[{\"t\":\"date\",\"v\":\"2021-05-06T00:00:00.000Z\"}]}")
                        && pairs("reference", "{\"reference\":\"Patient/123\"}",
                                "{\"k\":[{\"t\":\"ref\",\"tt\":\"Patient\",\"ti\":\"123\"}]}")
                        && pairs("reference", "\"https://example.test/fhir/Patient/123\"",
                                "{\"k\":[{\"t\":\"ref\",\"tt\":\"Patient\",\"ti\":\"123\"}]}")
                        && pairs("number", "\"12.5\"", "{\"k\":[{\"t\":\"num\",\"v\":12.5}]}")
                        && pairs("number", "\"not a number\"", "{}")
                        && pairs("quantity", "{\"value\":1}", "{}"),
                "a kind of parameter is not extracted into the shape a search asks by");
        Proves.that(DboPromises.SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE,
                "true".equals(one(ROOT, "SELECT ((e->'url') = '[{\"t\":\"str\",\"v\":"
                        + "\"https://envelope.test/vs\"}]'::jsonb AND jsonb_exists(e, 'status'))::text FROM "
                        + "(SELECT dbo.envelope(?::jsonb, 'ValueSet') AS e) one",
                        "{\"resourceType\":\"ValueSet\",\"url\":\"https://envelope.test/vs\","
                                + "\"status\":\"draft\",\"name\":\"Whatever\"}")),
                "the envelope is not built from the parameters the tenant holds");
    }

    @Test
    @Order(33)
    @DisplayName("what the database extracts loses nothing the engine stored: the envelope, "
            + "the claims and the edges, over the documents the root carries")
    @Proving(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED)
    void theDatabaseLosesNothingTheEngineStored() throws java.sql.SQLException {
        String definitions = cloud.jengu.dbo.core.api.Domains.tables(
                cloud.jengu.dbo.core.api.Domains.DEFINITIONS);
        java.util.Map<String, String> differing = new java.util.TreeMap<>();
        for (String type : List.of("StructureDefinition", "SearchParameter", "ValueSet",
                "CodeSystem")) {
            differing.put(type, one(ROOT, """
                    WITH x AS (
                      SELECT id, envelope, dbo.envelope_parts(
                               convert_from(payload, 'UTF8')::jsonb, type, true) AS p
                        FROM %1$s_data WHERE type = ? AND NOT deleted ORDER BY id LIMIT 25)
                    SELECT count(*)::text || ' compared, ' || count(*) FILTER (WHERE
                        NOT ((p->'envelope') @> envelope)
                        OR ARRAY(SELECT DISTINCT e->>'system' || '|' || (e->>'value')
                                   FROM jsonb_array_elements(p->'identifiers') e ORDER BY 1)
                           IS DISTINCT FROM
                           ARRAY(SELECT DISTINCT system || '|' || value
                                   FROM %1$s_identifier i WHERE i.object_id = x.id ORDER BY 1)
                        OR ARRAY(SELECT DISTINCT (e->>'refType') || ' -> ' || (e->>'targetType')
                                   || '/' || (e->>'targetId')
                                   FROM jsonb_array_elements(p->'references') e ORDER BY 1)
                           IS DISTINCT FROM
                           ARRAY(SELECT DISTINCT ref_type || ' -> ' || target_type || '/'
                                   || target_id FROM %1$s_reference r WHERE r.owner_id = x.id
                                   ORDER BY 1))::text || ' differing'
                      FROM x""".formatted(definitions), type));
        }
        Proves.that(DboPromises.SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED,
                differing.values().stream().allMatch("25 compared, 0 differing"::equals),
                "the database's extraction loses something the engine stored: " + differing);
    }

    @org.junit.jupiter.api.AfterAll
    void theRecordsClinicIsWithdrawn() {
        if (records != null) {
            dbo.retract(records);
        }
    }

    private HttpResponse<String> untilAnswered(String path, int status)
            throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        HttpResponse<String> answered = recordsDoor.get(path);
        while (answered.statusCode() != status && System.nanoTime() < giveUp) {
            Thread.sleep(500);
            answered = recordsDoor.get(path);
        }
        return answered;
    }

    private static int fullUrls(String bundle) {
        int n = 0;
        for (int at = bundle.indexOf("\"fullUrl\""); at >= 0;
                at = bundle.indexOf("\"fullUrl\"", at + 1)) {
            n++;
        }
        return n;
    }

    private String tenantDatabase(String tenant) {
        String admin = environment.getRequiredProperty("dbo.admin.jdbc-url");
        return admin.substring(0, admin.lastIndexOf('/') + 1) + "tenant_"
                + tenant.replace('-', '_');
    }

    @org.junit.jupiter.api.AfterAll
    void theSubscriberStops() {
        if (listening != null) {
            listening.stop(0);
        }
    }

    private String referral;

    private HttpResponse<String> putContent(byte[] content, String media, String bearer)
            throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(CLINIC) + "/blob"))
                .header("Content-Type", media)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A location the clinic answered with, on the application's own port. */
    private java.net.URI serverUri(String location) {
        return java.net.URI.create(dbo.at(CLINIC)).resolve(location);
    }

    /** A credential the clinic issued for reading only. */
    private String reader() {
        String client = names.value("blob-reader");
        var authority = tenants.authority(CLINIC).orElseThrow();
        authority.ensureClient(client, client + "-secret", List.of("system/*.read"));
        if (authority.token(client, client + "-secret", null)
                instanceof cloud.jengu.dbo.auth.TenantAuthority.TokenResult.Issued minted) {
            return minted.accessToken();
        }
        throw new IllegalStateException("St Jerome would not issue a reading credential");
    }
}
