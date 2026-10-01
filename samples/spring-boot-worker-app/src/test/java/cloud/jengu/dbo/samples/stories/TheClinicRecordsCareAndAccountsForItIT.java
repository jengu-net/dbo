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
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-CLINICAL-RECORD, walked on the sample world.
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

    /** The zone St Jerome takes its code systems from. */
    private static final String ZONE = "rl";

    /** The system St Jerome keys its patients by, in the sample world's spec. */
    private static final String MRN = "urn:st-jerome:mrn";

    private final StoryNames names = StoryNames.of(DboStories.CLINICAL_RECORD);

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

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
        // The zone publishes it, and St Jerome takes the zone's code systems.
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
}
