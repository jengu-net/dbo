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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration handed to the configuration door is applied through the face,
 * not onto the engine beneath it.
 *
 * <p>The engine is type-blind by design, and that leaves two questions it
 * cannot answer about a declared set. <b>What is this made of</b> — a
 * vocabulary's concepts live where {@code $lookup} can reach them, and one
 * written the ordinary way is stored whole and answers nothing, which is worse
 * than absent because the resource is plainly there. And <b>is this the same
 * declaration again</b> — sameness is a domain question, so an engine
 * comparing bytes rewrites a record because a serialiser reordered two keys.
 *
 * <p>Both bit at the same seam, and a loader reading a repository on a
 * schedule meets both: a zone of vocabularies that answer nothing, rewritten
 * whole every couple of minutes onto a feed everything downstream is watching.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ADeclarationIsAppliedThroughTheFaceIT {

    static final String ZONE = "through-the-face";
    static final String SYSTEM = "https://zone.test/cs/declared";
    static final String BENCH_SYSTEM = "https://zone.test/benches";
    static final String OID_BEARING = "https://zone.test/cs/with-oid";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String bearer;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-through-the-face");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ADeclarationIsAppliedThroughTheFaceIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(ZONE + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"Device","identity":"identifier","systems":["%s"],
                   "handling":"projected-config"}]}"""
                .formatted(ZONE, BENCH_SYSTEM));
        UntilServed.scan(manager, ZONE);
        manager.authority(ZONE).ensureClient("a-loader", "loader-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.CONFIGURATION));
        manager.authority(ZONE).ensureClient("a-reader", "reader-secret",
                List.of("system/*.read"));
        bearer = token("a-loader", "loader-secret",
                cloud.jengu.dbo.auth.Scopes.CONFIGURATION);
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

    /** One vocabulary, declared the way a file in a repository declares it. */
    private static String codeSystem(String version, String display) {
        return "{\"type\":\"CodeSystem\",\"name\":\"terminology/declared.json\",\"payload\":"
                + "{\"resourceType\":\"CodeSystem\",\"url\":\"" + SYSTEM + "\",\"version\":\""
                + version + "\",\"status\":\"active\",\"content\":\"complete\",\"concept\":["
                + "{\"code\":\"a\",\"display\":\"" + display + "\"},"
                + "{\"code\":\"b\",\"display\":\"Beta\"}]}}";
    }

    /** A declaration whose stored form is the whole of it. */
    private static String valueSet(String status) {
        return "{\"type\":\"ValueSet\",\"name\":\"terminology/declared-vs.json\",\"payload\":"
                + "{\"resourceType\":\"ValueSet\",\"url\":\"" + SYSTEM + "/vs\",\"version\":\"1\","
                + "\"status\":\"" + status + "\",\"compose\":{\"include\":[{\"system\":\""
                + SYSTEM + "\"}]}}}";
    }

    /**
     * The same vocabulary as a real jurisdiction publishes one: identified by
     * its url, and CARRYING the national OID it is also known by.
     */
    private static String codeSystemWithOid(String display) {
        return "{\"type\":\"CodeSystem\",\"name\":\"terminology/with-oid.json\",\"payload\":"
                + "{\"resourceType\":\"CodeSystem\",\"url\":\"" + OID_BEARING + "\","
                + "\"identifier\":[{\"system\":\"urn:ietf:rfc:3986\","
                + "\"value\":\"urn:oid:1.2.3.4\"}],"
                + "\"version\":\"1\",\"status\":\"active\",\"content\":\"complete\","
                + "\"concept\":[{\"code\":\"a\",\"display\":\"" + display + "\"}]}}";
    }

    /** A projected record: the lane's to write, and the lane's to take away. */
    private static String bench(String code) {
        return "{\"type\":\"Device\",\"name\":\"devices/" + code + ".json\",\"payload\":"
                + "{\"resourceType\":\"Device\",\"status\":\"active\",\"identifier\":[{"
                + "\"system\":\"" + BENCH_SYSTEM + "\",\"value\":\"" + code + "\"}]}}";
    }

    /**
     * The declared vocabulary has to arrive at the grain the tenant keeps
     * concepts in. Handed over and then asked — over the surface, because a
     * client asking is the whole reason this matters — is the only arrangement
     * that proves it was not stored whole.
     */
    @Test
    @Order(1)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.TERM_NATIVE_FORM,
            DboPromises.TERM_EVERY_TENANT_ANSWERS})
    @DisplayName("a vocabulary handed to the configuration door answers $lookup, rather than "
            + "sitting there whole and answering nothing")
    void aDeclaredVocabularyAnswers() throws Exception {
        HttpResponse<String> answered = hand("{\"correlation\":\"commit:one\","
                + "\"declarations\":[" + codeSystem("1", "Alpha") + "]}");

        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"applied\":1"), answered.body());

        String lookup = read("/CodeSystem/$lookup?system="
                + URLEncoder.encode(SYSTEM, StandardCharsets.UTF_8) + "&code=a");
        assertTrue(lookup.contains("Alpha"),
                "the declaration was applied and the concept it declared cannot be resolved, "
                        + "which is what being stored whole looks like from outside: " + lookup);

        // The other half of the same sentence: the shell the engine holds is
        // honest about not carrying them, so nobody reads the resource and
        // concludes the vocabulary is empty.
        String fetched = read("/CodeSystem?url="
                + URLEncoder.encode(SYSTEM, StandardCharsets.UTF_8));
        assertTrue(fetched.contains("not-present"),
                "stored at its own grain, the resource has to say the concepts are elsewhere: "
                        + fetched);
    }

    /**
     * A loader polling a repository posts the same set every tick. Nothing in
     * it moved, so nothing should move: not a version, not a history row, and
     * not an event on the feed.
     */
    @Test
    @Order(2)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    @DisplayName("the same declaration handed again is unchanged, and the record it names does "
            + "not move")
    void theSameDeclarationTwiceWritesNothing() throws Exception {
        assertTrue(hand("{\"declarations\":[" + valueSet("draft") + "]}").body()
                .contains("\"applied\":1"), "the first pass has to apply it");
        String was = versionOf("ValueSet", SYSTEM + "/vs");

        HttpResponse<String> again = hand("{\"declarations\":[" + valueSet("draft") + "]}");

        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"unchanged\":1"),
                "the door rewrote a declaration that had not changed: " + again.body());
        assertTrue(again.body().contains("\"applied\":0"),
                "nothing moved, so nothing was applied: " + again.body());
        assertEquals(was, versionOf("ValueSet", SYSTEM + "/vs"),
                "the record was written again for a declaration that had not changed");
    }

    /**
     * And the skip is not a deafness: the pass after a real change applies it.
     * A cache that never lets go is worse than no cache.
     */
    @Test
    @Order(3)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    @DisplayName("a changed declaration still lands")
    void aChangedDeclarationStillLands() throws Exception {
        String was = versionOf("ValueSet", SYSTEM + "/vs");

        HttpResponse<String> moved = hand("{\"declarations\":[" + valueSet("active") + "]}");

        assertEquals(200, moved.statusCode(), moved.body());
        assertTrue(moved.body().contains("\"applied\":1"), moved.body());
        assertFalse(was.equals(versionOf("ValueSet", SYSTEM + "/vs")),
                "the declaration changed and the record did not");
    }

    /**
     * The boundary that used to be here, now on the other side of it.
     *
     * <p>A vocabulary's whole form is ASSEMBLED, and the assembly carries what
     * it derives — a concept count no declaration ever wrote. So a comparison
     * made above the face held a written document against a computed one, said
     * "changed" every time, and re-kept a zone of vocabularies on every tick
     * that changed nothing. Only the thing that took the vocabulary apart can
     * answer it, and it does now.
     */
    @Test
    @Order(4)
    @Proving({DboPromises.TERM_NATIVE_FORM,
            DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS})
    @DisplayName("a vocabulary declared again unchanged is a read, not another pass over its "
            + "concepts")
    void aVocabularyDeclaredAgainIsUnchanged() throws Exception {
        HttpResponse<String> again = hand("{\"declarations\":[" + codeSystem("1", "Alpha") + "]}");

        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"unchanged\":1"),
                "the vocabulary had not moved and was taken apart again: " + again.body());
        assertTrue(again.body().contains("\"applied\":0"), again.body());

        String lookup = read("/CodeSystem/$lookup?system="
                + URLEncoder.encode(SYSTEM, StandardCharsets.UTF_8) + "&code=a");
        assertTrue(lookup.contains("Alpha"),
                "skipped and lost: the concepts have to still be there: " + lookup);
    }

    /**
     * The edit a cheaper answer would swallow.
     *
     * <p>Trusting the declared {@code version} would make this vanish: same
     * url, same version, a corrected display. It would never land and nobody
     * would be told — which is worse than the write it saves, because an
     * absent permission or an absent write gets reported by whoever hits it
     * and a silently ignored correction does not.
     */
    @Test
    @Order(5)
    @Proving({DboPromises.TERM_NATIVE_FORM,
            DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS})
    @DisplayName("a concept corrected without bumping the version still lands, because sameness "
            + "is the concepts and not a number beside them")
    void aCorrectionWithoutAVersionBumpStillLands() throws Exception {
        HttpResponse<String> corrected = hand("{\"declarations\":["
                + codeSystem("1", "Alpha corrected") + "]}");

        assertEquals(200, corrected.statusCode(), corrected.body());
        assertTrue(corrected.body().contains("\"applied\":1"),
                "the version had not moved, so the correction was taken for the same "
                        + "vocabulary and dropped: " + corrected.body());

        String lookup = read("/CodeSystem/$lookup?system="
                + URLEncoder.encode(SYSTEM, StandardCharsets.UTF_8) + "&code=a");
        assertTrue(lookup.contains("Alpha corrected"),
                "applied, and the concepts behind it did not move: " + lookup);

        // And back, so the ordering of everything after this is undisturbed.
        assertTrue(hand("{\"declarations\":[" + codeSystem("1", "Alpha") + "]}").body()
                .contains("\"applied\":1"), "the vocabulary would not go back");
    }

    /**
     * The same declaration, written by a tool that orders its keys differently.
     *
     * <p>Nothing about it changed, and bytes say it did. That is the whole
     * reason sameness is the face's answer rather than the engine's: a
     * declarer who switched serialisers would otherwise rewrite a zone once,
     * and one whose serialiser is unstable would rewrite it on every tick,
     * with the store insisting each time that something moved.
     */
    @Test
    @Order(6)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    @DisplayName("the same declaration with its keys in another order is still the same "
            + "declaration")
    void aReorderedDeclarationIsTheSameDeclaration() throws Exception {
        String was = versionOf("ValueSet", SYSTEM + "/vs");

        HttpResponse<String> again = hand("{\"declarations\":[{\"type\":\"ValueSet\","
                + "\"name\":\"terminology/declared-vs.json\",\"payload\":{"
                + "\"status\":\"active\",\"compose\":{\"include\":[{\"system\":\"" + SYSTEM
                + "\"}]},\"version\":\"1\",\"url\":\"" + SYSTEM + "/vs\","
                + "\"resourceType\":\"ValueSet\"}}]}");

        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"unchanged\":1"),
                "the same declaration, spelt in another order, was written again: "
                        + again.body());
        assertEquals(was, versionOf("ValueSet", SYSTEM + "/vs"),
                "a reordered serialisation moved the record");
    }

    /**
     * A loader polling a repository posts the same READ every tick, and says
     * so with a marker. The pass that finds this scope already agreed with
     * exactly that read does nothing and says it did nothing.
     *
     * <p>Without it the same set is re-applied every time, which for a zone
     * read every two minutes is a rewrite of everything in it onto a feed
     * everything downstream is watching — and the declarer had no way to
     * prevent it, because a list cannot say it is the same list.
     */
    @Test
    @Order(7)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.PROC_CONFIG_READ_FROM_A_SOURCE})
    @DisplayName("a posted set that says which read it is, and is the read this scope already "
            + "agreed with, is a read of nothing")
    void aPostedReadThisScopeAgreedWithIsARead() throws Exception {
        String read = "{\"marker\":\"commit:settled\",\"declarations\":["
                + valueSet("active") + "]}";

        HttpResponse<String> first = hand(read);
        assertEquals(200, first.statusCode(), first.body());

        HttpResponse<String> again = hand(read);
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"read\":0"),
                "the same read, posted again, was read again: " + again.body());
    }

    /**
     * And the other half a list could never have: what a complete read stops
     * naming is taken away. A device decommissioned in a repository stops
     * being a record here, rather than standing until somebody notices.
     */
    @Test
    @Order(8)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED})
    @DisplayName("a posted read that says it is complete withdraws what it no longer names")
    void aCompletePostedReadWithdraws() throws Exception {
        // A projected type, because only a type the lane owns outright can be
        // withdrawn by machinery that cannot otherwise tell whose it is.
        HttpResponse<String> declared = hand("{\"marker\":\"commit:has-bench\","
                + "\"complete\":true,\"declarations\":[" + bench("bench-7") + "]}");
        assertEquals(200, declared.statusCode(), declared.body());
        assertTrue(declared.body().contains("\"applied\":1"), declared.body());

        HttpResponse<String> without = hand("{\"marker\":\"commit:bench-gone\","
                + "\"complete\":true,\"declarations\":[" + bench("bench-8") + "]}");

        assertEquals(200, without.statusCode(), without.body());
        assertTrue(without.body().contains("\"withdrawn\":1"),
                "the read stopped naming it and it stayed: " + without.body());
    }

    /**
     * Completeness is a claim about a read, so it needs a read to be a claim
     * about. Honoured once for a body this store cannot recognise again, it
     * would quietly mean nothing on the next pass.
     */
    @Test
    @Order(9)
    @Proving(DboPromises.PROC_CONFIG_WITHDRAWAL_IS_DECLARED)
    @DisplayName("a set claiming to be complete without saying which read it is, is refused")
    void completenessWithoutAReadIsRefused() throws Exception {
        HttpResponse<String> refused = hand("{\"complete\":true,\"declarations\":["
                + valueSet("active") + "]}");

        assertEquals(400, refused.statusCode(),
                "a claim nothing can hold this declarer to was accepted: " + refused.body());
        assertTrue(refused.body().contains("marker"),
                "and the refusal has to say what would make the claim keepable: "
                        + refused.body());
    }

    /**
     * A vocabulary identified by its url, carrying the OID it is also known
     * by, is applied — and is the same record when it is declared again.
     *
     * <p>An envelope carries every identifier a resource bears and, for a
     * canonical type, the canonical identity beside them; the resource's own
     * tokens come first. Claiming the first of them claimed a national OID as
     * the identity of a type claimed by its url, the engine refused it —
     * rightly, that is not what the type is claimed under — and the door
     * answered {@code skipped} with the reason only in the run. Declarations
     * without an identifier went in, which is what made it look like something
     * about the awkward ones rather than about all of them: a jurisdiction's
     * 459 arrived as 89.
     */
    @Test
    @Order(10)
    @Proving({DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS,
            DboPromises.PROC_CONFIG_APPLIES_AS_A_SWEEP})
    @DisplayName("a canonical declaration carrying an identifier is claimed by its url, not by "
            + "the identifier it happens to list first")
    void aCanonicalDeclarationCarryingAnIdentifierIsApplied() throws Exception {
        HttpResponse<String> applied = hand("{\"declarations\":["
                + codeSystemWithOid("Alpha") + "]}");

        assertEquals(200, applied.statusCode(), applied.body());
        assertTrue(applied.body().contains("\"applied\":1"),
                "a declaration the face accepts was refused at the door: " + applied.body());

        // Declared again, changed, so it is an upsert rather than a create —
        // the half that says the claim landed on the right identity.
        HttpResponse<String> again = hand("{\"declarations\":["
                + codeSystemWithOid("Alpha prime") + "]}");
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"applied\":1"), again.body());

        assertEquals(1, manager.runtime(ZONE).orElseThrow().engine()
                        .getByIdentifier("CodeSystem",
                                List.of(new cloud.jengu.dbo.core.api.Identifier(
                                        cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM,
                                        OID_BEARING))).size(),
                "declared twice and held twice: the second pass claimed a different identity "
                        + "than the first");

        // And it is a vocabulary, not just a row: the grain is unaffected by
        // which identity the claim was made under.
        String lookup = read("/CodeSystem/$lookup?system="
                + URLEncoder.encode(OID_BEARING, StandardCharsets.UTF_8) + "&code=a");
        assertTrue(lookup.contains("Alpha prime"), lookup);
    }

    /**
     * The tally says how many nobody could apply. It cannot say which, and the
     * declarer is the one who has to open the file — so the cards come back
     * with it, each naming the declaration the way the declarer named it.
     */
    @Test
    @Order(11)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    @DisplayName("a declarer reads the cards of the pass it just ran, each naming the "
            + "declaration as the declarer named it")
    void theAnswerCarriesTheCardsOfThePass() throws Exception {
        String named = "zone/vocab/broken-status.json";
        HttpResponse<String> answered = hand("{\"declarations\":["
                + "{\"type\":\"ValueSet\",\"name\":\"" + named + "\","
                + "\"payload\":{\"resourceType\":\"ValueSet\",\"url\":\""
                + SYSTEM + "/vs-broken\",\"status\":\"unicorn\"}}]}");

        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"skipped\":1"),
                "a declaration the engine refuses has to be skipped rather than applied: "
                        + answered.body());
        // The whole point: the name is the declarer's own. Answered with this
        // store's id for what the declaration would have become, a declarer
        // has nothing to open.
        assertTrue(answered.body().contains(named),
                "the tally said one was skipped and the answer does not say which: "
                        + answered.body());
        assertTrue(answered.body().contains("\"run\""),
                "a pass that carded something has to name the run it carded it on, because "
                        + "the cards outlive the answer: " + answered.body());
    }

    /**
     * And the field is not noise. A pass that refused nothing says so with an
     * empty list rather than by leaving it out — absence is what a store that
     * never sent it looks like, and a declarer cannot tell those apart.
     */
    @Test
    @Order(12)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    @DisplayName("a pass that refused nothing answers with no cards, and says so")
    void aCleanPassCarriesAnEmptyCardList() throws Exception {
        HttpResponse<String> answered = hand("{\"declarations\":["
                + codeSystem("2", "Beta") + "]}");

        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"skipped\":0"), answered.body());
        assertTrue(answered.body().contains("\"cards\":[]"),
                "a clean pass has to say it carded nothing, rather than omitting the field: "
                        + answered.body());
    }

    /**
     * A refusal that says only "unknown" reads as this store having nothing to
     * say about the type. An embedder skips the entry and carries on, so the
     * catalogue it was projecting is absent, and the first sign is a process
     * that should have advanced and has not — weeks later.
     */
    @Test
    @Order(13)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    @DisplayName("a type this tenant does not serve is refused with what it does serve, so a "
            + "caller can tell a store that will not hold it from one that cannot")
    void aRefusalSaysWhatThisTenantDoesServe() throws Exception {
        String refused = read("/ActivityDefinition");

        assertTrue(refused.contains("ActivityDefinition is not one of them"),
                "the refusal has to name the type it is refusing: " + refused);
        // The count, not a count: a face serves what the declaration names and
        // what it adds of its own, and pinning the exact number here would
        // fail for a face that grew one while saying nothing about whether a
        // caller can act on the refusal.
        java.util.regex.Matcher serves = java.util.regex.Pattern
                .compile("this tenant serves (\\d+) resource types").matcher(refused);
        assertTrue(serves.find(),
                "a caller told only 'unknown' cannot tell a type this store will not hold "
                        + "from one it has never heard of: " + refused);
        assertTrue(Integer.parseInt(serves.group(1)) >= 3,
                "the refusal counts fewer types than the declaration names: " + refused);
        assertTrue(refused.contains("/metadata"),
                "the refusal has to say where the servable types are named, or a reader is "
                        + "left where they started: " + refused);
    }

    /**
     * Every type the surface serves is a type the capability statement names,
     * and the other way about. A type in one and not the other is the case
     * that costs weeks, because each side on its own looks right.
     */
    @Test
    @Order(14)
    @Proving(DboPromises.TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS)
    @DisplayName("the capability statement and the surface agree about which types exist")
    void whatIsAdvertisedIsWhatIsServed() throws Exception {
        String capability = read("/metadata");
        for (String declared : java.util.List.of("CodeSystem", "ValueSet", "Device")) {
            assertTrue(capability.contains("\"type\":\"" + declared + "\""),
                    declared + " is served and the capability statement does not name it: "
                            + capability);
        }
        assertFalse(capability.contains("\"type\":\"ActivityDefinition\""),
                "the capability statement names a type the surface refuses: " + capability);
    }

    /** What the engine holds for this declaration, by its version. */
    private String versionOf(String type, String url) {
        return manager.runtime(ZONE).orElseThrow().engine()
                .getByIdentifier(type, List.of(new cloud.jengu.dbo.core.api.Identifier(
                        cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url)))
                .stream().findFirst()
                .map(cloud.jengu.dbo.core.api.StoredObject::versionId)
                .map(String::valueOf)
                .orElseThrow(() -> new AssertionError("declared and not held"));
    }

    private HttpResponse<String> hand(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(ZONE).replace("/fhir", "/configuration")))
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String read(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(manager.baseUrl(ZONE) + path))
                        .header("Authorization", "Bearer "
                                + token("a-reader", "reader-secret", "system/*.read"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String token(String client, String secret, String scope) throws Exception {
        String form = "grant_type=client_credentials&client_id=" + client
                + "&client_secret=" + secret + "&scope="
                + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(
                        URI.create(manager.baseUrl(ZONE).replace("/fhir", "/oidc/token")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int at = body.indexOf("\"access_token\"");
        int start = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(start, body.indexOf('"', start));
    }
}
