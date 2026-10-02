package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
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
import org.springframework.core.env.Environment;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static cloud.jengu.dbo.samples.stories.APersonsDoors.encoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-PERSON-RIGHTS, walked in Rowling Land, the sample world.
 *
 * <p>Liis is a patient at Hogwarts, which holds its people behind the
 * membrane: what identifies her is sealed in the tenant's vault, and the
 * record the store keeps is pseudonymous. This is what she can ask for, and
 * what the store asks of anybody looking for her. The legs run as her scenes
 * do: she is read, looked up with a reason, identified, given pseudonyms and
 * a recording; then she asks to be forgotten, and what is left afterwards is
 * read. Erasure comes late because it destroys her key, and everything before
 * it needs that key. The clinicians who act for each other come last, because
 * their records are not hers.
 *
 * <p><b>The hospital keys patients and people by the national number</b>, a
 * system the world fixes, so this story cannot key Liis by a system of its
 * own. Her number, and every other this story writes there, carries the
 * story's prefix and this run's mark instead, which is what keeps them apart
 * from every other record the stories running beside this one write. The
 * clients, logins and role it registers with the hospital's authority are
 * named the same way.
 */
@AUserStory
class WhatAPersonCanAskForIT {

    private static final String HOSPITAL = "hogwarts";

    /** The system Hogwarts keys its patients and people by, in the sample world's spec. */
    private static final String NATIONAL_NUMBER = "urn:rl:nid";

    private static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String REDIRECT = "http://127.0.0.1/cb";

    /** Why the request desk turns a pseudonym back into a person. */
    private static final String WHY = "reuniting a health fact with the person it is about";

    private final StoryNames names = StoryNames.of(DboStories.PERSON_RIGHTS);

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    Environment environment;

    /** The clinic asking for its records to be written, and hearing back. */
    @Autowired
    cloud.jengu.dbo.samples.server.AskingForARegistration registering;

    @Autowired
    cloud.jengu.dbo.samples.server.AskingWhoSomebodyIs identifying;

    @Autowired
    cloud.jengu.dbo.samples.server.AskingForACorrection correcting;

    @Autowired
    cloud.jengu.dbo.samples.worker.HearingBack hearing;

    private APersonsDoors doors;
    private WhatTheDatabaseHolds database;

    /** Her Patient record, and the Person record that says who she is. */
    private String liis;
    private String herPerson;
    /** Somebody else, whose pseudonyms must not move when hers do. */
    private String him;
    private String hers;
    private String recordingAt;
    private byte[] recording;
    /** The client the request desk identifies people and resolves pseudonyms with. */
    private String broker;

    // The clinician who acts through a process, and what they act through.
    private String practitioner;
    private String clinicianPerson;
    /** The role the hospital grants them, and the record that gives it to them. */
    private String role;
    private String roleRecord;
    private String humanToken;
    private String delegation;

    @BeforeAll
    void theHospitalsDoors() {
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow(
                () -> new AssertionError(HOSPITAL + " has no authority: " + dbo.serving()));
        doors = new APersonsDoors(dbo, authority, HOSPITAL);
        database = new WhatTheDatabaseHolds(environment, HOSPITAL);
    }

    // ── reading her is not the same as writing her ──

    @Test
    @Order(1)
    @DisplayName("a credential that may write every type still reads Liis back without her "
            + "name, because what identifies her lives in the vault")
    @Proving({DboPromises.PDI_STRUCTURAL_VAULT, DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED})
    void readingHerIsNotTheSameAsWritingHer() {
        // Written by the hospital, from what a step answered with: the
        // clinic's application gave her and never held a records credential.
        var written = hearing.settled(HOSPITAL, registering.register(HOSPITAL, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(NATIONAL_NUMBER, names.value("liis"))),
                Duration.ofMinutes(3));
        assertEquals("completed", written.state(), "Liis was not recorded: " + written.body());
        liis = idOf(written, "Patient");

        HttpResponse<String> read = dbo.read(HOSPITAL, "Patient", liis);
        var record = dbo.says(read);
        // Answered rather than refused. What a recipient sees follows what the
        // hospital declared, not how much the credential may write, and work
        // that never needed her still runs.
        Proves.that(DboPromises.IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED,
                read.statusCode() == 200 && record.has("resourceType") && !record.has("name"),
                "a credential that may write every type and states no reason was not answered "
                        + "with her record without her in it: " + read.statusCode() + " "
                        + read.body());
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, !record.has("name"),
                "her name came back to a credential nothing declared may identify her: "
                        + read.body());

