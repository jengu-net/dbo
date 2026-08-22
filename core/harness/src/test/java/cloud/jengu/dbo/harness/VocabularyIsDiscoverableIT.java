package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer learns the domain's API and nothing else (#91).
 *
 * <p>dbo owns concepts FHIR has no word for — what a run's holder is, what an
 * audited interaction was — and minting a system for them is how the domain is
 * meant to be extended. What is not allowed is a client meeting one and having
 * nowhere to look it up. So: every {@code urn:dbo:} coding system that crosses
 * the wire is fetchable from the same tenant that served it, as a CodeSystem,
 * the way any implementation guide's vocabulary is discovered.
 *
 * <p>The ratchet reads the wire rather than the source: whatever a response
 * actually carries is what must resolve, so a vocabulary added later is caught
 * by this test rather than by a reviewer.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VocabularyIsDiscoverableIT {

    /** Every dbo system a response carries, wherever it sits. */
    private static final Pattern DBO_SYSTEM = Pattern.compile("\"system\":\"(urn:dbo:[^\"]+)\"");

    /**
     * The identifier NAMESPACES, which are not code systems and are not
     * published yet — named here rather than skipped silently, because a
     * ratchet with an invisible exception is not a ratchet.
     *
     * <p>FHIR answers "what is this identifier system" with a NamingSystem,
     * and R4's NamingSystem has no url, so it cannot take the canonical
     * identity a definition is fetched by. That wants a per-version answer
     * and is the remaining half of #91 — anything NOT on this list must
     * resolve as a CodeSystem, so a vocabulary added later cannot hide here.
     */
    private static final Set<String> IDENTIFIER_NAMESPACES = Set.of(
            "urn:dbo:run", "urn:dbo:correlation", "urn:dbo:executor",
            "urn:dbo:auth:client-id");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-vocab");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("VocabularyIsDiscoverableIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        // audit on, and the tenant declares NOTHING about CodeSystem — the
        // face's vocabulary is served because dbo publishes it, not because
        // the tenant remembered to ask for it
        Files.writeString(dir.resolve("sonavara.json"), """
                {"code":"sonavara","fhirVersion":"r4",
                 "audit":{"level":"writes"},
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("sonavara"));
        base = manager.baseUrl("sonavara");
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

    private static String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void everyDboCodingSystemOnTheWireIsFetchableFromTheSameTenant() throws Exception {
        // make the trail say something, so the wire carries dbo's vocabulary
        http.send(HttpRequest.newBuilder(URI.create(base + "/Patient"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"resourceType\":\"Patient\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        Set<String> met = new LinkedHashSet<>();
        Matcher matcher = DBO_SYSTEM.matcher(get(base + "/AuditEvent"));
        while (matcher.find()) {
            met.add(matcher.group(1));
        }
        assertTrue(met.contains("urn:dbo:audit"),
                "the trail must carry dbo's own audit vocabulary, or this test proves "
                        + "nothing: " + met);

        for (String system : met) {
            if (IDENTIFIER_NAMESPACES.contains(system)) {
                continue;
            }
            String found = get(base + "/CodeSystem?url=" + system);
            assertTrue(found.contains("\"resourceType\":\"CodeSystem\"")
                            && found.contains(system),
                    "a client met " + system + " on the wire and cannot look it up: " + found);
        }
    }

    /**
     * Fetchable is not resolvable (#98).
     *
     * <p>#91 published the definitions and stopped there: written the ordinary
     * FHIR way a CodeSystem is stored whole and its concepts never reach the
     * native form, so the operations a client would actually use to resolve a
     * code answered nothing while the document sat there looking complete.
     * A vocabulary you can read and cannot ask about is half a vocabulary.
     */
    @Test
    void aCodeInAPublishedVocabularyResolvesThroughTheOperationAClientWouldUse()
            throws Exception {
        String looked = get(base
                + "/CodeSystem/$lookup?system=urn:dbo:run:holder&code=PERSON");
        assertTrue(looked.contains("Parameters"),
                "$lookup must answer for dbo's own vocabulary: " + looked);

        String validated = get(base
                + "/CodeSystem/$validate-code?system=urn:dbo:run:holder&code=PERSON");
        assertTrue(validated.contains("\"value\":true") || validated.contains("\"valueBoolean\":true"),
                "a code dbo published must validate as its own: " + validated);

        String absent = get(base
                + "/CodeSystem/$validate-code?system=urn:dbo:run:holder&code=NOT-A-HOLDER");
        assertTrue(absent.contains("\"value\":false") || absent.contains("\"valueBoolean\":false"),
                "and one it never published must not: " + absent);
    }

    /**
     * A definition says what it knows and what it does not — and where the
     * codes actually are (#98).
     *
     * <p>This asserted `content: complete` with the codes inline until the
     * vocabulary started going through ingest, which is what made $lookup
     * work. The stored form is now a shell — `not-present`, a count, and the
     * original content mode on an extension — which is what `not-present`
     * means in FHIR: the concepts are not in this document, ask the
     * terminology server. The old assertion was pinning the broken shape.
     */
    @Test
    void theDefinitionSaysWhatItKnowsAndWhereTheCodesAre() throws Exception {
        // closed: dbo knows every holder there is, and says how many
        String holder = get(base + "/CodeSystem?url=urn:dbo:run:holder");
        assertTrue(holder.contains("\"content\":\"not-present\"") && holder.contains("\"count\":4"),
                "the shell says the concepts live elsewhere, and how many: " + holder);
        assertTrue(holder.contains("\"valueString\":\"complete\""),
                "and records that the vocabulary it was published from was complete: " + holder);

        // open: the codes are a module's or an application's, and the
        // definition declines to invent an enumeration it does not have
        String audit = get(base + "/CodeSystem?url=urn:dbo:audit");
        assertTrue(audit.contains("\"content\":\"not-present\""), audit);
        assertFalse(audit.contains("\"valueString\":\"complete\""),
                "an open vocabulary must not claim it was ever complete: " + audit);
    }

    @Test
    void publishingIsIdempotentAcrossBringUps() throws Exception {
        String spec = Files.readString(dir.resolve("sonavara.json"));
        assertEquals(1, countEntries(get(base + "/CodeSystem?url=urn:dbo:run:holder")),
                "one definition after the first bring-up");

        Files.delete(dir.resolve("sonavara.json"));
        UntilServed.scan(manager, up -> !up.contains("sonavara"));
        Files.writeString(dir.resolve("sonavara.json"), spec);
        UntilServed.scan(manager, up -> up.contains("sonavara"));
        base = manager.baseUrl("sonavara");

        String after = get(base + "/CodeSystem?url=urn:dbo:run:holder");
        assertEquals(1, countEntries(after),
                "a second bring-up rewrites the same record rather than adding one: " + after);
    }

    private static int countEntries(String bundle) {
        return bundle.split("\"resourceType\":\"CodeSystem\"", -1).length - 1;
    }
}
