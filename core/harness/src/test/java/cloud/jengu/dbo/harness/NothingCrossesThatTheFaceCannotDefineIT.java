package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client learns the domain's API and nothing else, so anything of this
 * store's own that reaches one has to be discoverable from the tenant that
 * sent it.
 *
 * <p>Minting a vocabulary is not the problem — this store owns concepts FHIR
 * has no word for, and an extension is how a standard is meant to be extended.
 * The problem is a client meeting {@code urn:dbo:run:holder} in a document and
 * having nowhere to look it up: at that point its only recourse is to read
 * this repository's source, and every such value is documentation somebody has
 * to write, read and keep true — which is exactly the cost a face exists to
 * remove.
 *
 * <p>So the rule is not "never use a vocabulary of our own". It is that the
 * client discovers it the way it discovers any implementation guide's: by
 * fetching the resource that defines it, from the tenant it is talking to.
 * Codes resolve as a {@code CodeSystem}; an identifier's namespace resolves as
 * a {@code NamingSystem}, because that is what FHIR uses to say what a
 * {@code system} means.
 *
 * <p>This drives the surfaces a client actually uses and collects every
 * {@code urn:dbo:} it is handed, rather than reading the sources for the ones
 * we remember minting. A value that reaches the wire by a path nobody thought
 * about is precisely the one that would otherwise go unnoticed.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NothingCrossesThatTheFaceCannotDefineIT {

    private static final Pattern SYSTEM = Pattern.compile("urn:dbo:[A-Za-z0-9:_-]+");
    private static final String TENANT = "avatud";

    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String token;

    @BeforeAll
    void up() throws Exception {
        Path dir = Files.createTempDirectory("dbo-face-api");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("NothingCrossesThatTheFaceCannotDefineIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4",
                 "policies":{"audit":{"level":"writes"}},
                 "types":[
                   {"name":"Patient","identity":"internal","handling":"operational"},
                   {"name":"Observation","identity":"internal","handling":"operational"},
                   {"name":"Task","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        base = manager.baseUrl(TENANT);

        String form = "grant_type=client_credentials&client_id=tenant-bootstrap&client_secret="
                + URLEncoder.encode(provisioner.bootstrapClientSecret(TENANT),
                        StandardCharsets.UTF_8);
        HttpResponse<String> issued = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + manager.port()
                                + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(issued.body());
        assertTrue(m.find(), "the fixture could not authenticate: " + issued.body());
        token = m.group(1);
    }

    @AfterAll
    void down() {
        manager.close();
        provisioner.close();
    }

    @Test
    @DisplayName("every system of our own that reaches a client resolves from the tenant "
            + "that sent it")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void whatCrossesCanBeLookedUp() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        HttpResponse<String> created = post("/Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Avatud\"}]}");
        assertEquals(201, created.statusCode(), created.body());
        String id = created.headers().firstValue("Location").orElseThrow()
                .replaceAll(".*/", "");

        answers.put("create", created.body());
        answers.put("read", get("/Patient/" + id).body());
        answers.put("history", get("/Patient/" + id + "/_history").body());
        answers.put("search", get("/Patient?_count=10").body());
        answers.put("capability", get("/metadata").body());
        answers.put("audit", get("/AuditEvent?_count=20").body());
        answers.put("observation search", get("/Observation?_count=5").body());
        answers.put("task search", get("/Task?_count=5").body());

        TreeSet<String> crossed = new TreeSet<>();
        answers.forEach((where, body) -> {
            Matcher found = SYSTEM.matcher(body == null ? "" : body);
            while (found.find()) {
                crossed.add(found.group());
            }
        });

        assertTrue(crossed.contains("urn:dbo:handling"),
                "nothing of ours crossed at all, which means this test drove no surface "
                        + "that carries one and is guarding nothing: " + crossed);

        // The resolver first, on a system nobody minted: one that answered
        // "yes" to everything would report every vocabulary discoverable,
        // including the one that is not.
        assertTrue(!resolves("urn:dbo:ei-ole-olemas"),
                "the lookup claims to resolve a system nobody ever minted, so its "
                        + "answers below mean nothing");

        TreeSet<String> undiscoverable = new TreeSet<>();
        for (String system : crossed) {
            if (!resolves(system)) {
                undiscoverable.add(system);
            }
        }

        assertTrue(undiscoverable.isEmpty(),
                "a client was handed a system it cannot look up from this tenant, so its "
                        + "only recourse is to read this store's source:\n  "
                        + String.join("\n  ", undiscoverable)
                        + "\nPublish each from the face — codes as a CodeSystem, an "
                        + "identifier's namespace as a NamingSystem — or stop sending it.");
    }

    @Test
    @DisplayName("and the statement does not announce them, which was decided rather than "
            + "overlooked")
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void theStatementInventsNoSlotForOurOwnVocabulary() throws Exception {
        String statement = get("/metadata").body();

        // Reading it is the check: if a list of what this server mints ever
        // appears here, a client has to learn OUR extension in order to
        // discover that there is nothing else of ours to learn — which is the
        // leak the rest of this test exists to close, wearing a helpful face.
        //
        // Failing here does not mean the addition is wrong. It means the
        // paragraph in the-fhir-face.md that says the statement stays quiet is
        // now describing something else, and the FHIR-shaped answers — an
        // implementation guide, or rest.resource.profile on the carrying types
        // — are what to reach for instead of a list.
        Matcher ours = SYSTEM.matcher(statement);
        TreeSet<String> announced = new TreeSet<>();
        while (ours.find()) {
            announced.add(ours.group());
        }
        assertTrue(announced.isEmpty(),
                "the CapabilityStatement now carries " + announced + ". FHIR has no slot "
                        + "for the systems a server mints, so this is an extension of ours "
                        + "that a client must learn before it can discover we have nothing "
                        + "else it must learn. The decision is recorded in "
                        + "docs/arc42-008-crosscutting/the-fhir-face.md — change it there "
                        + "and here together, or publish through a guide instead.");

        // And the statement is real, so the assertion above is about a
        // document rather than about an empty string.
        assertTrue(statement.contains("CapabilityStatement"),
                "there is no statement to be quiet: " + statement);
    }

    /** Discoverable means the tenant answers for it, in whichever shape fits. */
    private static boolean resolves(String system) throws Exception {
        String encoded = URLEncoder.encode(system, StandardCharsets.UTF_8);
        return get("/CodeSystem?url=" + encoded).body().contains("\"" + system + "\"")
                || get("/NamingSystem?value=" + encoded).body().contains("\"" + system + "\"");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
