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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reshape aimed at part of a profile's stock, by the expression a search
 * takes.
 *
 * <p>Converging everything below the bound is the right default and is what a
 * background sweep wants. It is not what an <b>admission gate</b> wants, and
 * that turns out to be a different question: a gate holds a tenant out of
 * service on a narrow condition — stock of a shape somebody waits on and in a
 * state somebody waits in — and it has to be able to clear only the condition
 * it measured. Without an aim it would have to converge the whole profile,
 * and for a clinic with years of filed orders that is a longer outage than
 * the narrowing exists to avoid.
 *
 * <p>The filter is compiled by the face that serves the searches, not parsed
 * again here. That is the property these tests are really about: one
 * expression counts the stock and converts it, so a gate cannot clear a
 * condition it never measured. An expression re-implemented to agree is an
 * expression that can stop agreeing.
 *
 * <p>And nothing is ignored. A parameter this store cannot honour is refused
 * by name before a single object is converted, because a dropped filter on a
 * read shows somebody too much, while a dropped filter here writes to
 * everything it was meant to exclude and then reports success.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AReshapeConvergesOnlyWhatItWasAimedAtIT {

    private static final String SHAPE = "https://sihtmark.example/StructureDefinition/order";
    private static final String STATES = "https://sihtmark.example/state";

    private static final String MAP = """
            {"resourceType":"StructureMap",
             "url":"https://sihtmark.example/StructureMap/order-2-to-3","version":"1.0.0",
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
                             "transform":"copy","parameter":[{"valueId":"m"}]}]}]}]}""";

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String base;
    static String adminBase;
    static String waitedOn;
    static String alsoWaitedOn;
    static String coldHistory;
    private static volatile String cachedToken;

    static SharedTenants.Tenant tenant;

    @BeforeAll
    void up() throws Exception {
        // Shared. It reshapes only what it aimed at, which is the whole
        // claim — a run that converged somebody else's stock would be the
        // very bug this class looks for, so sharing is a test of it.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_RESHAPE);
        base = tenant.fhir();
        adminBase = tenant.base() + "/admin";

        assertEquals(201, post("/StructureDefinition", shape("2.0.0")).statusCode());

        // Stock written while the pack stood at 2.0.0: two orders somebody is
        // waiting on, and one that is finished and can converge afterwards
        // behind an open door.
        waitedOn = idOf(post("/Basic", order("active")));
        alsoWaitedOn = idOf(post("/Basic", order("draft")));
        coldHistory = idOf(post("/Basic", order("completed")));

        // The pack advances, and ships the converter for the hop beside it.
        assertTrue(put("/StructureDefinition?url="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8),
                shape("3.0.0")).statusCode() < 300);
        assertEquals(201, post("/StructureMap", MAP.formatted(SHAPE, SHAPE)).statusCode());
    }

    @Test
    @Order(1)
    @DisplayName("the filter that counts the stock is the filter that converts it, so a gate "
            + "measures and clears one condition rather than two that can drift")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void oneExpressionCountsAndConverts() throws Exception {
        String aim = "code=" + URLEncoder.encode(STATES + "|active", StandardCharsets.UTF_8);

        // What the gate measures, through the search door.
        String counted = get("/Basic?_shape-below="
                + URLEncoder.encode(SHAPE + "|3", StandardCharsets.UTF_8)
                + "&" + aim + "&_summary=count").body();
        assertTrue(counted.contains("\"total\":1"),
                "the narrowed stock is not the order somebody waits on: " + counted);

        // And what the same expression converges, through the reshape door.
        String run = reshape("&" + aim);
        assertTrue(run.contains("\"converted\":1"),
                "the reshape converged something other than what was counted: " + run);
        assertTrue(stampOf(waitedOn).contains("3.0.0"), "the active order is at the target");
    }

    @Test
    @Order(2)
    @DisplayName("stock outside the aim is untouched, which is the point: a gate that "
            + "converged the whole profile would shut the clinic for longer than it had to")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void whatWasNotAimedAtIsLeftAlone() throws Exception {
        assertTrue(stampOf(coldHistory).contains("2.0.0"),
                "the finished order was converted too, so the narrowing bought nothing");
        assertTrue(stampOf(alsoWaitedOn).contains("2.0.0"),
                "an order outside the aim was converted, so the aim selected more than "
                        + "it named");

        // And they converge afterwards, behind an open door, by the same verb
        // asked without an aim.
        String sweep = reshape("");
        assertTrue(sweep.contains("\"converted\":2"), sweep);
        assertTrue(stampOf(coldHistory).contains("3.0.0"), "the sweep left it behind");
    }

    @Test
    @Order(3)
    @DisplayName("a parameter the store cannot honour is refused by name before anything is "
            + "converted, never dropped — a dropped filter here writes to what it excluded")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void anUnsupportedParameterIsRefusedByName() throws Exception {
        HttpResponse<String> unknown = adminResponse("/reshape?type=Basic&profile="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8)
                + "&target=3&nosuchparameter=x");
        assertEquals(400, unknown.statusCode(),
                "an unsupported parameter was accepted, so the caller believes it converged "
                        + "the narrowed set: " + unknown.body());
        assertTrue(unknown.body().contains("nosuchparameter"),
                "the refusal does not name the parameter, so the caller cannot act on it: "
                        + unknown.body());
    }

    @Test
    @Order(4)
    @DisplayName("a parameter that shapes a result is refused too, because a reshape has no "
            + "result to shape and its walk is steered by its own parameters")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void aResultShapingParameterIsRefused() throws Exception {
        for (String shaping : new String[] {"_count=5", "_sort=code", "_summary=count",
                "_elements=code", "_shape-below=" + URLEncoder.encode(SHAPE + "|2",
                        StandardCharsets.UTF_8)}) {
            HttpResponse<String> refused = adminResponse("/reshape?type=Basic&profile="
                    + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8)
                    + "&target=3&" + shaping);
            assertEquals(400, refused.statusCode(),
                    shaping + " was accepted by a verb with no result to shape: "
                            + refused.body());
        }
    }

    @Test
    @Order(5)
    @DisplayName("the hand-back lane takes the same aim, because a converter that is not this "
            + "store's wants the active page rather than the first page of a decade")
    @Proving(DboPromises.SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING)
    void theClaimLaneIsAimedTheSameWay() throws Exception {
        // Put one order back below the bound so there is narrowed stock to
        // claim, and leave a second one behind it that the aim excludes.
        String held = idOf(post("/Basic", order("active")));
        String excluded = idOf(post("/Basic", order("completed")));
        assertTrue(stampOf(held).contains("3.0.0"), "written under the current pack");

        String wide = adminResponse("/reshape/claim?type=Basic&profile="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8) + "&target=9").body();
        assertTrue(wide.contains(held) && wide.contains(excluded),
                "an unaimed claim should hand out both: " + wide);

        String aimed = adminResponse("/reshape/claim?type=Basic&profile="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8) + "&target=9&code="
                + URLEncoder.encode(STATES + "|active", StandardCharsets.UTF_8)).body();
        assertTrue(aimed.contains(held),
                "the aimed claim did not hand out the stock it was aimed at: " + aimed);
        assertTrue(!aimed.contains(excluded),
                "the aimed claim handed out stock outside the aim, so a platform-side "
                        + "converter claims the first page of a decade: " + aimed);
    }

    // ---------------------------------------------------------- plumbing

    private static String shape(String version) {
        return """
                {"resourceType":"StructureDefinition","url":"%s","version":"%s",
                 "name":"ShapeOrder","status":"active","kind":"resource","abstract":false,
                 "type":"Basic",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Basic",
                 "derivation":"constraint",
                 "differential":{"element":[
                   {"id":"Basic.code","path":"Basic.code","min":1}]}}"""
                .formatted(SHAPE, version);
    }

    private static String order(String state) {
        return """
                {"resourceType":"Basic",
                 "code":{"coding":[{"system":"%s","code":"%s"}]},
                 "meta":{"profile":["%s"]}}""".formatted(STATES, state, SHAPE);
    }

    private static String stampOf(String id) throws Exception {
        return get("/Basic/" + id).body();
    }

    private static String reshape(String aim) throws Exception {
        return adminResponse("/reshape?type=Basic&profile="
                + URLEncoder.encode(SHAPE, StandardCharsets.UTF_8) + "&target=3" + aim).body();
    }

    private static String idOf(HttpResponse<String> created) {
        assertEquals(201, created.statusCode(), created.body());
        return Extracted.field(created.body(), "id");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/fhir+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private static HttpResponse<String> adminResponse(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(adminBase + path))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        if (cachedToken != null) {
            return cachedToken;
        }
        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(tenant.bootstrapSecret(),
                        StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(
                                URI.create(tenant.base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        cachedToken = Extracted.tokenIn(body);
        return cachedToken;
    }
}
