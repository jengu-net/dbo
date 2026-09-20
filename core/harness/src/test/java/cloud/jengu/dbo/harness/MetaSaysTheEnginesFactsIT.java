package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two engine facts are said where FHIR says them: {@code Meta.security}
 * carries the handling class this store enforces on the record, and
 * {@code Meta.source} says which upstream streamed a copy here. Before this, a
 * client received the data and not the classification governing it — the
 * element has existed for exactly this since R4.
 *
 * <p>On the shared runtime, as a source and a receiver that declares it. The
 * sync round it drives is what the scan loop drives anyway, and every
 * assertion is about a record this class wrote.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetaSaysTheEnginesFactsIT {

    static final HttpClient http = HttpClient.newHttpClient();

    static SharedTenants.Tenant allikas;
    static SharedTenants.Tenant saaja;
    static String onSource;
    static String onReceiver;

    @BeforeAll
    void up() {
        // In this order: the receiver declares the source, so the source has
        // to be serving before it.
        allikas = SharedTenants.of(SharedTenants.Shape.R4_MIRROR_SOURCE);
        saaja = SharedTenants.of(SharedTenants.Shape.R4_MIRROR_RECEIVER);
        onSource = allikas.token("meta-source", "system/*.write", "system/*.read");
        onReceiver = saaja.token("meta-receiver", "system/*.write", "system/*.read");
        saaja.syncOnce();
    }

    private String base(SharedTenants.Tenant tenant) {
        return tenant.fhir();
    }

    /** Which tenant a url is on decides which credential it carries. */
    private static String bearerFor(String url) {
        return url.contains(saaja.code()) ? onReceiver : onSource;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearerFor(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/fhir+json")
                        .header("Authorization", "Bearer " + bearerFor(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every served record says the classification governing it. */
    @Test
    void aServedRecordCarriesItsHandlingClassInMetaSecurity() throws Exception {
        HttpResponse<String> created = post(base(allikas) + "/Patient",
                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = Extracted.field(created.body(), "id");

        String served = get(base(allikas) + "/Patient/" + id).body();
        assertTrue(served.contains("\"system\":\"urn:dbo:handling\"")
                        && served.contains("\"code\":\"operational\""),
                "the read says the declared class: " + served);
        // and the search path frames the same fact
        String bundle = get(base(allikas) + "/Patient").body();
        assertTrue(bundle.contains("urn:dbo:handling"),
                "a search hit is stamped like a read: " + bundle);
    }

    /** A streamed copy says where it came from; the original says nothing. */
    @Test
    void aStreamedCopySaysItsUpstreamInMetaSource() throws Exception {
        String atSource = post(base(allikas) + "/CodeSystem", """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"https://allikas.test/cs/varvid","concept":[{"code":"roheline"}]}""")
                .body();
        assertFalse(atSource.contains("urn:dbo:upstream:"),
                "the tenant's own record has no upstream: " + atSource);
        saaja.syncOnce();

        String copied = get(base(saaja)
                + "/CodeSystem?url=https://allikas.test/cs/varvid").body();
        assertTrue(copied.contains("\"source\":\"urn:dbo:upstream:" + allikas.code() + "\""),
                "the copy says which upstream streamed it: " + copied);
        assertTrue(copied.contains("\"code\":\"mirrored\""),
                "and the handling stamp says what governs it here: " + copied);
    }

    /** The author's own security codings survive, and ours never accumulates. */
    @Test
    void anAuthorsSecurityCodingSurvivesAndOursDoesNotAccumulate() throws Exception {
        HttpResponse<String> created = post(base(allikas) + "/Patient", """
                {"resourceType":"Patient",
                 "meta":{"security":[{"system":"http://terminology.hl7.org/CodeSystem/v3-Confidentiality","code":"R"}]}}""");
        assertEquals(201, created.statusCode(), created.body());
        String id = Extracted.field(created.body(), "id");

        String served = get(base(allikas) + "/Patient/" + id).body();
        assertTrue(served.contains("v3-Confidentiality") && served.contains("\"code\":\"R\""),
                "the author's coding survives: " + served);
        assertEquals(1, count(served, "urn:dbo:handling"),
                "exactly one engine stamp: " + served);

        // Round trip: the served document, with our stamp in its bytes, is
        // stored as a new record and served again — still exactly one stamp.
        // the id comes out with its leading comma, or the leftover comma makes
        // the round-tripped document invalid JSON
        HttpResponse<String> roundTripped = post(base(allikas) + "/Patient",
                served.replaceAll(",\"id\":\"[^\"]+\"", ""));
        assertEquals(201, roundTripped.statusCode(), roundTripped.body());
        String secondId = Extracted.field(roundTripped.body(), "id");
        String again = get(base(allikas) + "/Patient/" + secondId).body();
        assertEquals(1, count(again, "urn:dbo:handling"),
                "a served document written back does not accumulate stamps: " + again);
        assertTrue(again.contains("v3-Confidentiality"),
                "while the author's coding still survives: " + again);
    }

    /**
     * A record locally overriding a parked upstream copy says so: the
     * shadow used to be visible only to whoever queried the sync engine, and
     * one can sit there unnoticed for a very long time. Meta.tag is where the
     * record itself tells its reader.
     */
    @Test
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aRecordShadowingAnUpstreamCopySaysSoInMetaTag() throws Exception {
        // saaja's own local decision at a canonical the upstream also publishes
        assertEquals(201, post(base(saaja) + "/CodeSystem", """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"https://allikas.test/cs/vaidlus",
                 "concept":[{"code":"kohalik-otsus"}]}""").statusCode());
        // the upstream publishes DIFFERENT content at the same canonical
        assertEquals(201, post(base(allikas) + "/CodeSystem", """
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"https://allikas.test/cs/vaidlus",
                 "concept":[{"code":"ylemvoim"}]}""").statusCode());
        saaja.syncOnce();

        // The document is a shell -- concepts live natively -- so ownership is
        // proven where concepts answer: the LOCAL code resolves, upstream's
        // does not (REQ-DBO-SYNC-LOCAL-SHADOWING).
        assertEquals(200, get(base(saaja) + "/CodeSystem/$lookup"
                + "?system=https://allikas.test/cs/vaidlus&code=kohalik-otsus").statusCode(),
                "the local override's concept answers");
        assertTrue(get(base(saaja) + "/CodeSystem/$lookup"
                        + "?system=https://allikas.test/cs/vaidlus&code=ylemvoim")
                        .statusCode() >= 400,
                "the parked upstream's concept does not");
        String local = get(base(saaja)
                + "/CodeSystem?url=https://allikas.test/cs/vaidlus").body();
        assertTrue(local.contains("\"system\":\"urn:dbo:sync\"")
                        && local.contains("\"code\":\"shadows\""),
                "and now SAYS it is standing in front of a parked upstream copy: " + local);

        // a record shadowing nothing carries no such tag
        String unshadowed = get(base(saaja)
                + "/CodeSystem?url=https://allikas.test/cs/varvid").body();
        assertFalse(unshadowed.contains("\"code\":\"shadows\""),
                "an ordinary copy is not tagged: " + unshadowed);
    }

    /** The system on the wire resolves, per the ratchet. */
    @Test
    void theHandlingSystemResolvesWhereItIsServedFrom() throws Exception {
        // 200 is the discriminator: the answer for a known code carries no
        // display because the handling codes have none, so the
        // body is not the thing to match -- the unknown code refusing is.
        HttpResponse<String> lookup = get(base(allikas)
                + "/CodeSystem/$lookup?system=urn:dbo:handling&code=mirrored");
        assertEquals(200, lookup.statusCode(), lookup.body());
        HttpResponse<String> unknown = get(base(allikas)
                + "/CodeSystem/$lookup?system=urn:dbo:handling&code=sellist-pole");
        assertTrue(unknown.statusCode() >= 400,
                "an unknown code is refused, which is what makes the 200 above an answer: "
                        + unknown.statusCode());
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
