package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the store advertises and what the store accepts are the same thing
 * (REQ-DBO-SRCH-HONEST-CAPABILITY, #104).
 *
 * <p>The promise is usually read one way — do not announce what would be
 * refused — and it holds in both. A store that hides what it accepts is lying
 * about itself just as much, and that was the live case: every type owned by a
 * lane was advertised without {@code create} while the engine wrote it
 * happily, so a zone's CodeSystem said "read-only" to a loader that was
 * successfully posting to it.
 *
 * <p>One type, one declaration, one word different per tenant. Anything that
 * makes the advertised answer and the accepted answer disagree fails here,
 * whichever side moved.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityMatchesWhatIsAcceptedIT {

    /** Every classification a tenant may declare, as the only variable. */
    private static final String[] HANDLINGS = {
            "operational", "projected-config", "mirrored", "replicated", "audit", "ephemeral"};

    private static final Pattern CODE_SYSTEM_RESOURCE = Pattern.compile(
            "\\{\"type\":\"CodeSystem\".*?\\}(?=,\\{\"type\"|\\])", Pattern.DOTALL);

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static final Map<String, String> BASES = new LinkedHashMap<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-capability");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("CapabilityMatchesWhatIsAcceptedIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        for (String handling : HANDLINGS) {
            String code = "h" + handling.replace("-", "");
            Files.writeString(dir.resolve(code + ".json"), """
                    {"code":"%s","fhirVersion":"r4",
                     "types":[{"name":"CodeSystem","identity":"canonical","handling":"%s"}]}"""
                    .formatted(code, handling));
        }
        UntilServed.scan(manager, up -> up.size() >= HANDLINGS.length);
        for (String handling : HANDLINGS) {
            BASES.put(handling, manager.baseUrl("h" + handling.replace("-", "")));
        }
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"operational", "projected-config", "mirrored", "replicated",
            "audit", "ephemeral"})
    @Proving(DboPromises.SRCH_HONEST_CAPABILITY)
    void whatIsAdvertisedIsWhatIsAccepted(String handling) throws Exception {
        String base = BASES.get(handling);
        String metadata = get(base + "/metadata");
        Matcher matcher = CODE_SYSTEM_RESOURCE.matcher(metadata);
        assertTrue(matcher.find(), handling + ": CodeSystem is not in the statement at all");
        String declared = matcher.group();
        boolean advertisesCreate = declared.contains("\"code\":\"create\"");

        HttpResponse<String> written = http.send(
                HttpRequest.newBuilder(URI.create(base + "/CodeSystem"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"CodeSystem","status":"active",
                                 "content":"complete","name":"Probe",
                                 "url":"https://terms.test/CodeSystem/probe","version":"1",
                                 "concept":[{"code":"x","display":"X"}]}""")).build(),
                HttpResponse.BodyHandlers.ofString());
        boolean accepted = written.statusCode() / 100 == 2;

        assertEquals(advertisesCreate, accepted,
                handling + ": the statement says create=" + advertisesCreate
                        + " and the store answered " + written.statusCode()
                        + ". A store may refuse this write or offer it; what it may not do "
                        + "is disagree with itself.\n  statement: " + declared
                        + "\n  answer:    " + written.body());
    }

    private static String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
