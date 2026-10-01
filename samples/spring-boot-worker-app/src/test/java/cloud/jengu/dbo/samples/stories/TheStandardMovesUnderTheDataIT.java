package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.AfterAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-STANDARD-MOVES, walked on the sample world.
 *
 * <p>A clinic carries a profile pack of its own, and the pack moves while the
 * data stays. What an observation was validated under is recorded as a fact
 * of the accept, the claim it makes stays an unversioned canonical, and the
 * stock is findable by how far behind the current pack it is.
 *
 * <p><b>A tenant of its own, and why.</b> Every member of the sample world
 * takes its profiles by replication, from the face root or the zone; none
 * authors one and holds the data written under it. A type declares one
 * handling per tenant, so that is a different tenant rather than a setting,
 * and this story declares it: small, named for the story and this run, and
 * retracted when the story ends.
 */
@AUserStory
class TheStandardMovesUnderTheDataIT {

    private final StoryNames names = StoryNames.of(DboStories.STANDARD_MOVES);

    @Autowired
    DboTestContext dbo;

    private String clinicCode;
    private ATenantsDoor clinic;
    private String canonical;
    private String observation;

    @BeforeAll
    void aClinicThatCarriesItsOwnPack() {
        clinicCode = names.tenant("clinic");
        canonical = names.canonical("StructureDefinition/observed-on-somebody");
        dbo.declare(clinicCode, """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(clinicCode));
        assertTrue(dbo.until(clinicCode, true, Duration.ofMinutes(10)),
                "the story's clinic never came up: " + dbo.serving());
        clinic = new ATenantsDoor(dbo, clinicCode);

        // The pack arrives as ordinary content: a profile is data, not
        // configuration, so a clinic can carry its own without a release.
        HttpResponse<String> pack = clinic.post("/StructureDefinition", profile("2.0.0"));
        assertEquals(201, pack.statusCode(), pack.body());
    }

    @AfterAll
    void theClinicIsWithdrawn() {
        if (clinicCode != null) {
            dbo.retract(clinicCode);
        }
    }

    // ── what an object was validated under is a fact about the accept ──

    @Test
    @Order(1)
    @DisplayName("an accepted observation records the pack version it was validated under, "
            + "while the conformance claim it makes stays an unversioned canonical")
    @Proving({DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
            DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM})
    void whatItWasValidatedUnderIsRecorded() {
        HttpResponse<String> created = clinic.post("/Observation", claiming());
        assertEquals(201, created.statusCode(), created.body());
        observation = dbo.says(created).one("id").orElseThrow();

        String served = clinic.get("/Observation/" + observation).body();
        Proves.that(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED,
                served.contains("\"valueString\":\"2.0.0\""),
                "the version it was validated under is not served beside the claim: " + served);
        Proves.that(DboPromises.SHAPE_SERVED_BESIDE_THE_CLAIM,
                dbo.says(clinic.get("/Observation/" + observation)).at("meta.profile")
                        .equals(List.of(canonical)),
                "the claim did not stay unversioned, and conversion moves an object's shape, "
                        + "never its identity: " + served);
    }

    @Test
    @Order(2)
    @DisplayName("echoing the served document back does not accumulate a second stamp, "
            + "because the stamp is derived on accept rather than carried by the caller")
    @Proving(DboPromises.SHAPE_STAMP_IS_DERIVED)
    void theStampIsReplacedNeverAccumulated() {
        String served = clinic.get("/Observation/" + observation).body();
        assertEquals(200, clinic.put("/Observation/" + observation, served).statusCode());

        String again = clinic.get("/Observation/" + observation).body();
        Proves.that(DboPromises.SHAPE_STAMP_IS_DERIVED, count(again, "urn:dbo:shape") == 1,
                "one stamp per profile however many round trips, or a client that echoes "
                        + "what it was given slowly grows the record: " + again);
    }

    @Test
    @Order(3)
    @DisplayName("the pack moves and the stamp moves with the next accept, while the version "
            + "written before it keeps its own stamp in history")
    @Proving({DboPromises.SHAPE_WRITTEN_UNDER_STAMPED, DboPromises.SHAPE_STAMP_IS_DERIVED})
    void thePackMovesAndSoDoesTheStamp() {
        assertTrue(clinic.put("/StructureDefinition?url=" + enc(canonical), profile("3.0.0"))
                        .statusCode() < 300,
                "the pack does not advance as ordinary content");

        assertEquals(200, clinic.put("/Observation/" + observation,
                clinic.get("/Observation/" + observation).body()).statusCode());
        Proves.that(DboPromises.SHAPE_STAMP_IS_DERIVED,
                clinic.get("/Observation/" + observation).body()
                        .contains("\"valueString\":\"3.0.0\""),
                "re-accepting did not move the stamp to what it was validated under this time");

        String history = clinic.get("/Observation/" + observation + "/_history").body();
        Proves.that(DboPromises.SHAPE_WRITTEN_UNDER_STAMPED, history.contains("2.0.0"),
                "the version written under the old pack no longer says so, and what the "
                        + "record claimed last year is a fact about last year: " + history);
    }

    // ── so the stock can be counted, found and moved ──

    @Test
    @Order(4)
    @DisplayName("stock is findable by version bound, so 'what do I still have below the "
            + "current major' is a query rather than a scan somebody writes")
    @Proving(DboPromises.SHAPE_QUERYABLE_BY_VERSION)
    void stockIsFindableByBound() {
        // The observation stands at 3.0.0 by now, so the bound that finds it
        // is the one above it, and the one at it does not.
        HttpResponse<String> below4 = clinic.get("/Observation?_shape-below="
                + enc(canonical + "|4"));
        assertEquals(200, below4.statusCode(), below4.body());
        Proves.that(DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                dbo.says(below4).at("entry.resource.id").contains(observation),
                "stock under the bound was not findable by it, which is the scan this "
                        + "parameter exists to replace: " + below4.body());

        HttpResponse<String> below3 = clinic.get("/Observation?_shape-below="
                + enc(canonical + "|3"));
        assertEquals(200, below3.statusCode(), below3.body());
        Proves.that(DboPromises.SHAPE_QUERYABLE_BY_VERSION,
                !dbo.says(below3).at("entry.resource.id").contains(observation),
                "stock stamped AT the bound came back as below it: " + below3.body());
    }

    @Test
    @Order(5)
    @DisplayName("a shape whose version has no leading integer is refused when the shape "
            + "arrives, rather than stamping stock no bound could ever match")
    @Proving(DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED)
    void anUnparseableVersionIsRefusedAtTheDoor() {
        HttpResponse<String> refused = clinic.post("/StructureDefinition", """
                {"resourceType":"StructureDefinition",
                 "url":"%s/unparseable","version":"spring-release",
                 "name":"Unparseable","status":"active","kind":"resource",
                 "abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint"}""".formatted(canonical));

        Proves.that(DboPromises.SHAPE_UNPARSEABLE_VERSION_REFUSED, refused.statusCode() != 201,
                "a version no bound can order was accepted, so the stock it stamps is "
                        + "discovered mid-migration rather than at the door: " + refused.body());
    }

    @Test
    @Order(6)
    @DisplayName("the clinic's own definitions travel with its face rather than being "
            + "fetched from wherever they were published")
    @Proving({DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE,
            DboPromises.VER_CONCURRENT_VERSIONS})
    void definitionsTravelWithTheFace() {
        // Nothing here reaches the network: the profile was written into this
        // tenant and validation resolved it from the tenant's own content.
        HttpResponse<String> stillThere = clinic.get("/StructureDefinition?url="
                + enc(canonical));
        assertEquals(200, stillThere.statusCode(), stillThere.body());
        Proves.that(DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE,
                stillThere.body().contains("3.0.0"),
                "the definition the clinic validates against is not its own record of it: "
                        + stillThere.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String claiming() {
        return """
                {"resourceType":"Observation","status":"final",
                 "meta":{"profile":["%s"]},
                 "code":{"text":"Body temperature"},
                 "subject":{"reference":"Patient/anybody"},
                 "valueQuantity":{"value":37.1}}""".formatted(canonical);
    }

    private String profile(String version) {
        return """
                {"resourceType":"StructureDefinition",
                 "url":"%s","version":"%s",
                 "name":"ObservedOnSomebody","status":"active","kind":"resource",
                 "abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
                .formatted(canonical, version);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int seen = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            seen++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return seen;
    }
}
