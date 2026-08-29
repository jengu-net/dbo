package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
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
     * The identifier NAMESPACES, which are not code systems and must not be
     * published as ones — a CodeSystem for a namespace that has no codes would
     * be a lie in the shape of a definition.
     *
     * <p>FHIR answers "what is this identifier system" with a NamingSystem, so
     * these resolve as one. They are still named here rather than skipped
     * silently, because the assertion for them is different, not absent: a
     * vocabulary added later and not listed here still has to be a CodeSystem
     * and cannot hide in this set.
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
    static String elementBase;

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
        // the SAME tenant on the newest version, because a promise about a
        // tenant's own vocabulary that only holds on one face is not a promise
        // about the store (#101)
        Files.writeString(dir.resolve("sonavara6.json"), """
                {"code":"sonavara6","fhirVersion":"r6",
                 "audit":{"level":"writes"},
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("sonavara") && up.contains("sonavara6"));
        base = manager.baseUrl("sonavara");
        elementBase = manager.baseUrl("sonavara6");
    }

    /** Every version a tenant can be given, for the promises that are per tenant. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tenants() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("r4", base),
                org.junit.jupiter.params.provider.Arguments.of("r6", elementBase));
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
                // an identifier namespace answers as what it is
                String named = get(base + "/NamingSystem?value=" + system);
                assertTrue(named.contains("\"resourceType\":\"NamingSystem\"")
                                && named.contains(system),
                        "a client met the identifier system " + system + " on the wire and "
                                + "cannot look it up: " + named);
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
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("tenants")
    @Proving(DboPromises.TERM_EVERY_TENANT_ANSWERS)
    void aCodeInAPublishedVocabularyResolvesThroughTheOperationAClientWouldUse(
            String version, String base) throws Exception {
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
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("tenants")
    @Proving(DboPromises.TERM_NATIVE_FORM)
    void theDefinitionSaysWhatItKnowsAndWhereTheCodesAre(String version, String base)
            throws Exception {
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

        // And the same for a definition identified by something other than a
        // url. A NamingSystem has none, so its identity is claimed from the
        // namespace it names — and if that claim is not made, the second
        // bring-up's conditional write matches nothing and quietly writes a
        // second copy of every namespace (#91).
        String namespaces = get(base + "/NamingSystem?value=urn:dbo:run");
        assertEquals(1, countEntries(namespaces, "NamingSystem"),
                "a namespace is one record after two bring-ups, not two: " + namespaces);
    }

    /**
     * Every identifier namespace this face uses, whether or not a response
     * happened to carry one today.
     *
     * <p>The wire scan above only reaches what the trail put there; a namespace
     * used by a surface nobody exercised in this test would pass by not being
     * met. This asks directly.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("identifierNamespaces")
    void everyIdentifierNamespaceIsAnsweredAsANamingSystem(String namespace) throws Exception {
        for (String tenantBase : new String[] {base, elementBase}) {
            String named = get(tenantBase + "/NamingSystem?value=" + namespace);
            assertTrue(named.contains("\"resourceType\":\"NamingSystem\"")
                            && named.contains(namespace),
                    namespace + " is not answerable from " + tenantBase + ": " + named);
            assertTrue(named.contains("\"kind\":\"identifier\""),
                    namespace + " must say what kind of namespace it is: " + named);
        }
    }

    /**
     * The extension this face puts on a served resource has a definition a
     * client can fetch (#91).
     *
     * <p>Asserted rather than assumed, because failing to publish is silent:
     * {@code publishVocabularies} logs a definition this store cannot hold and
     * carries on, and a profile that cannot be snapshotted is refused by the
     * same quiet route. A shell carrying an extension nobody can look up would
     * have looked exactly like success.
     */
    @Test
    void theExtensionThisFacePutsOnAResourceHasADefinition() throws Exception {
        for (String tenantBase : new String[] {base, elementBase}) {
            String found = get(tenantBase
                    + "/StructureDefinition?url=https://dbo.dev/fhir/ext/original-content");
            assertTrue(found.contains("\"resourceType\":\"StructureDefinition\"")
                            && found.contains("\"type\":\"Extension\""),
                    "the original-content extension has no definition in " + tenantBase
                            + ": " + found);
            assertTrue(found.contains("\"expression\":\"CodeSystem\""),
                    "and it says where it may appear: " + found);
        }
    }

    /**
     * The Task a run is rendered as has a definition, not just an example
     * (#91).
     *
     * <p>Its seven dbo-owned systems were each discoverable on their own — a
     * CodeSystem here, a NamingSystem there — and nothing said which element
     * carried which, or that they belonged to one shape at all. A client had
     * to read an example and infer, which is the convention this rule exists
     * to remove.
     *
     * <p>Asserted at the definition rather than on the wire because nothing
     * serves runs yet, and that is exactly why it is worth doing now: once the
     * participation surface ships, this becomes a live API and the shape stops
     * being free to correct.
     */
    @Test
    @Proving(DboPromises.TERM_EVERY_TENANT_ANSWERS)
    void theTaskARunIsRenderedAsHasADefinition() throws Exception {
        for (String tenantBase : new String[] {base, elementBase}) {
            String found = get(tenantBase + "/StructureDefinition?url="
                    + java.net.URLEncoder.encode(
                            "https://dbo.dev/fhir/StructureDefinition/run-as-task",
                            java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(found.contains("\"resourceType\":\"StructureDefinition\"")
                            && found.contains("\"type\":\"Task\""),
                    "a run is served as a Task and the Task has no definition in "
                            + tenantBase + ": " + found);
            assertTrue(found.contains("http://hl7.org/fhir/StructureDefinition/Task"),
                    "and it constrains the domain's own Task rather than inventing a type: "
                            + found);

            // The profile has to name the systems the projection actually
            // puts on the wire. A definition that described a different Task
            // than the one served would be worse than none: it would be
            // discoverable and wrong.
            for (String system : new String[] {"urn:dbo:run", "urn:dbo:correlation"}) {
                assertTrue(found.contains(system),
                        "the profile names " + system + ", which the rendered Task carries: "
                                + found);
            }
            assertTrue(found.contains("\"fixedCode\":\"order\""),
                    "and it fixes what the renderer fixes — every rendered run is an order: "
                            + found);
        }
    }

    /**
     * A run's OUTPUT codes and a run's KEY are different things and no longer
     * share a URI (#91).
     *
     * <p>Nothing serves runs yet, so this is asserted at the definition rather
     * than on the wire — which is the point of doing it now: after the
     * participation surface ships, splitting them would be a change to a live
     * API.
     */
    @Test
    void aRunsOutputCodesHaveTheirOwnSystem() throws Exception {
        String codes = get(base + "/CodeSystem?url=urn:dbo:run:output");
        assertEquals(1, countEntries(codes),
                "the run output codes are published under their own url: " + codes);
        assertEquals(0, countEntries(get(base + "/CodeSystem?url=urn:dbo:run")),
                "and urn:dbo:run is no longer also a code system — it names a run's key");
    }

    static java.util.stream.Stream<String> identifierNamespaces() {
        return IDENTIFIER_NAMESPACES.stream().sorted();
    }

    private static int countEntries(String bundle) {
        return countEntries(bundle, "CodeSystem");
    }

    private static int countEntries(String bundle, String typeName) {
        return bundle.split("\"resourceType\":\"" + typeName + "\"", -1).length - 1;
    }
}
