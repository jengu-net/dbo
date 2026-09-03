package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.fleet.Credentials;
import cloud.jengu.dbo.fleet.FleetReader;
import cloud.jengu.dbo.fleet.FleetService;
import cloud.jengu.dbo.fleet.Node;
import cloud.jengu.dbo.fleet.Reading;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Introductions;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment is read from outside every container, and every answer says
 * which node it came from.
 *
 * <p>Two runtimes in this JVM, on two ports, are two nodes as far as anything
 * over HTTP can tell — and everything here goes over HTTP, because the point
 * of the reader is that it stands outside. A third node nobody started is in
 * the reading too: the node that did not answer is the one an operator opened
 * this for, and a reading that dropped it would answer "which nodes are fine"
 * while looking like it answered "which nodes exist".
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TheFleetIsReadFromOutsideEveryNodeIT {

    private static final String OPS_TOKEN = "ops-token-for-this-deployment";
    private static final String CLIENT = "operaator";

    private static final StepDeclaration VALIDATE_V1 =
            StepDeclaration.of("lab.result.validate", "1.0", "r4");
    private static final StepDeclaration VALIDATE_V2 =
            StepDeclaration.of("lab.result.validate", "2.0", "r4");
    private static final StepDeclaration FILE =
            StepDeclaration.of("lab.result.file", "1.0", "r4").overridableBy("zone");

    static PostgreSQLContainer<?> postgres;
    static final HttpClient http = HttpClient.newHttpClient();
    static ManagedNode left;
    static ManagedNode right;
    static URI nobodyListens;
    static String personHeldKey;

    /** One node: a runtime, its provisioner, and its spec directory. */
    record ManagedNode(String name, TenantRuntimeManager manager,
            LocalDatabasePerTenantProvisioner provisioner, Path dir) {

        URI base() {
            return URI.create("http://127.0.0.1:" + manager.port());
        }

        Node asNode() {
            return new Node(name, base(), OPS_TOKEN);
        }

        void close() {
            manager.close();
            provisioner.close();
        }
    }

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        left = node("vasak", Steps.of(VALIDATE_V1), "esimene");
        right = node("parem", Steps.of(VALIDATE_V2, FILE), "teine", "ilma");
        // a port that was free a moment ago and has nothing behind it
        try (ServerSocket free = new ServerSocket(0)) {
            nobodyListens = URI.create("http://127.0.0.1:" + free.getLocalPort());
        }
        // a credential per tenant the reader is allowed to ask — and none for
        // "ilma", which is the tenant the reading must still name
        for (String code : List.of("esimene")) {
            left.manager().authority(code).ensureClient(CLIENT, code + "-secret", List.of("fleet"));
        }
        right.manager().authority("teine").ensureClient(CLIENT, "teine-secret", List.of("fleet"));

        // work on the left node, held by a person: the row the read exists for
        Runs runs = new Runs(left.manager().runtime("esimene").orElseThrow().engine());
        Run run = runs.pipeline("lab.result", "lab.result.validate");
        personHeldKey = runs.fellThrough(run, Scope.BASELINE, "nobody here takes it").key();
        runs.pipeline("lab.result", "lab.result.validate");
    }

    private static ManagedNode node(String name, Steps installed, String... tenants)
            throws Exception {
        Path dir = Files.createTempDirectory("dbo-fleet-" + name);
        LocalDatabasePerTenantProvisioner provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("TheFleetIsReadFromOutsideEveryNodeIT" + name),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        TenantRuntimeManager manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0,
                null, new TenantRuntimeManager.AuthorityConfig(kek, null),
                FhirVersions.installed(), installed);
        manager.serveRuntimeState(OPS_TOKEN);
        for (String code : tenants) {
            Files.writeString(dir.resolve(code + ".json"), """
                    {"code":"%s","face":"r4","types":[
                      {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                    .formatted(code));
        }
        UntilServed.scan(manager, tenants);
        return new ManagedNode(name, manager, provisioner, dir);
    }

    @AfterAll
    void down() {
        if (left != null) {
            left.close();
        }
        if (right != null) {
            right.close();
        }
    }

    private static Reading read(FleetReader.RunFilter filter) {
        return new FleetReader(
                List.of(left.asNode(), right.asNode(), new Node("kadunud", nobodyListens, OPS_TOKEN)),
                Credentials.of(Map.of(
                        "esimene", new Credentials.Credential(CLIENT, "esimene-secret"),
                        "teine", new Credentials.Credential(CLIENT, "teine-secret"))),
                Duration.ofSeconds(5)).read(filter);
    }

    private static Reading.NodeReading node(Reading reading, String name) {
        return reading.nodes().stream().filter(n -> n.node().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("node '" + name + "' is missing from the "
                        + "reading, and a missing node is the one failure this exists to "
                        + "prevent: " + reading));
    }

    private static Reading.TenantReading tenant(Reading.NodeReading node, String code) {
        return node.tenants().stream().filter(t -> t.tenant().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("tenant '" + code + "' is missing under "
                        + node.node() + ": " + node));
    }

    @Test
    @DisplayName("every node is in the reading, the one nobody started as unreachable")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void everyNodeIsInTheReading() {
        Reading reading = read(FleetReader.RunFilter.ANY);

        assertEquals(List.of("vasak", "parem", "kadunud"),
                reading.nodes().stream().map(Reading.NodeReading::node).toList(),
                "the reading has a different set of nodes than the reader was given");
        assertEquals(Reading.Outcome.UNREACHABLE, node(reading, "kadunud").outcome());
        assertTrue(node(reading, "kadunud").detail().contains(String.valueOf(nobodyListens.getPort())),
                "an unreachable node says where it was looked for: "
                        + node(reading, "kadunud").detail());
        assertEquals(Reading.Outcome.ANSWERED, node(reading, "vasak").outcome());
        assertEquals(Reading.Outcome.ANSWERED, node(reading, "parem").outcome());
    }

    @Test
    @DisplayName("a run is reported under the node that holds it, as an envelope and never a payload")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void workIsLabelledWithItsNode() {
        Reading reading = read(FleetReader.RunFilter.heldByAPerson());

        Reading.TenantReading esimene = tenant(node(reading, "vasak"), "esimene");
        assertEquals(Reading.Outcome.ANSWERED, esimene.outcome(), esimene.detail());
        assertEquals(1, esimene.runs().size(),
                "held by a person was asked for, and there is one such run: " + esimene.runs());
        Map<String, Object> envelope = esimene.runs().get(0);
        assertEquals(personHeldKey, envelope.get("key"));
        assertEquals("person", envelope.get("holder"));
        assertEquals("lab.result.validate", envelope.get("step"));
        assertFalse(envelope.containsKey("inputs") || envelope.containsKey("item")
                        || envelope.containsKey("payload"),
                "the fleet door disclosed more than the envelope: " + envelope);

        Reading.TenantReading teine = tenant(node(reading, "parem"), "teine");
        assertEquals(Reading.Outcome.ANSWERED, teine.outcome(), teine.detail());
        assertTrue(teine.runs().isEmpty(),
                "the right node reported work that lives on the left: " + teine.runs());

        // any, and the second run appears where the first did
        assertEquals(2, tenant(node(read(FleetReader.RunFilter.ANY), "vasak"), "esimene")
                .runs().size());
    }

    @Test
    @DisplayName("a tenant the reader holds no credential for is named and not asked")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void aTenantWithoutACredentialIsNamedNotSkipped() {
        Reading.TenantReading ilma = tenant(node(read(FleetReader.RunFilter.ANY), "parem"), "ilma");

        assertEquals("serving", ilma.state(), "the node says what it is doing about the tenant "
                + "whether or not the reader may ask it anything");
        assertEquals(Reading.Outcome.NO_CREDENTIAL, ilma.outcome());
        assertTrue(ilma.runs().isEmpty());
    }

    @Test
    @DisplayName("the network map is the union of the nodes' inventories, by step and version, "
            + "and nothing was declared to make it")
    @Proving(DboPromises.PROC_NETWORK_MAP)
    void theMapIsOneAnswerAcrossNodes() {
        Reading reading = read(FleetReader.RunFilter.ANY);

        Map<String, Map<String, List<String>>> map = reading.map();
        assertEquals(Map.of("1.0", List.of("vasak"), "2.0", List.of("parem")),
                map.get("lab.result.validate"),
                "one step at two versions on two nodes is two rows under it — a rolling "
                        + "upgrade is exactly when somebody asks: " + map);
        assertEquals(Map.of("1.0", List.of("parem")), map.get("lab.result.file"), map.toString());
        assertEquals(2, map.size(), "the map names steps no node has installed: " + map);

        // Descriptive, never a declaration: reading the fleet introduced no
        // step into any tenant. The introduction door would have refused a
        // step both nodes carry as a collision, which is why the inventory
        // travels another way.
        for (ManagedNode node : List.of(left, right)) {
            for (String code : node.manager().tenantStates().stream()
                    .map(cloud.jengu.dbo.tenant.TenantState::code).toList()) {
                assertTrue(new Introductions(node.manager().runtime(code).orElseThrow().engine(),
                                Steps.of()).all().isEmpty(),
                        "reading the map introduced a step into " + code + " on " + node.name());
            }
        }
    }

    @Test
    @DisplayName("the node's inventory is behind the deployment's token, like its tenants are")
    @Proving(DboPromises.PROC_NETWORK_MAP)
    void theInventoryIsTheDeploymentsToAsk() throws Exception {
        URI catalogue = left.base().resolve("/runtime/catalogue");
        HttpResponse<String> unnamed = http.send(HttpRequest.newBuilder(catalogue)
                .header("Authorization", "Bearer some-other-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unnamed.statusCode());
        assertFalse(unnamed.body().contains("lab.result"),
                "a refusal that listed the inventory is a disclosure: " + unnamed.body());

        HttpResponse<String> named = http.send(HttpRequest.newBuilder(catalogue)
                .header("Authorization", "Bearer " + OPS_TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, named.statusCode(), named.body());
        assertTrue(named.body().contains("\"id\":\"lab.result.validate\""), named.body());
        assertTrue(named.body().contains("\"version\":\"1.0\""), named.body());
        assertNull(RecordWireAccess.field(named.body(), "tenants"),
                "the inventory answered with tenants, so the two questions are one door");
    }

    @Test
    @DisplayName("as a service, it reads the fleet when asked and holds nothing between asks")
    @Proving(DboPromises.OPS_FLEET_IS_READ_FROM_OUTSIDE)
    void theServiceReadsAfreshOnEveryAsk() throws Exception {
        FleetReader reader = new FleetReader(List.of(left.asNode(), right.asNode()),
                Credentials.of(Map.of(
                        "esimene", new Credentials.Credential(CLIENT, "esimene-secret"),
                        "teine", new Credentials.Credential(CLIENT, "teine-secret"))),
                Duration.ofSeconds(5));
        try (FleetService service = new FleetService(reader, "127.0.0.1", 0, "fleet-token")) {
            service.start();
            URI door = URI.create("http://127.0.0.1:" + service.port() + "/fleet?process=lab.fresh");

            HttpResponse<String> unnamed = http.send(HttpRequest.newBuilder(door).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unnamed.statusCode());
            assertFalse(unnamed.body().contains("esimene") || unnamed.body().contains("teine"),
                    "a refusal that named a tenant is the disclosure the token prevents: "
                            + unnamed.body());

            String before = ask(door).body();
            assertTrue(runsOf(before, "parem", "teine").isEmpty(),
                    "nothing under lab.fresh existed yet: " + before);

            // work appears on a node between two asks, and the second ask
            // sees it — because the service asked again rather than
            // answering from anything it kept
            Runs runs = new Runs(right.manager().runtime("teine").orElseThrow().engine());
            String key = runs.pipeline("lab.fresh", "lab.result.file").key();

            String after = ask(door).body();
            List<?> fresh = runsOf(after, "parem", "teine");
            assertEquals(1, fresh.size(), "the service answered from a reading it kept: " + after);
            assertEquals(key, ((Map<?, ?>) fresh.get(0)).get("key"));
        }
    }

    private static HttpResponse<String> ask(URI door) throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(door)
                .header("Authorization", "Bearer fleet-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        return answer;
    }

    /** The runs under one tenant of one node, walked out of the service's JSON. */
    private static List<?> runsOf(String json, String nodeName, String tenantCode) {
        Object nodes = RecordWireAccess.field(json, "nodes");
        for (Object node : (List<?>) nodes) {
            Map<?, ?> n = (Map<?, ?>) node;
            if (!nodeName.equals(n.get("node"))) {
                continue;
            }
            for (Object tenant : (List<?>) n.get("tenants")) {
                Map<?, ?> t = (Map<?, ?>) tenant;
                if (tenantCode.equals(t.get("tenant"))) {
                    return (List<?>) t.get("runs");
                }
            }
        }
        throw new AssertionError(nodeName + "/" + tenantCode + " is missing from " + json);
    }

    /** The wire's own reader, so the test decodes with what the store encodes. */
    private static final class RecordWireAccess {
        static Object field(String json, String name) {
            Object read = cloud.jengu.dbo.core.wire.RecordWire.read(json);
            return read instanceof Map<?, ?> map ? map.get(name) : null;
        }
    }
}
