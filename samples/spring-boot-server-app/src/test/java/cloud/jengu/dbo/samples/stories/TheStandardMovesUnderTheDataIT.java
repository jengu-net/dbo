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
 * US-DBO-STANDARD-MOVES, walked in Rowling Land, the sample world.
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

    @Autowired
    cloud.jengu.dbo.spring.server.DboTenants tenants;

    @Autowired
    org.springframework.core.env.Environment environment;

    private String clinicCode;
    private ATenantsDoor clinic;
    private String canonical;
    private String observation;

    @BeforeAll
    void aClinicThatCarriesItsOwnPack() {
        clinicCode = names.tenant("clinic");
        canonical = names.canonical("StructureDefinition/observed-on-somebody");
        // --8<-- [start:pack-tenant]
        dbo.declare(clinicCode, """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"StructureMap","identity":"canonical","handling":"operational"},
                  {"name":"Basic","identity":"internal","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(clinicCode));
        // --8<-- [end:pack-tenant]
        assertTrue(dbo.until(clinicCode, true, Duration.ofMinutes(10)),
                "the story's clinic never came up: " + dbo.serving());
        clinic = new ATenantsDoor(dbo, clinicCode);

        // --8<-- [start:pack]
        // The pack arrives as ordinary content: a profile is data, not
        // configuration, so a clinic can carry its own without a release.
        HttpResponse<String> pack = clinic.post("/StructureDefinition", profile("2.0.0"));
        assertEquals(201, pack.statusCode(), pack.body());
        // --8<-- [end:pack]
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

    // ── and the stock moves to the new shape, in place ──

    @Test
    @Order(7)
    @DisplayName("stock stamped below the target is converted in place by the clinic's own map; "
            + "the new version carries the new stamp and history keeps the old one")
    @Proving(DboPromises.SHAPE_RESHAPED_IN_PLACE)
    void stockIsConvertedInPlace() {
        String note = noteShape();
        assertEquals(201, clinic.post("/StructureDefinition", basicShape(note, "2.0.0"))
                .statusCode());
        HttpResponse<String> created = clinic.post("/Basic", basicNote(note));
        assertEquals(201, created.statusCode(), created.body());
        oldStock = dbo.says(created).one("id").orElseThrow();
        assertTrue(clinic.get("/Basic/" + oldStock).body().contains("\"valueString\":\"2.0.0\""),
                "the stock does not start stamped 2.0.0");

        assertTrue(clinic.put("/StructureDefinition?url=" + enc(note), basicShape(note, "3.0.0"))
                .statusCode() < 300, "the shape did not move to 3.0.0");
        HttpResponse<String> map = clinic.post("/StructureMap", noteMap(note));
        assertEquals(201, map.statusCode(), map.body());

        String run = admin("/reshape?type=Basic&profile=" + enc(note) + "&target=3");
        Proves.that(DboPromises.SHAPE_RESHAPED_IN_PLACE,
                run.contains("\"converted\":1")
                        && clinic.get("/Basic/" + oldStock).body()
                                .contains("\"valueString\":\"3.0.0\"")
                        && clinic.get("/Basic/" + oldStock + "/_history").body()
                                .contains("\"valueString\":\"2.0.0\""),
                "the stock was not converted in place with its old version kept in history: "
                        + run);
    }

    @Test
    @Order(8)
    @DisplayName("a re-run finds only what is still behind, so converted stock is not "
            + "converted twice")
    @Proving(DboPromises.SHAPE_RESHAPE_RESUMABLE)
    void aReRunConvertsNothing() {
        String run = admin("/reshape?type=Basic&profile=" + enc(noteShape()) + "&target=3");
        Proves.that(DboPromises.SHAPE_RESHAPE_RESUMABLE,
                run.contains("\"converted\":0") && run.contains("\"complete\":true"),
                "a re-run converted something again, or did not say it was complete: " + run);
    }

    @Test
    @Order(9)
    @DisplayName("an object no map covers is named and left behind, with its reason, and the "
            + "run does not claim to be complete")
    @Proving(DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND)
    void whatNoMapCoversIsNamedAndLeftBehind() {
        String orphanShape = orphanShape();
        assertEquals(201, clinic.post("/StructureDefinition", basicShape(orphanShape, "2.0.0"))
                .statusCode());
        HttpResponse<String> created = clinic.post("/Basic", basicNote(orphanShape));
        String orphan = dbo.says(created).one("id").orElseThrow();

        String run = admin("/reshape?type=Basic&profile=" + enc(orphanShape) + "&target=3");
        Proves.that(DboPromises.SHAPE_REFUSED_OBJECT_LEFT_BEHIND,
                run.contains("\"converted\":0") && run.contains(orphan)
                        && run.contains("no converter covers")
                        && run.contains("\"complete\":false")
                        && clinic.get("/Basic/" + orphan).body()
                                .contains("\"valueString\":\"2.0.0\""),
                "an object no map covers was not named and left untouched, or the run claimed "
                        + "to be complete: " + run);
    }

    @Test
    @Order(10)
    @DisplayName("a stamp outlives the pack version that made it: re-numbering the shape leaves "
            + "the stock findable and counted under what stamped it")
    @Proving(DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK)
    void aStampOutlivesItsPack() {
        String orphanShape = orphanShape();
        String stranded = dbo.says(clinic.post("/Basic", basicNote(orphanShape))).one("id")
                .orElseThrow();
        assertTrue(clinic.put("/StructureDefinition?url=" + enc(orphanShape),
                basicShape(orphanShape, "9.0.0")).statusCode() < 300);

        Proves.that(DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK,
                dbo.says(clinic.get("/Basic?_shape-below=" + enc(orphanShape + "|9")))
                        .at("entry.resource.id").contains(stranded),
                "stock stamped under the withdrawn version is not findable by it");
        String inventory = admin("/inventory");
        Proves.that(DboPromises.SHAPE_STAMP_OUTLIVES_ITS_PACK,
                inventory.contains(orphanShape) && inventory.contains("2.0.0"),
                "the stock is not counted under the version that stamped it: " + inventory);
    }

    @Test
    @Order(11)
    @DisplayName("a claim for a converter outside the store writes nothing and holds nothing: "
            + "abandoning it strands no data, and the same stock comes back")
    @Proving(DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING)
    void aClaimHoldsNothing() {
        String stranded = dbo.says(clinic.post("/Basic", basicNote(orphanShape()))).one("id")
                .orElseThrow();
        String claim = "/reshape/claim?type=Basic&profile=" + enc(orphanShape()) + "&target=10";
        Proves.that(DboPromises.SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING,
                admin(claim).contains(stranded) && admin(claim).contains(stranded),
                "an abandoned claim stranded the stock, so it did not come back");
    }

    @Test
    @Order(12)
    @DisplayName("a converted form handed back is validated, re-stamped and version-checked; a "
            + "stale one is refused and its object left untouched")
    @Proving(DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE)
    void aHandBackKeepsTheDiscipline() {
        String id = dbo.says(clinic.post("/Basic", basicNote(orphanShape()))).one("id")
                .orElseThrow();
        String claim = "/reshape/claim?type=Basic&profile=" + enc(orphanShape()) + "&target=10";
        long version = versionOf(admin(claim), id);

        assertTrue(clinic.put("/Basic/" + id, basicNote(orphanShape())).statusCode() < 300);
        String stale = applyBack(id, version, basicNote(orphanShape()));
        long current = versionOf(admin(claim), id);
        String applied = applyBack(id, current, basicNote(orphanShape()));
        Proves.that(DboPromises.SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE,
                stale.contains("\"converted\":0") && stale.contains(id)
                        && applied.contains("\"converted\":1")
                        && clinic.get("/Basic/" + id).body()
                                .contains("\"valueString\":\"9.0.0\""),
                "a stale hand-back was taken, or a current one was not re-stamped by the pack: "
                        + stale + " / " + applied);
    }

    // ── and a pack that moves backwards does not hide what it no longer covers ──

    @Test
    @Order(13)
    @DisplayName("when the pack rolls back below an object's stamp, the object is refused by "
            + "id with its own answer naming the stamp and what the pack now declares")
    @Proving({DboPromises.SHAPE_NEWER_DATA_REFUSED, DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER})
    void dataNewerThanThePackIsRefusedById() {
        String reading = names.canonical("StructureDefinition/reading");
        assertEquals(201, clinic.post("/StructureDefinition", basicShape(reading, "3.0.0"))
                .statusCode());
        tooNew = dbo.says(clinic.post("/Basic", basicNote(reading))).one("id").orElseThrow();
        assertTrue(clinic.get("/Basic/" + tooNew).body().contains("\"valueString\":\"3.0.0\""),
                "the object does not start stamped at what the pack then declared");

        assertTrue(clinic.put("/StructureDefinition?url=" + enc(reading),
                basicShape(reading, "2.0.0")).statusCode() < 300);
        HttpResponse<String> refused = clinic.get("/Basic/" + tooNew);
        Proves.that(DboPromises.SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER,
                refused.statusCode() == 409 && refused.body().contains(tooNew)
                        && refused.body().contains("3.0.0") && refused.body().contains("2.0.0")
                        && refused.body().contains("conflict"),
                "the object newer than the pack was not refused as its own answer, naming the "
                        + "stamp and the pack: " + refused.statusCode() + " " + refused.body());
    }

    @Test
    @Order(14)
    @DisplayName("a search whose answer would contain it is refused whole, never quietly short")
    @Proving(DboPromises.SHAPE_NEWER_DATA_REFUSED)
    void aSearchThatWouldHoldItIsRefusedWhole() {
        HttpResponse<String> search = clinic.get("/Basic");
        Proves.that(DboPromises.SHAPE_NEWER_DATA_REFUSED,
                search.statusCode() == 409 && search.body().contains(tooNew),
                "a search answered short, which looks like an answer: " + search.statusCode()
                        + " " + search.body());
    }

    @Test
    @Order(15)
    @DisplayName("what is not demonstrably ahead still reads: unstamped stock, and stock under a "
            + "shape the pack no longer carries at all")
    @Proving(DboPromises.SHAPE_NEWER_DATA_REFUSED)
    void onlyWhatIsDemonstrablyAheadIsRefused() {
        String unstamped = dbo.says(clinic.post("/Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"plain\"}}"))
                .one("id").orElseThrow();
        String gone = names.canonical("StructureDefinition/gone");
        String goneShape = dbo.says(clinic.post("/StructureDefinition", basicShape(gone, "1.0.0")))
                .one("id").orElseThrow();
        String underGone = dbo.says(clinic.post("/Basic", basicNote(gone))).one("id")
                .orElseThrow();
        assertTrue(clinic.delete("/StructureDefinition/" + goneShape).statusCode() < 400,
                "the pack did not drop the shape");

        Proves.that(DboPromises.SHAPE_NEWER_DATA_REFUSED,
                clinic.get("/Basic/" + unstamped).statusCode() == 200
                        && clinic.get("/Basic/" + underGone).statusCode() == 200,
                "unstamped stock, or stock under a shape the pack no longer carries, was "
                        + "refused as though it were ahead");
    }

    @Test
    @Order(39)
    @DisplayName("each version the world serves was cut once into an image by its first "
            + "tenant, so every tenant after it loaded the version rather than expanding it")
    @Proving(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE)
    void eachVersionWasCutOnce() {
        // Where this deployment was told to keep them, as the sample world's
        // compose file tells its node. The directory starts empty with the
        // world, so what is in it is what this deployment cut.
        java.nio.file.Path kept = java.nio.file.Path.of(System.getProperty("dbo.face.images"));
        for (String face : List.of("r4", "r5")) {
            Proves.that(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE,
                    java.nio.file.Files.isReadable(kept.resolve(face + ".faceimage")),
                    "the world serves " + face + " and this deployment kept no image of it, so "
                            + "every tenant on it expanded the whole version again");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private String oldStock;
    private String tooNew;

    private String noteShape() {
        return names.canonical("StructureDefinition/note");
    }

    private String orphanShape() {
        return names.canonical("StructureDefinition/orphan");
    }

    private static String basicShape(String url, String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"Shape%s","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(url, version, Math.abs(url.hashCode()));
    }

    private static String basicNote(String profile) {
        return """
                {"resourceType":"Basic","code":{"text":"note"},
                 "meta":{"profile":["%s"]}}""".formatted(profile);
    }

    /** The hop from 2 to 3, spelled the way FHIR spells a versioned reference. */
    private String noteMap(String shape) {
        return """
                {"resourceType":"StructureMap",
                 "url":"%s","version":"1.0.0",
                 "name":"NoteTwoToThree","status":"active",
                 "structure":[{"url":"%s|2.0.0","mode":"source"},
                              {"url":"%s|3.0.0","mode":"target"}],
                 "group":[{"name":"main","typeMode":"types",
                   "input":[{"name":"src","type":"Basic","mode":"source"},
                            {"name":"tgt","type":"Basic","mode":"target"}],
                   "rule":[
                     {"name":"code","source":[{"context":"src","element":"code","variable":"c"}],
                      "target":[{"context":"tgt","contextType":"variable","element":"code",
                                 "transform":"copy","parameter":[{"valueId":"c"}]}]},
                     {"name":"meta","source":[{"context":"src","element":"meta","variable":"m"}],
                      "target":[{"context":"tgt","contextType":"variable","element":"meta",
                                 "transform":"copy","parameter":[{"valueId":"m"}]}]}]}]}"""
                .formatted(names.canonical("StructureMap/note-2-to-3"), shape, shape);
    }

    // ── the stock can be counted, and the stamp is the store's own ──

    @Test
    @Order(16)
    @DisplayName("an operator counts the stock by profile and version, a profile with no "
            + "version counted as that, and a profile can be searched by")
    @Proving(DboPromises.SHAPE_STOCK_COUNTED)
    void theStockIsCounted() {
        String counted = names.canonical("StructureDefinition/counted");
        String versionless = names.canonical("StructureDefinition/versionless");
        assertEquals(201, clinic.post("/StructureDefinition",
                profileAt(counted, "\"version\":\"1.0.0\",", "Counted")).statusCode());
        assertEquals(201, clinic.post("/StructureDefinition",
                profileAt(versionless, "", "Versionless")).statusCode());
        HttpResponse<String> one = clinic.post("/Observation", claimingOf(counted));
        assertEquals(201, one.statusCode(), one.body());
        countedObservation = dbo.says(one).one("id").orElseThrow();
        assertEquals(201, clinic.post("/Observation", claimingOf(versionless)).statusCode());

        String inventory = admin("/inventory");
        Proves.that(DboPromises.SHAPE_STOCK_COUNTED,
                inventory.contains("\"profile\":\"" + counted + "\",\"version\":\"1.0.0\","
                        + "\"count\":1")
                        && inventory.contains("\"profile\":\"" + versionless
                        + "\",\"version\":null,\"count\":1"),
                "the stock is not counted by profile and version: " + inventory);
        Proves.that(DboPromises.SHAPE_STOCK_COUNTED,
                clinic.get("/Observation?_profile=" + encoded(versionless)).body()
                        .contains(versionless),
                "the stock of a profile cannot be searched by it");
    }

    @Test
    @Order(17)
    @DisplayName("rebuilding the envelopes keeps the stamp, because it is derived from what "
            + "the object was accepted under, and the stamp rides the change feed")
    @Proving({DboPromises.SHAPE_STAMP_IS_DERIVED, DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP})
    void theStampSurvivesARebuildAndRidesTheWire() {
        int rebuilt = tenants.store(clinicCode).orElseThrow().rebuildEnvelopes("Observation");
        Proves.that(DboPromises.SHAPE_STAMP_IS_DERIVED,
                rebuilt >= 1 && clinic.get("/Observation/" + countedObservation).body()
                        .contains("\"valueString\":\"1.0.0\""),
                "rebuilding the envelopes lost the stamp: " + rebuilt);

        var feed = tenants.changes(clinicCode).orElseThrow();
        List<String> stamped = null;
        String cursor = null;
        for (var chunk = feed.read(null, 200); !chunk.items().isEmpty();
                chunk = feed.read(cursor, 200)) {
            for (var item : chunk.items()) {
                if (countedObservation.equals(item.objectId()) && item.shape() != null
                        && !item.shape().isEmpty()) {
                    stamped = item.shape();
                }
            }
            cursor = chunk.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        Proves.that(DboPromises.SHAPE_MIRRORED_KEEPS_ITS_STAMP,
                stamped != null && stamped.stream().anyMatch(stamp -> stamp.endsWith("|1.0.0")),
                "the change feed does not carry the stamp, so a mirror re-derives it: "
                        + stamped);
    }

    // ── and a conversion is aimed the way a search is ──

    @Test
    @Order(18)
    @DisplayName("one expression counts what a conversion would take and converts exactly that, "
            + "and what was not aimed at is left alone until it is")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void aConversionTakesTheSearchNarrowing() {
        assertEquals(201, clinic.post("/StructureDefinition", order("2.0.0")).statusCode());
        String waitedOn = idOf(clinic.post("/Basic", anOrder("active")));
        String alsoWaitedOn = idOf(clinic.post("/Basic", anOrder("draft")));
        String coldHistory = idOf(clinic.post("/Basic", anOrder("completed")));
        assertTrue(clinic.put("/StructureDefinition?url=" + encoded(orders()), order("3.0.0"))
                .statusCode() < 300);
        assertEquals(201, clinic.post("/StructureMap", ordersMap()).statusCode());

        String aim = "code=" + encoded(states() + "|active");
        String counted = clinic.get("/Basic?_shape-below=" + encoded(orders() + "|3") + "&" + aim
                + "&_summary=count").body();
        String converted = admin("/reshape?type=Basic&profile=" + encoded(orders())
                + "&target=3&" + aim);
        String after = clinic.get("/Basic/" + waitedOn).body();
        Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                counted.contains("\"total\":1") && converted.contains("\"converted\":1")
                        && after.contains("3.0.0"),
                "the count and the conversion were not the same expression: counted " + counted
                        + ", converted " + converted + ", and the order reads " + after);
        Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                clinic.get("/Basic/" + coldHistory).body().contains("2.0.0")
                        && clinic.get("/Basic/" + alsoWaitedOn).body().contains("2.0.0"),
                "a conversion took what it was not aimed at");
        Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                admin("/reshape?type=Basic&profile=" + encoded(orders()) + "&target=3")
                        .contains("\"converted\":2")
                        && clinic.get("/Basic/" + coldHistory).body().contains("3.0.0"),
                "what was left behind did not converge when the door opened");
    }

    @Test
    @Order(19)
    @DisplayName("a parameter a conversion cannot take is refused by name, and so is one that "
            + "shapes a result rather than narrowing what is converted")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void whatCannotAimIsRefused() {
        String base = "/reshape?type=Basic&profile=" + encoded(orders()) + "&target=3";
        HttpResponse<String> unknown = adminResponse(base + "&nosuchparameter=x");
        Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                unknown.statusCode() == 400 && unknown.body().contains("nosuchparameter"),
                "an unsupported parameter was not refused by name: " + unknown.body());
        for (String shaping : List.of("_count=5", "_sort=code", "_summary=count",
                "_elements=code", "_shape-below=" + encoded(orders() + "|2"))) {
            Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                    adminResponse(base + "&" + shaping).statusCode() == 400,
                    "a result-shaping parameter aimed a conversion: " + shaping);
        }
    }

    @Test
    @Order(20)
    @DisplayName("the claim lane is aimed the same way")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void theClaimIsAimedTheSameWay() {
        String held = idOf(clinic.post("/Basic", anOrder("active")));
        String excluded = idOf(clinic.post("/Basic", anOrder("completed")));
        String base = "/reshape/claim?type=Basic&profile=" + encoded(orders()) + "&target=9";
        String everything = admin(base);
        String aimed = admin(base + "&code=" + encoded(states() + "|active"));
        Proves.that(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING,
                everything.contains(held) && everything.contains(excluded)
                        && aimed.contains(held) && !aimed.contains(excluded),
                "the claim was not narrowed the way the conversion is: " + aimed);
    }

    // ── a type says who decides a write of it ──

    @Test
    @Order(21)
    @DisplayName("a type that names the database as its judge is refused by it, by element and "
            + "bound, a clean write is unaffected, and a type that said nothing is unchanged")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void aTypeSaysWhoDecidesAWriteOfIt() {
        String judged = names.tenant("judged");
        dbo.declare(judged, """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"fhir-r4","face":true,
                   "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational",
                   "verdict":"database"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(judged));
        try {
            assertTrue(dbo.until(judged, true, Duration.ofMinutes(10)), "no judged clinic");
            ATenantsDoor door = new ATenantsDoor(dbo, judged);
            HttpResponse<String> twice = door.post("/Patient", """
                    {"resourceType":"Patient","name":[{"family":"Kaks"}],
                     "gender":["male","female"]}""");
            Proves.that(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT,
                    twice.statusCode() == 422 && twice.body().contains("Patient.gender")
                            && twice.body().contains("0..1"),
                    "the database did not decide, or did not say why: " + twice.body());
            Proves.that(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT,
                    door.post("/Patient", """
                            {"resourceType":"Patient","name":[{"family":"Tamm"}],
                             "gender":"female","birthDate":"1980-04-01"}""").statusCode() == 201,
                    "a clean write was refused");
            Proves.that(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT,
                    door.post("/Observation", """
                            {"resourceType":"Observation","status":"final",
                             "code":{"text":"kaks korda"},
                             "issued":["2020-01-01T00:00:00Z","2021-01-01T00:00:00Z"]}""")
                            .statusCode() == 201,
                    "a type that named no judge was judged by the database");
        } finally {
            dbo.retract(judged);
        }
    }

    // ── a profile is answered however it arrived ──

    @Test
    @Order(22)
    @DisplayName("a profile written behind the door is enforced once the store notices it, "
            + "without a restart")
    @Proving(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED)
    void aProfileWrittenBehindTheDoorIsEnforced() throws InterruptedException {
        String quiet = names.canonical("StructureDefinition/arrived-quietly");
        tenants.store(clinicCode).orElseThrow().put(cloud.jengu.dbo.core.api.PutRequest.create(
                        "StructureDefinition", profileAt(quiet, "\"version\":\"1.0.0\",",
                                "ArrivedQuietly").getBytes(StandardCharsets.UTF_8)),
                cloud.jengu.dbo.core.api.Handling.Authority.SOURCE_TENANT);
        String without = """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "meta":{"profile":["%s"]}}""".formatted(quiet);
        Proves.that(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED,
                untilStatus(() -> clinic.post("/Observation", without), 422)
                        && clinic.post("/Observation", claimingOf(quiet)).statusCode() == 201,
                "a profile that arrived behind the door was never enforced");
    }

    @Test
    @Order(23)
    @DisplayName("a profile that arrived by replication is validated against at the tenant "
            + "that took it")
    @Proving(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED)
    void aReplicatedProfileIsValidatedAgainst() throws InterruptedException {
        String zone = names.tenant("shape-zone");
        String reader = names.tenant("shape-reader");
        String observation = """
                [{"name":"Observation","identity":"internal","handling":"operational"}]""";
        dbo.declare(zone, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(zone));
        try {
            assertTrue(dbo.until(zone, true, Duration.ofMinutes(10)), "no zone");
            dbo.declare(reader, """
                    {"code":"%s","face":"r4","audit":{"level":"none"},
                     "dependencies":[{"name":"%s","types":["StructureDefinition"]}],
                     "types":[
                      {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(reader, zone));
            assertTrue(dbo.until(reader, true, Duration.ofMinutes(10)), "no reader");
            String replicated = names.canonical("StructureDefinition/replicated");
            assertEquals(201, new ATenantsDoor(dbo, zone).post("/StructureDefinition",
                    profileAt(replicated, "\"version\":\"1.0.0\",", "Replicated"))
                    .statusCode());
            ATenantsDoor door = new ATenantsDoor(dbo, reader);
            assertTrue(untilStatus(() -> door.get("/StructureDefinition?url="
                            + encoded(replicated) + "&_summary=count"), 200, "\"total\":1"),
                    "the profile never arrived at the reader");
            String without = """
                    {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                     "meta":{"profile":["%s"]}}""".formatted(replicated);
            Proves.that(DboPromises.SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED,
                    untilStatus(() -> door.post("/Observation", without), 422)
                            && door.post("/Observation", claimingOf(replicated)).statusCode()
                            == 201,
                    "a replicated profile was not validated against at the tenant holding it");
        } finally {
            dbo.retract(reader);
            dbo.retract(zone);
        }
    }

    // ── and a version is records, held once by its root ──

    @Test
    @Order(24)
    @DisplayName("the root holds its version once and findably: one Patient structure, and the "
            + "version's search parameters in their thousand")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void theRootHoldsTheVersionOnce() {
        HttpResponse<String> patient = dbo.get(dbo.at("fhir-r4")
                + "/fhir/StructureDefinition?url="
                + encoded("http://hl7.org/fhir/StructureDefinition/Patient"),
                dbo.token("fhir-r4"));
        String count = dbo.get(dbo.at("fhir-r4") + "/fhir/SearchParameter?_summary=count",
                dbo.token("fhir-r4")).body();
        java.util.regex.Matcher total = java.util.regex.Pattern.compile("\"total\":(\\d+)")
                .matcher(count);
        Proves.that(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS,
                patient.statusCode() == 200 && occurrences(patient.body(), "\"fullUrl\"") == 1
                        && patient.body().contains("\"type\":\"Patient\"")
                        && total.find() && Long.parseLong(total.group(1)) > 1000,
                "the root does not hold its version once and findably: " + count);
    }

    @Test
    @Order(25)
    @DisplayName("a tenant declared a root later loads the version where it stands")
    @Proving(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS)
    void aTenantBecomingARootLoadsWhereItStands() throws InterruptedException {
        String later = names.tenant("root-later");
        String types = """
                [{"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                 {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                 {"name":"ValueSet","identity":"canonical","handling":"operational"},
                 {"name":"CodeSystem","identity":"canonical","handling":"operational"}]""";
        dbo.declare(later, """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":%s}"""
                .formatted(later, types));
        try {
            assertTrue(dbo.until(later, true, Duration.ofMinutes(10)), "no tenant");
            dbo.declare(later, """
                    {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                     "types":%s}""".formatted(later, types));
            ATenantsDoor door = new ATenantsDoor(dbo, later);
            Proves.that(DboPromises.VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS,
                    untilStatus(() -> door.get("/StructureDefinition?url="
                            + encoded("http://hl7.org/fhir/StructureDefinition/Patient")),
                            200, "\"type\":\"Patient\""),
                    "a tenant that became a root did not load the version where it stands");
        } finally {
            dbo.retract(later);
        }
    }

    @Test
    @Order(26)
    @DisplayName("what an envelope would cost to build from the root's compiled parameters is "
            + "small: few parameters reach past plain navigation")
    @Proving(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES)
    void fewParametersReachPastNavigation() {
        int all = 0;
        int beyond = 0;
        int refused = 0;
        // What the root declares: a tenant compiles the parameters of the types
        // it holds, and the root holds the version's own four.
        String[] declared = {"StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem"};
        record Compiled(String unenforceable, String predicate, String[] paths) {
        }
        for (Compiled one : new WhatTheDatabaseHolds(environment, "fhir-r4").each(
                "SELECT unenforceable, predicate, "
                        + "ARRAY(SELECT jsonb_array_elements_text(paths)) "
                        + "FROM definitions.definition_parameter WHERE base = ANY(?)",
                rs -> new Compiled(rs.getString(1), rs.getString(2),
                        (String[]) rs.getArray(3).getArray()),
                (Object) declared)) {
            all++;
            if (one.unenforceable() != null) {
                refused++;
                continue;
            }
            boolean past = one.predicate() != null;
            for (String path : one.paths()) {
                past |= path.contains("? (") || path.contains("like_regex")
                        || path.contains("starts with") || path.contains("==")
                        || path.contains(".type()") || path.contains("exists(");
            }
            beyond += past ? 1 : 0;
        }
        Proves.that(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES,
                all > 50 && beyond < 20,
                "the root's parameters are not compiled, or too many reach past navigation: "
                        + all + " held, " + beyond + " beyond, " + refused + " refused");
    }

    // ── a definition is expanded into rows the moment it arrives ──

    private static final String PATIENT = "http://hl7.org/fhir/StructureDefinition/Patient";
    private static final String ROOT = "fhir-r4";

    @Test
    @Order(27)
    @DisplayName("the version arrives at its root expanded into rows: where each element is, "
            + "under what, and what binds it, the version's own examples included")
    @Proving(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES)
    void theVersionArrivesExpanded() {
        Proves.that(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
                Long.parseLong(rows(ROOT, "SELECT count(*)::text FROM "
                        + "definitions.definition_element").get(0)) > 10_000
                        && rows(ROOT, "SELECT unnest(steps) FROM definitions.definition_element "
                        + "WHERE canonical = ? AND element_id = 'Patient.contact.name'", PATIENT)
                        .equals(List.of("$.\"name\"[*]"))
                        && rows(ROOT, "SELECT parent_id FROM definitions.definition_element "
                        + "WHERE canonical = ? AND element_id = 'Patient.contact.name'", PATIENT)
                        .equals(List.of("Patient.contact")),
                "the root's version is not held as located rows");
        List<String> gender = rows(ROOT, "SELECT binding_strength || ' ' || binding_valueset "
                + "FROM definitions.definition_element WHERE canonical = ? "
                + "AND element_id = 'Patient.gender'", PATIENT);
        assertTrue(gender.size() == 1 && gender.get(0).startsWith("required ")
                && gender.get(0).contains("administrative-gender"), gender.toString());
        String composition = "http://hl7.org/fhir/StructureDefinition/example-composition";
        Proves.that(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
                Long.parseLong(rows(ROOT, "SELECT count(*)::text FROM "
                        + "definitions.definition_element WHERE canonical = ?", composition)
                        .get(0)) > 20
                        && rows(ROOT, "SELECT unnest(steps) FROM definitions.definition_element "
                        + "WHERE canonical = ? AND element_id = 'Composition.status'", composition)
                        .equals(List.of("$.\"status\"[*]")),
                "the version's own differentials were not expanded too");
    }

    @Test
    @Order(28)
    @DisplayName("an element that cannot be located says so by name, and is the exception")
    @Proving(DboPromises.VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME)
    void whatCannotBeLocatedSaysSo() {
        List<String> said = rows(ROOT, "SELECT element_id || ' | ' || unenforceable FROM "
                + "definitions.definition_element WHERE canonical = ? "
                + "AND unenforceable IS NOT NULL ORDER BY ordinal",
                "http://hl7.org/fhir/StructureDefinition/lipidprofile");
        Proves.that(DboPromises.VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME,
                !said.isEmpty() && said.stream().allMatch(row -> row.contains("follows a reference"))
                        && rows(ROOT, "SELECT element_id FROM definitions.definition_element "
                        + "WHERE unenforceable IS NOT NULL AND cardinality(steps) > 0").isEmpty()
                        && Long.parseLong(rows(ROOT, "SELECT count(*)::text FROM "
                        + "definitions.definition_element WHERE unenforceable IS NOT NULL")
                        .get(0)) < 20,
                "what cannot be located was not said by name, or is not the exception: " + said);
    }

    @Test
    @Order(29)
    @DisplayName("a profile stating only its changes is expanded whole, and its snapshot is "
            + "kept beside the rows derived from it")
    @Proving({DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
            DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE})
    void aProfileOfOnlyChangesIsExpandedWhole() throws Exception {
        String nameless = names.canonical("StructureDefinition/nimeline-patsient");
        assertEquals(201, clinic.post("/StructureDefinition", patientProfile(nameless,
                "Patient.name", "\"min\":1")).statusCode());
        assertTrue(untilRows(clinicCode, "SELECT min_occurs::text FROM "
                + "definitions.definition_element WHERE canonical = ? "
                + "AND element_id = 'Patient.name'", nameless), "the profile was never expanded");
        Proves.that(DboPromises.VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES,
                rows(clinicCode, "SELECT min_occurs::text FROM definitions.definition_element "
                        + "WHERE canonical = ? AND element_id = 'Patient.name'", nameless)
                        .equals(List.of("1"))
                        && rows(clinicCode, "SELECT array_to_string(steps, '|') FROM "
                        + "definitions.definition_element WHERE canonical = ? "
                        + "AND element_id = 'Patient.birthDate'", nameless)
                        .equals(List.of("$.\"birthDate\"[*]"))
                        && rows(clinicCode, "SELECT binding_strength FROM "
                        + "definitions.definition_element WHERE canonical = ? "
                        + "AND element_id = 'Patient.gender'", nameless).equals(List.of("required"))
                        && rows(clinicCode, "SELECT element_id FROM definitions.definition_element "
                        + "WHERE canonical = ? ORDER BY ordinal", nameless)
                        .equals(rows(ROOT, "SELECT element_id FROM definitions.definition_element "
                        + "WHERE canonical = ? ORDER BY ordinal", PATIENT)),
                "the profile's differential was not expanded into the whole structure");
        Proves.that(DboPromises.TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE,
                rows(clinicCode, "SELECT count(*)::text FROM definitions.definition_snapshot "
                        + "WHERE canonical = ?", nameless).equals(List.of("1"))
                        && rows(clinicCode, "SELECT (position('\"Patient.gender\"' in "
                        + "convert_from(snapshot, 'UTF8')) > 0)::text FROM "
                        + "definitions.definition_snapshot WHERE canonical = ?", nameless)
                        .equals(List.of("true")),
                "the snapshot was not kept beside the rows derived from it");
    }

    @Test
    @Order(30)
    @DisplayName("an element nothing defines is found where the rows reach, and what is defined "
            + "is not")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void anUndefinedElementIsFound() {
        String unknown = "SELECT path FROM dbo.unknown_issues(?::jsonb, ?) ORDER BY path";
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                rows(ROOT, unknown, "{\"resourceType\":\"Patient\",\"favouriteColour\":\"blue\"}",
                        PATIENT).equals(List.of("Patient.favouriteColour"))
                        && rows(ROOT, unknown, "{\"resourceType\":\"Patient\",\"identifier\":"
                        + "[{\"system\":\"urn:x\",\"value\":\"1\",\"period\":{\"start\":\"2026\"}}]}",
                        PATIENT).isEmpty()
                        && rows(ROOT, unknown, "{\"resourceType\":\"Patient\",\"birthDate\":"
                        + "\"1980-01-01\",\"_birthDate\":{\"id\":\"x\"}}", PATIENT).isEmpty(),
                "an undefined element was not found where the rows reach, or a defined one was");
    }

    @Test
    @Order(31)
    @DisplayName("a structure's rules are held as rows, each either compiled to a path or "
            + "refused by name, never both")
    @Proving({DboPromises.VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES,
            DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME})
    void theRulesAreHeldAsRows() {
        List<String> patient = rows(ROOT, "SELECT key || ' ' || severity || ' ' || "
                + "coalesce(path, '-') FROM definitions.definition_invariant WHERE canonical = ? "
                + "AND element_id = 'Patient' ORDER BY key", PATIENT);
        Proves.that(DboPromises.VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES,
                patient.size() > 4 && patient.stream().anyMatch(r -> r.startsWith("dom-2 error !exists("))
                        && patient.stream().anyMatch(r -> r.contains("warning"))
                        && Long.parseLong(rows(ROOT, "SELECT count(*)::text FROM "
                        + "definitions.definition_invariant").get(0)) > 1000,
                "the version's rules are not held as compiled rows: " + patient);
        Proves.that(DboPromises.VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME,
                rows(ROOT, "SELECT key FROM definitions.definition_invariant "
                        + "WHERE unenforceable IS NOT NULL AND path IS NOT NULL").isEmpty()
                        && rows(ROOT, "SELECT count(*)::text FROM definitions.definition_invariant")
                        .equals(rows(ROOT, "SELECT ((SELECT count(*) FROM "
                        + "definitions.definition_invariant WHERE unenforceable IS NOT NULL) + "
                        + "(SELECT count(*) FROM definitions.definition_invariant "
                        + "WHERE path IS NOT NULL))::text")),
                "a rule is neither compiled nor refused, or both");
    }

    // ── and the release's own SQL answers what a write is ──

    private static final String SHAPE = "http://hl7.org/fhir/StructureDefinition/StructureDefinition";
    private String oneName;
    private String pinned;

    private List<String> issues(String tenant, String profile, String document)
            {
        return rows(tenant, "SELECT key || ' | ' || detail FROM dbo.validate(?::jsonb, ?) "
                + "WHERE severity = 'error' ORDER BY path, key", document, profile);
    }

    @Test
    @Order(32)
    @DisplayName("the release installs its own validation functions into the clinic's database, "
            + "and they read the definitions from one schema of their own")
    @Proving({DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE,
            DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN})
    void theReleaseInstalledItsOwnFunctions() {
        Proves.that(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE,
                rows(clinicCode, "SELECT p.proname FROM pg_proc p JOIN pg_namespace n "
                        + "ON n.oid = p.pronamespace WHERE n.nspname = 'dbo' ORDER BY p.proname")
                        .equals(List.of("admits", "binding_in", "binding_issues", "cardinality_in",
                                "cardinality_issues", "coded_values", "date_key", "descends_from",
                                "envelope", "envelope_canonical", "envelope_key", "envelope_meta",
                                "envelope_pairs", "envelope_parts", "identifier_in",
                                "identifier_issues", "in_value_set", "instances",
                                "invariant_holds", "invariant_in", "invariant_issues", "located",
                                "primitive_in", "primitive_issues", "record_exists",
                                "reference_in", "reference_issues", "token_forms", "unknown_in",
                                "unknown_issues", "validate", "value_in", "value_issues",
                                "walked"))
                        && !rows(clinicCode, "SELECT installed_at::text FROM state.face_sql")
                        .isEmpty(),
                "the clinic's database does not carry exactly the release's functions");
        Proves.that(DboPromises.VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN,
                rows(clinicCode, "SELECT proname FROM (SELECT p.proname, "
                        + "pg_get_functiondef(p.oid) AS body FROM pg_proc p JOIN pg_namespace n "
                        + "ON n.oid = p.pronamespace WHERE n.nspname = 'dbo' AND p.prokind = 'f') f "
                        + "WHERE body ~ 'state[.](definition|term)_'").isEmpty(),
                "a function reads definitions from somewhere other than their own schema");
    }

    @Test
    @Order(33)
    @DisplayName("an element is counted inside its parent, and the clinic's own rule is "
            + "answered alongside the version's")
    @Proving(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE)
    void anElementIsCountedInsideItsParent() throws Exception {
        oneName = names.canonical("StructureDefinition/uhe-nimega-patsient");
        assertEquals(201, clinic.post("/StructureDefinition", patientProfile(oneName,
                "Patient.name", "\"min\":1,\"max\":\"1\"")).statusCode());
        assertTrue(untilRows(clinicCode, "SELECT 1 FROM definitions.definition_element "
                + "WHERE canonical = ?", oneName), "the profile was never expanded");
        String tamm = "{\"family\":\"Tamm\"}";
        Proves.that(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE,
                issues(clinicCode, oneName, "{\"resourceType\":\"Patient\",\"name\":[" + tamm
                        + "],\"contact\":[{\"name\":{\"family\":\"A\"}},{\"name\":"
                        + "{\"family\":\"B\"}}]}").stream().noneMatch(i -> i.startsWith("cardinality"))
                        && issues(clinicCode, oneName, "{\"resourceType\":\"Patient\",\"name\":["
                        + tamm + "],\"contact\":[{\"name\":[{\"family\":\"A\"},{\"family\":"
                        + "\"B\"}]}]}").stream().anyMatch(i -> i.contains("Patient.contact.name")
                        && i.contains("2 times")),
                "an element was counted across parents rather than inside its own");
        Proves.that(DboPromises.VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE,
                issues(clinicCode, oneName, "{\"resourceType\":\"Patient\",\"name\":[" + tamm
                        + "]}").stream().noneMatch(i -> i.contains("Patient.name")
                        || i.contains("unenforceable"))
                        && issues(clinicCode, oneName, "{\"resourceType\":\"Patient\","
                        + "\"gender\":\"female\"}").stream().anyMatch(i -> i.contains("Patient.name"))
                        && issues(clinicCode, oneName, "{\"resourceType\":\"Patient\",\"name\":["
                        + tamm + ",{\"family\":\"Kask\"}]}").stream()
                        .anyMatch(i -> i.contains("Patient.name")),
                "the clinic's own rule is not answered by the release's functions");
    }

    @Test
    @Order(34)
    @DisplayName("what a profile pins and what a binding requires are answered in the database, "
            + "against what the tenant holds")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void whatIsPinnedAndBoundIsAnswered() throws Exception {
        pinned = names.canonical("StructureDefinition/ik-patsient");
        assertEquals(201, clinic.post("/StructureDefinition", """
                {"resourceType":"StructureDefinition","url":"%s","name":"IkPatsient",
                 "status":"active","kind":"resource","abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint","differential":{"element":[
                   {"id":"Patient.identifier.system","path":"Patient.identifier.system",
                    "fixedUri":"https://ee.ee/ik"},
                   {"id":"Patient.maritalStatus","path":"Patient.maritalStatus",
                    "patternCodeableConcept":{"coding":[{"system":
                      "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}]}}]}}"""
                .formatted(pinned)).statusCode());
        assertTrue(untilRows(clinicCode, "SELECT 1 FROM definitions.definition_element "
                + "WHERE canonical = ?", pinned), "the profile was never expanded");
        String married = "\"maritalStatus\":{\"coding\":[{\"system\":"
                + "\"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\",\"code\":\"M\"}],"
                + "\"text\":\"Abielus\"}";
        List<String> wrong = issues(clinicCode, pinned, "{\"resourceType\":\"Patient\","
                + "\"identifier\":[{\"system\":\"https://vale.ee/ik\",\"value\":\"1\"}],"
                + "\"maritalStatus\":{\"coding\":[{\"system\":"
                + "\"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\",\"code\":\"U\"}]}}");
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                issues(clinicCode, pinned, "{\"resourceType\":\"Patient\",\"identifier\":"
                        + "[{\"system\":\"https://ee.ee/ik\",\"value\":\"1\"}]," + married + "}")
                        .stream().noneMatch(i -> i.startsWith("fixed") || i.startsWith("pattern"))
                        && wrong.stream().anyMatch(i -> i.contains("fixed to")
                        && i.contains("Patient.identifier.system"))
                        && wrong.stream().anyMatch(i -> i.contains("must contain"))
                        && issues(clinicCode, pinned, "{\"resourceType\":\"Patient\"," + married
                        + "}").stream().noneMatch(i -> i.contains("must contain")),
                "what the profile pins is not answered in the database: " + wrong);

        String sd = "{\"resourceType\":\"StructureDefinition\",\"url\":\"https://ee.ee/sd/%s\","
                + "\"name\":\"%s\",\"status\":\"%s\",\"kind\":\"resource\","
                + "\"abstract\":false,\"type\":\"Patient\"%s}";
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                issues(ROOT, SHAPE, sd.formatted("a", "A", "active", "")).stream()
                        .noneMatch(i -> i.startsWith("binding"))
                        && issues(ROOT, SHAPE, sd.formatted("b", "B", "kehtetu", "")).stream()
                        .anyMatch(i -> i.contains("kehtetu")
                        && i.contains("StructureDefinition.status"))
                        && issues(ROOT, SHAPE, sd.formatted("c", "C", "active",
                        ",\"jurisdiction\":[{\"coding\":[{\"system\":\"https://ee.ee/oma-maa\","
                        + "\"code\":\"EE\"}]}]")).stream().noneMatch(i -> i.startsWith("binding")),
                "a required binding was not answered from the codes the tenant holds");
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                issues(ROOT, SHAPE, sd.formatted("x", "X", "draft",
                        ",\"baseDefinition\":\"StructureDefinition/Patient\"")).stream()
                        .anyMatch(i -> i.startsWith("primitive") && i.contains("baseDefinition"))
                        && issues(ROOT, SHAPE, sd.formatted("x", "X", "draft",
                        ",\"baseDefinition\":\"http://hl7.org/fhir/StructureDefinition/Patient\""))
                        .stream().noneMatch(i -> i.startsWith("primitive"))
                        && issues(ROOT, SHAPE, sd.formatted("y", "Y", "draft",
                        ",\"identifier\":[{\"system\":\"urn:ietf:rfc:3986\",\"value\":"
                        + "\"Local eCMS identifier\"}]")).stream()
                        .anyMatch(i -> i.startsWith("identifier"))
                        && issues(ROOT, SHAPE, sd.formatted("y", "Y", "draft",
                        ",\"identifier\":[{\"system\":\"urn:ietf:rfc:3986\",\"value\":"
                        + "\"https://ee.ee/identifier/1\"}]")).stream()
                        .noneMatch(i -> i.startsWith("identifier")),
                "a canonical or an RFC 3986 identifier was not judged by what it must be");
    }

    @Test
    @Order(35)
    @DisplayName("the walk reaches what was expanded, and a reference is resolved against the "
            + "records the clinic holds")
    @Proving(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE)
    void aReferenceIsResolvedAgainstTheRecords() {
        assertTrue(rows(clinicCode, "SELECT path FROM definitions.definition_element "
                + "WHERE canonical = ? AND path LIKE 'StructureDefinition.snapshot.element.%'",
                SHAPE).isEmpty(), "the walk descended into a structure's own snapshot");
        assertFalse(rows(clinicCode, "SELECT path FROM definitions.definition_element "
                + "WHERE canonical = ? AND path LIKE 'Patient.identifier%'", pinned).isEmpty());
        String held = dbo.says(clinic.post("/Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Viide\"}]}"))
                .one("id").orElseThrow();
        String linked = "{\"resourceType\":\"Patient\",\"link\":[{\"other\":{\"reference\":"
                + "\"%s\"},\"type\":\"seealso\"}]%s}";
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                issues(clinicCode, pinned, linked.formatted("Patient/" + held, "")).stream()
                        .noneMatch(i -> i.startsWith("reference"))
                        && issues(clinicCode, pinned, linked.formatted(
                        "Patient/8f2b1a54-0000-4000-8000-000000000000", "")).stream()
                        .anyMatch(i -> i.contains("Patient.link.other")
                        && i.contains("not a record this store holds"))
                        && issues(clinicCode, pinned, linked.formatted(
                        "https://teine.ee/fhir/Patient/7", "")).stream()
                        .noneMatch(i -> i.startsWith("reference"))
                        && issues(clinicCode, pinned, linked.formatted("#sees",
                        ",\"contained\":[{\"resourceType\":\"Patient\",\"id\":\"sees\"}]"))
                        .stream().noneMatch(i -> i.startsWith("reference")),
                "a reference was not resolved against the records the clinic holds");
    }

    @Test
    @Order(36)
    @DisplayName("slicing is compiled rather than interpreted, and a rule broken is reported by "
            + "its key")
    @Proving({DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
            DboPromises.VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE})
    void slicingIsCompiledAndRulesAreKeyed() {
        String bp = "http://hl7.org/fhir/StructureDefinition/bp";
        List<String> systolic = rows(ROOT, "SELECT unnest(steps) FROM "
                + "definitions.definition_element WHERE canonical = ? "
                + "AND element_id = 'Observation.component:SystolicBP'", bp);
        String pressure = "{\"resourceType\":\"Observation\",\"status\":\"final\","
                + "\"category\":[{\"coding\":[{\"system\":"
                + "\"http://terminology.hl7.org/CodeSystem/observation-category\","
                + "\"code\":\"vital-signs\"}]}],\"code\":{\"coding\":[{\"system\":"
                + "\"http://loinc.org\",\"code\":\"85354-9\"}]},\"subject\":{\"reference\":"
                + "\"Patient/8f2b1a54-0000-4000-8000-000000000000\"},"
                + "\"effectiveDateTime\":\"2026-09-11\",\"component\":["
                + "{\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"%s\"}]},"
                + "\"valueQuantity\":{\"value\":120,\"unit\":\"mmHg\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"mm[Hg]\"}},"
                + "{\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"8462-4\"}]},"
                + "\"valueQuantity\":{\"value\":80,\"unit\":\"mmHg\","
                + "\"system\":\"http://unitsofmeasure.org\",\"code\":\"mm[Hg]\"}}]}";
        String all = "SELECT key || ' | ' || detail FROM dbo.validate(?::jsonb, ?)";
        Proves.that(DboPromises.VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE,
                systolic.size() == 1 && systolic.get(0).contains(" ? (")
                        && rows(ROOT, all, pressure.formatted("8480-6"), bp).stream()
                        .noneMatch(i -> i.contains("component:SystolicBP"))
                        && rows(ROOT, all, pressure.formatted("9999-9"), bp).stream()
                        .anyMatch(i -> i.contains("Observation.component")),
                "a slice was not compiled, or was not answered: " + systolic);
        String keyed = "SELECT key || ' ' || detail FROM dbo.validate(?::jsonb, ?) ORDER BY key";
        String nested = "{\"resourceType\":\"StructureDefinition\",\"url\":\"https://ee.ee/sd/x\","
                + "\"name\":\"X\",\"status\":\"draft\",\"kind\":\"resource\",\"abstract\":false,"
                + "\"type\":\"Patient\",\"contained\":[{\"resourceType\":\"Patient\",\"id\":\"a\","
                + "\"contained\":[{\"resourceType\":\"Patient\",\"id\":\"b\"}]}]}";
        String plain = "{\"resourceType\":\"StructureDefinition\",\"url\":\"https://ee.ee/sd/z\","
                + "\"name\":\"Z\",\"status\":\"draft\",\"kind\":\"resource\",\"abstract\":false,"
                + "\"type\":\"Patient\"}";
        Proves.that(DboPromises.VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE,
                rows(ROOT, keyed, nested, SHAPE).stream().anyMatch(i -> i.startsWith("dom-2"))
                        && rows(ROOT, keyed, plain, SHAPE).stream()
                        .noneMatch(i -> i.startsWith("dom-2"))
                        && rows(ROOT, "SELECT DISTINCT severity FROM dbo.validate(?::jsonb, ?)",
                        plain, SHAPE).contains("warning"),
                "a broken rule was not reported by its key");
    }

    @Test
    @Order(37)
    @DisplayName("both answerers are asked and the toolchain decides, and the node counts what "
            + "the database made of it, including what it could not compare")
    @Proving(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT)
    void bothAreAskedAndOneDecides() {
        java.util.Map<String, Object> before = tally(clinicCode);
        assertEquals(201, clinic.post("/Patient", """
                {"resourceType":"Patient","meta":{"profile":["%s"]},
                 "name":[{"family":"Tamm","given":["Mari"]}]}""".formatted(oneName)).statusCode());
        HttpResponse<String> twoNames = clinic.post("/Patient", """
                {"resourceType":"Patient","meta":{"profile":["%s"]},
                 "name":[{"family":"Tamm"},{"family":"Kask"}]}""".formatted(oneName));
        assertTrue(twoNames.statusCode() == 422 && twoNames.body().contains("name"),
                twoNames.body());
        assertEquals(201, clinic.post("/Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Saar\"}]}")
                .statusCode());
        java.util.Map<String, Object> after = tally(clinicCode);
        long answered = counted(after, "agreed") + counted(after, "onlyTheToolchain")
                + counted(after, "onlyTheDatabase")
                - counted(before, "agreed") - counted(before, "onlyTheToolchain")
                - counted(before, "onlyTheDatabase");
        Proves.that(DboPromises.VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT,
                answered >= 2 && counted(after, "notHeld") > counted(before, "notHeld"),
                "the node did not count both answers, or what could not be compared: "
                        + before + " / " + after);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> tally(String tenant) {
        Object read = cloud.jengu.dbo.core.wire.RecordWire.read(dbo.get(
                java.net.URI.create(dbo.at(tenant)).resolve("/runtime/tenants").toString(),
                "stories-ops").body());
        for (Object row : (List<?>) ((java.util.Map<?, ?>) read).get("tenants")) {
            java.util.Map<?, ?> fields = (java.util.Map<?, ?>) row;
            if (tenant.equals(fields.get("code"))) {
                return (java.util.Map<String, Object>) fields.get("answeredBesideTheToolchain");
            }
        }
        throw new AssertionError(tenant + " is not on the node's list");
    }

    private static long counted(java.util.Map<String, Object> tally, String key) {
        Object value = tally.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ── and a clinic can be on a version with no generated model at all ──

    @Test
    @Order(38)
    @DisplayName("a clinic on the R6 ballot comes up, validates against the ballot's own "
            + "definitions, is searched by what its parameters extract, and keeps versions")
    @Proving({DboPromises.VER_PERSONALITY_OWNS_MEANING, DboPromises.SRCH_STRICT_BY_DEFAULT})
    void aClinicOnTheBallotIsServedFromDefinitions() throws Exception {
        String ballot = names.tenant("on-the-ballot");
        String eid = "urn:" + names.prefix() + ":" + names.run() + ":eid";
        dbo.declare(ballot, """
                {"code":"%s","face":"r6","audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(ballot, eid));
        try {
            assertTrue(dbo.until(ballot, true, Duration.ofMinutes(10)), "the R6 clinic never came up");
            ATenantsDoor door = new ATenantsDoor(dbo, ballot);
            String metadata = dbo.get(dbo.at(ballot) + "/fhir/metadata", null).body();
            assertTrue(metadata.contains("\"fhirVersion\":\"6.0.0-ballot5\"")
                    && metadata.contains("\"type\":\"Patient\""), metadata);

            HttpResponse<String> unicorn = door.post("/Patient",
                    "{\"resourceType\":\"Patient\",\"gender\":\"unicorn\"}");
            Proves.that(DboPromises.VER_PERSONALITY_OWNS_MEANING, unicorn.statusCode() == 422,
                    "a code outside the ballot's value set was stored: " + unicorn.body());
            HttpResponse<String> created = door.post("/Patient", """
                    {"resourceType":"Patient","identifier":[{"system":"%s","value":"38001010001"}],
                     "name":[{"family":"Aiakas","given":["Kass"]}],"gender":"female",
                     "birthDate":"1980-01-01"}""".formatted(eid));
            assertEquals(201, created.statusCode(), created.body());
            String id = dbo.says(created).one("id").orElseThrow();

            String byIdentifier = door.get("/Patient?identifier="
                    + encoded(eid + "|38001010001")).body();
            Proves.that(DboPromises.VER_PERSONALITY_OWNS_MEANING,
                    byIdentifier.contains("Aiakas") && byIdentifier.contains("\"mode\":\"match\"")
                            && door.get("/Patient?family=aiak").body().contains("Aiakas"),
                    "the ballot's own parameters did not find what was written: " + byIdentifier);
            Proves.that(DboPromises.SRCH_STRICT_BY_DEFAULT,
                    door.get("/Patient?nosuchparam=1").statusCode() == 400,
                    "an unknown parameter was ignored rather than refused");

            String read = door.get("/Patient/" + id).body();
            assertTrue(read.contains("\"id\":\"" + id + "\"")
                    && read.contains("\"versionId\":\"1\""), read);
            assertEquals(200, door.put("/Patient/" + id, """
                    {"resourceType":"Patient","id":"%s",
                     "identifier":[{"system":"%s","value":"38001010001"}],
                     "name":[{"family":"Aiakas","given":["Kass","Teine"]}],"gender":"female",
                     "birthDate":"1980-01-01"}""".formatted(id, eid)).statusCode());
            String history = door.get("/Patient/" + id + "/_history").body();
            assertTrue(history.contains("\"type\":\"history\"")
                    && history.contains("\"versionId\":\"1\"")
                    && history.contains("\"versionId\":\"2\""), history);
        } finally {
            dbo.retract(ballot);
        }
    }

    private String patientProfile(String url, String path, String constraint) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","name":"P%s","status":"active",
                 "kind":"resource","abstract":false,"type":"Patient",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                 "derivation":"constraint","differential":{"element":[
                   {"id":"%s","path":"%s",%s}]}}"""
                .formatted(url, Integer.toHexString(url.hashCode()), path, path, constraint);
    }

    /** One column of a query against a tenant's database, as text. */
    private List<String> rows(String tenant, String sql, String... parameters) {
        return new WhatTheDatabaseHolds(environment, tenant).rows(sql, (Object[]) parameters);
    }

    private boolean untilRows(String tenant, String sql, String... parameters) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            if (!rows(tenant, sql, parameters).isEmpty()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private boolean untilStatus(java.util.function.Supplier<HttpResponse<String>> ask,
            int status) throws InterruptedException {
        return untilStatus(ask, status, "");
    }

    private boolean untilStatus(java.util.function.Supplier<HttpResponse<String>> ask,
            int status, String containing) throws InterruptedException {
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            HttpResponse<String> answered = ask.get();
            if (answered.statusCode() == status && answered.body().contains(containing)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int occurrences(String in, String what) {
        int n = 0;
        for (int at = in.indexOf(what); at >= 0; at = in.indexOf(what, at + 1)) {
            n++;
        }
        return n;
    }

    private String countedObservation;

    private String orders() {
        return names.canonical("StructureDefinition/order");
    }

    private String states() {
        return "urn:" + names.prefix() + ":" + names.run() + ":state";
    }

    private String order(String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"ShapeOrder","status":"active","kind":"resource","abstract":false,
                 "type":"Basic","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[{"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(orders(), version);
    }

    private String anOrder(String state) {
        return """
                {"resourceType":"Basic","code":{"coding":[{"system":"%s","code":"%s"}]},
                 "meta":{"profile":["%s"]}}""".formatted(states(), state, orders());
    }

    private String ordersMap() {
        return """
                {"resourceType":"StructureMap","url":"%s","version":"1.0.0",
                 "name":"OrderTwoToThree","status":"active",
                 "structure":[{"url":"%s|2.0.0","mode":"source"},
                              {"url":"%s|3.0.0","mode":"target"}],
                 "group":[{"name":"main","typeMode":"types",
                   "input":[{"name":"src","type":"Basic","mode":"source"},
                            {"name":"tgt","type":"Basic","mode":"target"}],
                   "rule":[
                     {"name":"code","source":[{"context":"src","element":"code","variable":"c"}],
                      "target":[{"context":"tgt","contextType":"variable","element":"code",
                                 "transform":"copy","parameter":[{"valueId":"c"}]}]},
                     {"name":"meta","source":[{"context":"src","element":"meta","variable":"m"}],
                      "target":[{"context":"tgt","contextType":"variable","element":"meta",
                                 "transform":"copy","parameter":[{"valueId":"m"}]}]}]}]}"""
                .formatted(names.canonical("StructureMap/order-2-to-3"), orders(), orders());
    }

    private String profileAt(String url, String version, String name) {
        return """
                {"resourceType":"StructureDefinition","url":"%s",%s"name":"%s",
                 "status":"active","kind":"resource","abstract":false,"type":"Observation",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Observation",
                 "derivation":"constraint","differential":{"element":[
                   {"id":"Observation.subject","path":"Observation.subject","min":1}]}}"""
                .formatted(url, version, name);
    }

    private String claimingOf(String profile) {
        return """
                {"resourceType":"Observation","status":"final","code":{"text":"pulse"},
                 "subject":{"display":"somebody"},"meta":{"profile":["%s"]}}"""
                .formatted(profile);
    }

    private String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return dbo.says(created).one("id").orElseThrow();
    }

    private HttpResponse<String> adminResponse(String path) {
        return dbo.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(clinicCode) + "/admin" + path))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()),
                dbo.token(clinicCode));
    }

    private static long versionOf(String claimJson, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{\"id\":\"" + id + "\",\"version\":(\\d+)").matcher(claimJson);
        assertTrue(m.find(), "the claim does not name " + id + ": " + claimJson);
        return Long.parseLong(m.group(1));
    }

    private String applyBack(String id, long version, String payload) {
        String body = "{\"held\":[{\"id\":\"" + id + "\",\"version\":" + version
                + ",\"payload\":\"" + java.util.Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
        return dbo.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(clinicCode) + "/admin/reshape/apply?type=Basic"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)),
                dbo.token(clinicCode)).body();
    }

    /** A POST to the clinic's maintenance surface, the door an operator uses. */
    private String admin(String path) {
        return dbo.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(dbo.at(clinicCode) + "/admin" + path))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()),
                dbo.token(clinicCode)).body();
    }

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