        // The other half, so the absence above is the vault's doing and not a
        // record that lost her name: stating why it reads, the same credential
        // is given her back.
        HttpResponse<String> treating = doors.readFor("TREAT", "Patient/" + liis);
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, treating.body().contains("Tamm"),
                "a read stating treatment did not reassemble her, so the vault is not "
                        + "holding her name but has lost it: " + treating.body());
    }

    @Test
    @Order(2)
    @DisplayName("what passes through the hospital's database to reach her record is not "
            + "written down there, because the database is pinned not to log parameters")
    @Proving(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE)
    void herPlaintextPassesThroughAndLeavesNoTrace() {
        // As a fresh session sees it, which is how every pooled session the
        // store holds sees it: the pin is a property of the database.
        Proves.that(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE,
                "0".equals(database.setting("log_parameter_max_length")),
                "a slow statement writing Liis would be logged with her in its parameters: "
                        + database.setting("log_parameter_max_length"));
        Proves.that(DboPromises.PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE,
                "0".equals(database.setting("log_parameter_max_length_on_error")),
                "a failing statement writing Liis would be logged with her in its parameters: "
                        + database.setting("log_parameter_max_length_on_error"));
    }

    @Test
    @Order(3)
    @DisplayName("Liis is held as a Patient and as the Person who is her, one human under one "
            + "key, while a second Person claiming her number or her record is refused")
    @Proving({DboPromises.PDI_STRUCTURAL_VAULT, DboPromises.CORE_NO_IMPLICIT_MERGE})
    void sheIsOneHumanHeldAsTwoRecords() {
        var person = hearing.settled(HOSPITAL, identifying.identify(HOSPITAL, """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "link":[{"target":{"reference":"Patient/%s"}}]}"""
                .formatted(NATIONAL_NUMBER, names.value("liis"), liis)), Duration.ofMinutes(3));
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, "completed".equals(person.state()),
                "the Person who is Liis carries the number her Patient record carries, and "
                        + "was refused as a conflict against it, so a human cannot be held as "
                        + "both — which is the ordinary way of holding one: " + person.body());
        herPerson = idOf(person, "Person");

        // A record re-asserting its own claim is not a second claimant.
        var again = hearing.settled(HOSPITAL, correcting.correct(HOSPITAL, "Patient/" + liis, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(NATIONAL_NUMBER, names.value("liis"))),
                Duration.ofMinutes(3));
        assertEquals("completed", again.state(), again.body());

        var twin = hearing.settled(HOSPITAL, identifying.identify(HOSPITAL, """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Kask"}]}""".formatted(NATIONAL_NUMBER, names.value("liis"))),
                Duration.ofMinutes(3));
        Proves.that(DboPromises.CORE_NO_IMPLICIT_MERGE, "failed".equals(twin.state()),
                "a second Person claiming Liis's number was written, so the store merged two "
                        + "people by silence: " + twin.body());

        // And a link that would join two people who are each identified is
        // refused rather than decided here: her record is already somebody's.
        var joined = hearing.settled(HOSPITAL, identifying.identify(HOSPITAL, """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Teine"}],
                 "link":[{"target":{"reference":"Patient/%s"}}]}"""
                .formatted(NATIONAL_NUMBER, names.value("teine"), liis)), Duration.ofMinutes(3));
        Proves.that(DboPromises.CORE_NO_IMPLICIT_MERGE, "failed".equals(joined.state()),
                "a link joined Liis's record to another identified person, so the store "
                        + "decided which human she is: " + joined.body());
    }

    // ── looking somebody up is an act with a reason ──

    @Test
    @Order(4)
    @DisplayName("looking Liis up by her national number is refused until the caller says "
            + "what it is for, and then finds her and nobody else")
    @Proving({DboPromises.PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL, DboPromises.PDI_EXACT_RESOLUTION})
    void lookingSomebodyUpIsAnActWithAReason() {
        String byHerNumber = "identifier=" + NATIONAL_NUMBER + "|" + names.value("liis");

        HttpResponse<String> unstated = dbo.search(HOSPITAL, "Patient", byHerNumber);
        Proves.that(DboPromises.PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL,
                unstated.statusCode() == 403,
                "an identifying search with no purpose answered " + unstated.statusCode()
                        + " rather than a refusal the caller can act on: " + unstated.body());

        HttpResponse<String> stated = dbo.search(HOSPITAL, "Patient", byHerNumber, "TREAT");
        assertEquals(200, stated.statusCode(), stated.body());
        List<String> ids = dbo.says(stated).at("entry.resource.id");
        Proves.that(DboPromises.PDI_EXACT_RESOLUTION, ids.equals(List.of(liis)),
                "her number, with a purpose stated, should resolve to her and only her, and "
                        + "resolved to " + ids + " where she is " + liis + ": " + stated.body());

        // The same number finds the Person who is her: each record that
        // carries it is found by it, not only the first to claim it.
        HttpResponse<String> asPerson = dbo.search(HOSPITAL, "Person", byHerNumber, "TREAT");
        List<String> people = dbo.says(asPerson).at("entry.resource.id");
        Proves.that(DboPromises.PDI_EXACT_RESOLUTION, people.equals(List.of(herPerson)),
                "her number found " + people + " among people where the Person who is her is "
                        + herPerson + ": " + asPerson.body());
    }

    @Test
    @Order(5)
    @DisplayName("every way a record of a person can carry a number, a lookup by it finds the "
            + "record or refuses, and never answers empty for something the hospital holds")
    @Proving({DboPromises.PDI_EXACT_RESOLUTION, DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS})
    void aNumberTheHospitalHoldsIsNeverAnsweredEmpty() {
        List<String> silent = new ArrayList<>();
        // the types identified by that system, each claiming the value
        lookedUp("Person", NATIONAL_NUMBER, names.value("person"), silent);
        lookedUp("Patient", NATIONAL_NUMBER, names.value("patient"), silent);
        lookedUp("Practitioner", NATIONAL_NUMBER, names.value("practitioner"), silent);
        // a value of a system nothing here is identified by, carried beside one
        lookedUp("Patient", names.system(), names.value("card"), silent);
        Proves.that(DboPromises.PDI_EXACT_RESOLUTION, silent.isEmpty(),
                "these are held here and a lookup for them answered an empty bundle. Empty "
                        + "reads as nobody here, and a caller acting on it creates the person "
                        + "again:\n  " + String.join("\n  ", silent));

        // A conditional create asks the question a lookup answers, so the
        // desk registering somebody twice leaves one record.
        String question = "identifier=" + encoded(NATIONAL_NUMBER + "|" + names.value("uks"));
        String document = """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Uks"}]}""".formatted(NATIONAL_NUMBER, names.value("uks"));
        ATenantsDoor door = new ATenantsDoor(dbo, HOSPITAL);
        HttpResponse<String> first = door.post("/Patient", document, "If-None-Exist", question);
        assertEquals(201, first.statusCode(), first.body());
        HttpResponse<String> twice = door.post("/Patient", document, "If-None-Exist", question);
        Proves.that(DboPromises.CORE_IDENTITY_KEYED_CONDITIONALS,
                twice.headers().firstValue("Location").orElse("").contains(idIn(first)),
                "the second asking made a second record behind the membrane: "
                        + twice.statusCode() + " "
                        + twice.headers().firstValue("Location").orElse("(no location)"));
    }

    // ── who she is, decided rather than guessed ──

    @Test
    @Order(6)
    @DisplayName("identifying somebody is a door of its own: a claim resolves to the people the "
            + "hospital holds, an unchecked one never resolves with certainty, a stranger is an "
            + "answer, and a credential that may write everything may not knock")
    @Proving({DboPromises.IDN_IDENTIFICATION_IS_REACHABLE,
            DboPromises.IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION})
    void identifyingHerIsADoorOfItsOwn() {
        HttpResponse<String> checked = identify("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"CHECKED"}]}"""
                .formatted(NATIONAL_NUMBER, names.value("liis")));
        assertEquals(200, checked.statusCode(), checked.body());
        Proves.that(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE,
                checked.body().contains(herPerson) && checked.body().contains("\"confidence\""),
                "the Person who is Liis was not offered as a candidate, with how sure the "
                        + "store is, to a claim of her number: " + checked.body());

        HttpResponse<String> asserted = identify("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"ASSERTED"}]}"""
                .formatted(NATIONAL_NUMBER, names.value("liis")));
        Proves.that(DboPromises.IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION,
                asserted.statusCode() == 200 && !asserted.body().contains("\"CERTAIN\""),
                "a number nobody checked resolved her with certainty, so a number read off a "
                        + "card is enough to attach one person's care to another: "
                        + asserted.body());

        HttpResponse<String> stranger = identify("resolve", """
                {"claims":[{"system":"%s","value":"%s","verification":"AUTHENTICATED"}]}"""
                .formatted(NATIONAL_NUMBER, names.value("nobody")));
        Proves.that(DboPromises.IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION,
                stranger.statusCode() == 200 && !stranger.body().contains(herPerson),
                "somebody before their first visit was answered as a fault, or as Liis: "
                        + stranger.statusCode() + " " + stranger.body());

        HttpResponse<String> broad = doors.post("/identity/resolve", "{\"claims\":[]}",
                doors.tokenFor(names.code("writes-all"), "system/*.write"));
        Proves.that(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE,
                broad.statusCode() == 403 && broad.body().contains("identity"),
                "a broad write grant reached identification, or was refused without naming "
                        + "what it lacked: " + broad.statusCode() + " " + broad.body());
        HttpResponse<String> none = doors.post("/identity/resolve", "{\"claims\":[]}", null);
        Proves.that(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE, none.statusCode() == 401,
                "the identification door answered somebody with no credential: "
                        + none.statusCode() + " " + none.body());
    }

    @Test
    @Order(7)
    @DisplayName("a person's decision about who somebody is comes back with the next "
            + "resolution, a binding can be withdrawn, and somebody who declared anonymity is "
            + "not bound")
    @Proving({DboPromises.IDN_IDENTIFICATION_IS_REACHABLE, DboPromises.IDN_A_DECISION_IS_EVIDENCE,
            DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE,
            DboPromises.IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED})
    void whoSheIsIsDecidedAndCanBeUndone() {
        String claim = """
                [{"system":"%s","value":"%s","verification":"CHECKED"}]"""
                .formatted(NATIONAL_NUMBER, names.value("liis"));
        HttpResponse<String> decided = identify("adjudicate", """
                {"outcome":"CREATED","subject":"%s","rejected":["%s"],
                 "decidedBy":"%s","because":"different birth year on the document",
                 "claims":%s}""".formatted(names.value("newcomer"), herPerson,
                names.value("registrar"), claim));
        assertEquals(201, decided.statusCode(), decided.body());
        HttpResponse<String> recalled = identify("resolve", "{\"claims\":" + claim + "}");
        Proves.that(DboPromises.IDN_A_DECISION_IS_EVIDENCE,
                recalled.body().contains("\"previouslyRejected\":true"),
                "the candidate somebody examined and declined is offered again as though for "
                        + "the first time: " + recalled.body());

        String subject = names.value("patient-1");
        assertEquals(201, identify("bind", """
                {"identity":"%s","subject":"%s","assurance":"SUBSTANTIAL",
                 "actor":"%s","purpose":"registration"}"""
                .formatted(herPerson, subject, names.value("registrar"))).statusCode());
        HttpResponse<String> bound = identify("subject", "{\"subject\":\"" + subject + "\"}");
        Proves.that(DboPromises.IDN_IDENTIFICATION_IS_REACHABLE,
                bound.body().contains(herPerson) && bound.body().contains("SUBSTANTIAL"),
                "the binding is not visible where a caller would look for it: " + bound.body());
        assertEquals(201, identify("unbind", """
                {"identity":"%s","subject":"%s","actor":"%s",
                 "purpose":"correction","because":"wrong person"}"""
                .formatted(herPerson, subject, names.value("registrar"))).statusCode());
        HttpResponse<String> withdrawn = identify("subject", "{\"subject\":\"" + subject + "\"}");
        Proves.that(DboPromises.IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE,
                !withdrawn.body().contains(herPerson),
                "a withdrawn binding still stands, so a wrong identification cannot be taken "
                        + "back: " + withdrawn.body());

        String anonymous = names.value("patient-2");
        assertEquals(201, identify("anonymity", """
                {"kind":"DECLARED","subject":"%s","actor":"%s",
                 "basis":"asked not to be identified"}""".formatted(anonymous, anonymous))
                .statusCode());
        Proves.that(DboPromises.IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED,
                identify("subject", "{\"subject\":\"" + anonymous + "\"}").body()
                        .contains("\"anonymous\":true"),
                "the declaration is invisible to a caller, who cannot tell them from somebody "
                        + "merely not identified yet");
        HttpResponse<String> refused = identify("bind", """
                {"identity":"%s","subject":"%s","assurance":"HIGH",
                 "actor":"%s","purpose":"registration"}"""
                .formatted(herPerson, anonymous, names.value("registrar")));
        Proves.that(DboPromises.IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED,
                refused.statusCode() == 409,
                "somebody who declared they are not to be identified was bound anyway: "
                        + refused.statusCode() + " " + refused.body());
    }

    // ── a pseudonym, derived and turned back ──

    @Test
    @Order(8)
    @DisplayName("Liis's pseudonym is the same each time it is asked for and is written nowhere, "
            + "another scope or another person answers something else, and none is given "
            + "without a scope")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void herPseudonymIsDerivedAndNeverKept() {
        var he = dbo.write(HOSPITAL, "Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Varjunimi"}]}""".formatted(NATIONAL_NUMBER, names.value("him")));
        assertTrue(he.accepted(), he.body());
        him = he.idOrFail();

        hers = pseudonym("Person/" + herPerson, "research");
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT,
                hers.equals(pseudonym("Person/" + herPerson, "research")),
                "asking twice answered differently, so the pseudonym cannot recognise her");
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, database.mentioning(hers) == 0,
                "her pseudonym is written down in the hospital's database, which is the "
                        + "correlatable link the derivation exists to not have");

        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT,
                !hers.equals(pseudonym("Person/" + herPerson, "billing")),
                "two scopes answered the same value, so her pseudonym in one context "
                        + "identifies her in every other");
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT,
                !hers.equals(pseudonym("Person/" + him, "research")),
                "two people answered the same pseudonym in one scope");
        HttpResponse<String> unscoped = doors.post("/identity/pseudonym",
                "{\"subject\":\"Person/" + herPerson + "\"}", broker());
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, unscoped.statusCode() == 400,
                "a pseudonym without a scope was given, which would be her only pseudonym: "
                        + unscoped.statusCode() + " " + unscoped.body());
    }

    @Test
    @Order(9)
    @DisplayName("her pseudonym, with a scope and a reason, is turned back into her; under "
            + "another scope it is nobody; and the trail keeps who asked and why without "
            + "keeping the pseudonym")
    @Proving(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN)
    void herPseudonymResolvesBackToHer() {
        HttpResponse<String> found = resolve(hers, "research");
        assertEquals(200, found.statusCode(), found.body());
        String person = dbo.says(found).one("person").orElseThrow(
                () -> new AssertionError("her pseudonym resolved to nobody: " + found.body()));
        // By round trip rather than by an id this story was told: only her own
        // key answers what she answers under a second scope.
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                pseudonym(person, "billing").equals(pseudonym("Person/" + herPerson, "billing")),
                "the resolution answered somebody whose key is not the one her pseudonym was "
                        + "made from: " + found.body());

        HttpResponse<String> elsewhere = resolve(hers, "billing");
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                elsewhere.statusCode() == 200 && elsewhere.body().contains("\"found\":false"),
                "a miss was not answered as an ordinary nobody: " + elsewhere.statusCode()
                        + " " + elsewhere.body());

        HttpResponse<String> unscoped = doors.post("/identity/pseudonym/resolve", """
                {"pseudonym":"%s","actor":"%s","purpose":"x"}""".formatted(hers, broker),
                broker());
        HttpResponse<String> purposeless = doors.post("/identity/pseudonym/resolve", """
                {"pseudonym":"%s","scope":"research","actor":"%s"}""".formatted(hers, broker),
                broker());
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                unscoped.statusCode() == 400 && purposeless.statusCode() == 400,
                "a pseudonym was turned back into a person without its scope or without "
                        + "anybody saying why: " + unscoped.statusCode() + " "
                        + purposeless.statusCode());

        List<String> trail = dbo.asking(HOSPITAL).records("PseudonymResolution")
                .where("actor", broker).stream()
                .map(kept -> new String(kept.payload(), StandardCharsets.UTF_8))
                .toList();
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                !trail.isEmpty() && trail.stream().allMatch(row -> row.contains(WHY)),
                "the desk's resolutions are not on the trail with why they were made, so "
                        + "'who turned my pseudonym back into me' has no answer: " + trail);
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                trail.stream().anyMatch(row -> row.contains("\"found\":false")),
                "only the resolutions that reached somebody were recorded: " + trail);
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN, database.mentioning(hers) == 0,
                "after it was resolved, her pseudonym is in the hospital's database: the "
                        + "stored mapping arriving one question at a time");
    }

    // ── what she said is sealed to her ──

    @Test
    @Order(10)
    @DisplayName("a recording of Liis is kept sealed to her: it reads back as the bytes that "
            + "were sent, and what lies at rest is not those bytes")
    @Proving(DboPromises.PDI_CRYPTO_SHREDDING)
    void herRecordingIsSealedToHer() {
        recording = new byte[4096];
        new SecureRandom().nextBytes(recording);
        HttpResponse<String> kept = doors.keep(recording, "audio/ogg", "Patient/" + liis);
        assertEquals(201, kept.statusCode(), kept.body());
        recordingAt = kept.headers().firstValue("Location").orElseThrow();

        HttpResponse<byte[]> back = doors.fetch(recordingAt);
        assertEquals(200, back.statusCode());
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING, Arrays.equals(recording, back.body()),
                "her recording did not come back as what was sent, so the seal is changing "
                        + "the thing it protects");

        var atRest = database.content(recordingAt.substring(recordingAt.lastIndexOf('/') + 1))
                .orElseThrow(() -> new AssertionError("nothing is kept at " + recordingAt));
        // The person, not the record that names her. A record id taken for a
        // person seals under a key belonging to nobody, which then survives
        // her erasure.
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING,
                atRest.person() != null && !atRest.person().equals(liis),
                "the recording is kept against " + atRest.person() + ", which is not the "
                        + "person behind Patient/" + liis);
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING,
                !Arrays.equals(atRest.atRest(), recording),
                "her recording lies at rest as it was sent, so destroying her key would "
                        + "destroy nothing");
    }

    // ── erasure is its own authority, and it is asked for like any other work ──

    @Test
    @Order(11)
    @DisplayName("Liis asks to be forgotten: the desk's own credential opens a run keyed by "
            + "her, which says how far it got, and asking again finds the same run, while a "
            + "credential that may write everything cannot erase anybody")
    @Proving({DboPromises.PDI_ERASURE_IS_A_RUN, DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT})
    void sheAsksToBeForgotten() {
        String subject = "{\"subject\":\"Person/" + herPerson + "\"}";
        HttpResponse<String> broad = doors.post("/erasure", subject, dbo.token(HOSPITAL));
        assertEquals(403, broad.statusCode(),
                "a credential that may write every type erased somebody: " + broad.body());
        HttpResponse<String> nobody = doors.post("/erasure", subject, null);
        assertEquals(401, nobody.statusCode(),
                "the desk answered somebody with no credential: " + nobody.body());

        // --8<-- [start:erasure]
        String desk = doors.tokenFor(names.code("desk"), "erasure");
        HttpResponse<String> asked = doors.post("/erasure", subject, desk);
        assertEquals(202, asked.statusCode(), asked.body());
        var receipt = dbo.says(asked);
        String run = receipt.one("run").orElseThrow(
                () -> new AssertionError("the erasure answered without a run: " + asked.body()));
        // --8<-- [end:erasure]
        Proves.that(DboPromises.PDI_ERASURE_IS_A_RUN,
                receipt.one("open").equals(java.util.Optional.of("false")),
                "the run is still open after the erasure answered, so the receipt does not "
                        + "say it was done: " + asked.body());
        Proves.that(DboPromises.PDI_ERASURE_SAYS_HOW_FAR_IT_GOT,
                receipt.one("tally.keyDestroyed").equals(java.util.Optional.of("1"))
                        && receipt.one("tally.known").equals(java.util.Optional.of("1")),
                "the receipt does not say her key was there and was destroyed, which is what "
                        + "tells erased apart from was-never-here: " + asked.body());

        HttpResponse<String> twice = doors.post("/erasure", subject, desk);
        Proves.that(DboPromises.PDI_ERASURE_IS_A_RUN,
                twice.statusCode() == 202
                        && dbo.says(twice).one("run").equals(java.util.Optional.of(run)),
                "asking twice opened a second account of one erasure: " + twice.statusCode()
                        + " " + twice.body() + " where the first was " + run);
    }

    // ── afterwards ──

    @Test
    @Order(12)
    @DisplayName("afterwards her records keep their shape and lose her, even to a reader "
            + "stating treatment, and her number, her pseudonym and her recording reach "
            + "nobody, while the trail still says something happened to her record")
    @Proving({DboPromises.PDI_CRYPTO_SHREDDING, DboPromises.POL_ERASURE_COMPATIBLE,
            DboPromises.PDI_UNFINDABLE_AFTER_ERASURE, DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN})
    void afterwardsNothingReachesHer() {
        // Erased through the Person who is her, read through her Patient
        // record: the reference names a record, the erasure reaches the human.
        HttpResponse<String> remains = doors.readFor("TREAT", "Patient/" + liis);
        assertEquals(200, remains.statusCode(), remains.body());
        var record = dbo.says(remains);
        Proves.that(DboPromises.POL_ERASURE_COMPATIBLE,
                record.one("resourceType").equals(java.util.Optional.of("Patient")),
                "her record lost its shape as well as her: " + remains.body());
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING,
                !record.has("name") && !record.has("birthDate") && !record.has("identifier"),
                "a reader stating treatment still reads her out of the record her erasure "
                        + "was asked through another of: " + remains.body());

        HttpResponse<String> byNumber = dbo.search(HOSPITAL, "Patient",
                "identifier=" + NATIONAL_NUMBER + "|" + names.value("liis"), "TREAT");
        Proves.that(DboPromises.PDI_UNFINDABLE_AFTER_ERASURE,
                byNumber.statusCode() == 200
                        && dbo.says(byNumber).at("entry.resource.id").isEmpty(),
                "she is still found by her number after her erasure: " + byNumber.body());

        HttpResponse<String> derived = doors.post("/identity/pseudonym",
                "{\"subject\":\"" + "Person/" + herPerson + "\",\"scope\":\"research\"}",
                broker());
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING, derived.statusCode() == 410,
                "her pseudonym is still derivable after her key was destroyed: "
                        + derived.statusCode() + " " + derived.body());
        HttpResponse<String> resolved = resolve(hers, "research");
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                resolved.statusCode() == 200 && resolved.body().contains("\"found\":false"),
                "her pseudonym still reaches her after her key was destroyed: "
                        + resolved.body());
        String his = pseudonym("Person/" + him, "research");
        Proves.that(DboPromises.PDI_PSEUDONYM_RESOLVED_BY_SCAN,
                resolve(his, "research").body().contains("\"found\":true"),
                "erasing her stopped somebody else being resolvable, so the walk has stopped "
                        + "working rather than stopped finding her");

        HttpResponse<String> heard = doors.fetchText(recordingAt);
        Proves.that(DboPromises.PDI_CRYPTO_SHREDDING,
                heard.statusCode() == 410 && heard.body().contains("erased"),
                "her recording still opens after her erasure, or answers without saying it "
                        + "was erased: " + heard.statusCode() + " " + heard.body());

        // Scoped to her record: whether the account of what happened to HER
        // survived is the question, not what the hospital did lately.
        HttpResponse<String> trail = dbo.get(dbo.at(HOSPITAL) + "/fhir/AuditEvent?entity=Patient/"
                + liis, dbo.token(HOSPITAL));
        Proves.that(DboPromises.POL_ERASURE_COMPATIBLE,
                !dbo.says(trail).at("entry.resource.id").isEmpty()
                        && !trail.body().contains("Tamm"),
                "the trail either went with her or still names her: " + trail.body());
    }

    // ── the people who work here act for each other ──

    @Test
    @Order(13)
    @DisplayName("a process acts in a clinician's name by exchanging their token: the subject "
            + "stays the clinician, the process is named as the actor, the scopes narrow, and "
            + "the trail names both")
    @Proving(DboPromises.AUTH_ON_BEHALF_OF)
    void aProcessActsInAClinicianName() {
        theClinicianSignsIn();

        HttpResponse<String> exchanged = doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&subject_token=" + humanToken
                + "&scope=" + encoded("user/Encounter.write"));
        String acting = tokenIn(exchanged);
        String claims = claimsOf(acting);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                claims.contains("\"sub\":\"" + clinicianPerson + "\"")
                        && claims.contains("\"act\":{\"sub\":\"" + engineClient() + "\"}")
                        && claims.contains("\"fhirUser\":\"Practitioner/" + practitioner + "\""),
                "the exchanged token does not keep the clinician as its subject, name the "
                        + "process as the actor and the capacity as fhirUser: " + claims);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                claims.contains("user/Encounter.write") && !claims.contains("user/*.read"),
                "the scopes did not narrow to what was asked of what the clinician holds: "
                        + claims);

        var written = new ATenantsDoor(dbo, HOSPITAL).postAs("/Encounter", """
                {"resourceType":"Encounter","status":"planned",
                 "class":[{"coding":[{"code":"AMB"}]}]}""", acting);
        assertEquals(201, written.statusCode(), written.body());
        String encounter = idIn(written);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                dbo.get(dbo.at(HOSPITAL) + "/fhir/Patient?_summary=count", acting)
                        .statusCode() == 403,
                "the narrowed token still reads what it narrowed away");

        HttpResponse<String> trail = dbo.get(dbo.at(HOSPITAL)
                + "/fhir/AuditEvent?action=C&entity=Encounter/" + encounter, dbo.token(HOSPITAL));
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                trail.body().contains("\"value\":\"" + engineClient() + "\"")
                        && trail.body().contains("\"reference\":\"Practitioner/"
                        + practitioner + "\""),
                "the trail of what the process wrote does not name both the process and the "
                        + "clinician: " + trail.body());

        HttpResponse<String> beyond = doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&subject_token=" + humanToken
                + "&scope=" + encoded("user/Patient.write"));
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                beyond.statusCode() == 400 && beyond.body().contains("access_denied"),
                "a process was given a scope the clinician does not hold: "
                        + beyond.statusCode() + " " + beyond.body());

        // And the grant that carries this is one a client can find.
        HttpResponse<String> discovery = dbo.get(dbo.at(HOSPITAL)
                + "/oidc/.well-known/openid-configuration", null);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                dbo.says(discovery).at("$.\"grant_types_supported\"[*]").contains(TOKEN_EXCHANGE),
                "discovery does not name token exchange, so a client concludes acting in "
                        + "somebody's name is unreachable: " + discovery.body());
    }

    @Test
    @Order(14)
    @DisplayName("a standing delegation lets the process act with no token of the clinician's, "
            + "stays as narrow as it was granted when the clinician's role widens, and carries "
            + "the purpose of each request rather than one of its own")
    @Proving({DboPromises.AUTH_ON_BEHALF_OF, DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST})
    void aStandingDelegationOutlivesTheTokenAndNeverWidens() {
        delegation = delegate();
        String acting = tokenIn(doors.form("/oidc/token", "grant_type=" + encoded(TOKEN_EXCHANGE)
                + engine() + "&delegation_id=" + delegation));
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                claimsOf(acting).contains("\"act\":{\"sub\":\"" + engineClient() + "\"}"),
                "a token minted from the delegation does not name the process: "
                        + claimsOf(acting));
        assertEquals(201, new ATenantsDoor(dbo, HOSPITAL).postAs("/Encounter", """
                {"resourceType":"Encounter","status":"planned",
                 "class":[{"coding":[{"code":"AMB"}]}]}""", acting).statusCode());

        tenants.authority(HOSPITAL).orElseThrow().ensureRoleGrant(role,
                List.of("user/*.read", "user/Encounter.write", "user/Patient.write"));
        String widened = tokenIn(doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&delegation_id=" + delegation));
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                !claimsOf(widened).contains("user/Patient.write"),
                "the delegation widened with the clinician's role: " + claimsOf(widened));

        String live = tokenIn(doors.form("/oidc/token", "grant_type=" + encoded(TOKEN_EXCHANGE)
                + engine() + "&subject_token=" + humanToken + "&purpose_of_use=TREAT"));
        Proves.that(DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST,
                claimsOf(live).contains("\"purpose_of_use\":[\"TREAT\"]"),
                "the live exchange dropped the purpose the caller stated: " + claimsOf(live));
        String stated = tokenIn(doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&delegation_id=" + delegation
                + "&purpose_of_use=TREAT"));
        Proves.that(DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST,
                claimsOf(stated).contains("\"purpose_of_use\":[\"TREAT\"]"),
                "the delegated exchange dropped the purpose the caller stated: "
                        + claimsOf(stated));
        String silent = tokenIn(doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&delegation_id=" + delegation));
        Proves.that(DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST,
                !claimsOf(silent).contains("purpose_of_use"),
                "the delegation remembered the last caller's purpose, and would assert it "
                        + "after the occasion passed: " + claimsOf(silent));

        HttpResponse<String> forged = doors.form("/oidc/token", "grant_type=client_credentials"
                + engine() + "&purpose_of_use=" + encoded("TREAT\",\"scope\":\"system/*.write"));
        Proves.that(DboPromises.AUTH_PURPOSE_IS_STATED_PER_REQUEST,
                forged.statusCode() == 400 && forged.body().contains("invalid_request"),
                "a purpose that is not a code was minted into a token: "
                        + forged.statusCode() + " " + forged.body());
    }

    @Test
    @Order(15)
    @DisplayName("ending the delegation ends what the process may do, and so does the "
            + "clinician's role ending")
    @Proving(DboPromises.AUTH_ON_BEHALF_OF)
    void whatAProcessMayDoEndsWithWhatItWasGranted() {
        HttpResponse<String> ended = dbo.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(dbo.at(HOSPITAL) + "/oidc/delegation/" + delegation))
                .DELETE(), humanToken);
        assertEquals(200, ended.statusCode(), ended.body());
        HttpResponse<String> afterEnding = doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&delegation_id=" + delegation);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                afterEnding.statusCode() == 400 && afterEnding.body().contains("invalid_grant"),
                "an ended delegation still mints tokens: " + afterEnding.statusCode() + " "
                        + afterEnding.body());

        String fresh = delegate();
        HttpResponse<String> roleEnded = new ATenantsDoor(dbo, HOSPITAL).put(
                "/PractitionerRole/" + roleRecord, """
                {"resourceType":"PractitionerRole","id":"%s",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"%s","code":"%s"}]}],
                 "period":{"end":"%s"}}""".formatted(roleRecord, practitioner, names.system(), role,
                        LocalDate.now().minusDays(1)), "If-Match", "W/\"1\"");
        assertEquals(200, roleEnded.statusCode(), roleEnded.body());
        HttpResponse<String> denied = doors.form("/oidc/token", "grant_type="
                + encoded(TOKEN_EXCHANGE) + engine() + "&delegation_id=" + fresh);
        Proves.that(DboPromises.AUTH_ON_BEHALF_OF,
                denied.statusCode() == 400 && denied.body().contains("access_denied"),
                "a delegation outlived the role of the clinician who gave it: "
                        + denied.statusCode() + " " + denied.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String broker() {
        broker = names.code("broker");
        return doors.tokenFor(broker, "identity", "system/*.read", "system/*.write");
    }

    private HttpResponse<String> identify(String verb, String body) {
        return doors.post("/identity/" + verb, body, doors.tokenFor(names.code("registrar"),
                "identity"));
    }

    private String pseudonym(String subject, String scope) {
        HttpResponse<String> answered = doors.post("/identity/pseudonym",
                "{\"subject\":\"" + subject + "\",\"scope\":\"" + scope + "\"}", broker());
        assertEquals(200, answered.statusCode(), answered.body());
        return dbo.says(answered).one("pseudonym").orElseThrow(
                () -> new AssertionError("no pseudonym in " + answered.body()));
    }

    private HttpResponse<String> resolve(String pseudonym, String scope) {
        String bearer = broker();
        return doors.post("/identity/pseudonym/resolve", """
                {"pseudonym":"%s","scope":"%s","actor":"%s","purpose":"%s"}"""
                .formatted(pseudonym, scope, broker, WHY), bearer);
    }

    /** Writes one record carrying the value, then asks for it back by that value. */
    private void lookedUp(String type, String system, String value, List<String> silent) {
        String identifiers = system.equals(NATIONAL_NUMBER)
                ? "[{\"system\":\"%s\",\"value\":\"%s\"}]".formatted(system, value)
                : "[{\"system\":\"%s\",\"value\":\"%s\"},{\"system\":\"%s\",\"value\":\"%s\"}]"
                        .formatted(NATIONAL_NUMBER, value + "-nid", system, value);
        var written = dbo.write(HOSPITAL, type, """
                {"resourceType":"%s","identifier":%s,"name":[{"family":"Otsitav"}]}"""
                .formatted(type, identifiers));
        assertTrue(written.accepted(), type + " carrying " + value + " was not accepted: "
                + written.body());
        String id = written.idOrFail();
        HttpResponse<String> found = dbo.search(HOSPITAL, type,
                "identifier=" + system + "|" + value, "TREAT");
        // A refusal is an answer: it says the store holds this and cannot
        // match on it. Silence is the failure.
        if (found.statusCode() == 200 && !dbo.says(found).at("entry.resource.id").contains(id)) {
            silent.add(type + " carrying " + system + "|" + value + " (record " + id + ") -> "
                    + found.body().replaceAll("\\s+", " "));
        }
    }

    /**
     * A clinician of the hospital's who signs in as themselves: the capacity
     * they act in, the person who is them, a role the hospital grants, and a
     * login of their own.
     */
    private void theClinicianSignsIn() {
        TenantAuthority authority = tenants.authority(HOSPITAL).orElseThrow();
        role = names.code("doctor");
        var capacity = dbo.write(HOSPITAL, "Practitioner", """
                {"resourceType":"Practitioner",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Volitaja"}]}"""
                .formatted(NATIONAL_NUMBER, names.value("volitaja")));
        assertTrue(capacity.accepted(), capacity.body());
        practitioner = capacity.idOrFail();
        var who = dbo.write(HOSPITAL, "Person", """
                {"resourceType":"Person",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Volitaja"}],
                 "link":[{"target":{"reference":"Practitioner/%s"},"assurance":"level3"}]}"""
                .formatted(NATIONAL_NUMBER, names.value("volitaja"), practitioner));
        assertTrue(who.accepted(), who.body());
        clinicianPerson = who.idOrFail();
        var grantedRole = dbo.write(HOSPITAL, "PractitionerRole", """
                {"resourceType":"PractitionerRole",
                 "practitioner":{"reference":"Practitioner/%s"},
                 "code":[{"coding":[{"system":"%s","code":"%s"}]}]}"""
                .formatted(practitioner, names.system(), role));
        assertTrue(grantedRole.accepted(), grantedRole.body());
        roleRecord = grantedRole.idOrFail();

        authority.ensureRoleGrant(role, List.of("user/*.read", "user/Encounter.write"));
        authority.ensureLocalCredential(login(), "salakala8", clinicianPerson);
        authority.ensureClient(webapp(), null, List.of("user/*.read", "user/*.write"),
                "public-pkce", List.of(REDIRECT));
        authority.ensureClient(engineClient(), engineClient() + "-secret", List.of());

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        HttpResponse<String> login = doors.form("/oidc/authorize/login",
                "client_id=" + encoded(webapp()) + "&redirect_uri=" + encoded(REDIRECT)
                        + "&code_challenge=" + challenge + "&login=" + encoded(login())
                        + "&password=salakala8");
        String location = login.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("the clinician's sign-in sent them nowhere: "
                        + login.statusCode() + " " + login.body()));
        String code = location.replaceAll("(?s).*[?&]code=([^&]+).*", "$1");
        humanToken = tokenIn(doors.form("/oidc/token", "grant_type=authorization_code"
                + "&client_id=" + encoded(webapp()) + "&code=" + code
                + "&redirect_uri=" + encoded(REDIRECT) + "&code_verifier=" + verifier));
    }

    private String delegate() {
        HttpResponse<String> created = doors.form("/oidc/delegation",
                "client_id=" + encoded(engineClient()) + "&process_ref=" + encoded(names.code("workflow"))
                        + "&scope=" + encoded("user/Encounter.write")
                        + "&valid_until=" + (System.currentTimeMillis() / 1000 + 3600),
                humanToken);
        assertEquals(201, created.statusCode(), created.body());
        return dbo.says(created).one("delegation_id").orElseThrow(
                () -> new AssertionError("no delegation in " + created.body()));
    }

    private String login() {
        return names.code("volitaja");
    }

    private String webapp() {
        return names.code("webapp");
    }

    private String engineClient() {
        return names.code("engine");
    }

    /** The process's own client credentials, as form fields. */
    private String engine() {
        return "&client_id=" + encoded(engineClient()) + "&client_secret="
                + encoded(engineClient() + "-secret");
    }

    private String tokenIn(HttpResponse<String> issued) {
        assertEquals(200, issued.statusCode(), issued.body());
        return dbo.says(issued).one("access_token").orElseThrow(
                () -> new AssertionError("no token in " + issued.body()));
    }

    private static String claimsOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
    }

    /** The id of the one record of a type a finished run says the hospital wrote. */
    private static String idOf(cloud.jengu.dbo.spring.worker.DboInitiator.Answer answer,
            String type) {
        return cloud.jengu.dbo.samples.worker.HearingBack.produced(answer).stream()
                .filter(written -> written.startsWith(type + "/"))
                .map(written -> written.split("/")[1]).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " was written: "
                        + answer.body()));
    }

    private static String idIn(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        String location = created.headers().firstValue("Location").orElseThrow();
        String path = location.contains("/_history/")
                ? location.substring(0, location.indexOf("/_history/")) : location;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
