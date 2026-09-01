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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Another authority's publication is held as published, not refused for being
 * imperfect.
 *
 * <p>A national terminology fails R4 rules this store enforces — a property URI
 * written {@code exclude} rather than as an absolute URI, a property the
 * specification fixes as {@code code} declared {@code string}. Not one of those
 * findings touches a code, a display or the hierarchy: the vocabulary is usable
 * and its declarations are sloppy. Refusing it cannot make the publication
 * correct, and {@code READ_ONLY_HERE} says nobody here may correct it either —
 * so the only thing a refusal changes is that a jurisdiction's clinicians have
 * no diagnosis coding at all.
 *
 * <p>The scoping is the whole point and is asserted here rather than described:
 * the two tenants below differ in ONE word of their declaration, and the same
 * bytes are held by one and refused by the other. Validation is a gate on
 * authorship, and replicated content has no authorship here to gate.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MirroredTerminologyIsHeldIT {

    /**
     * The real defects, reduced: a non-absolute property URI, and
     * {@code concept-properties#synonym} typed {@code string} where the
     * specification fixes it as {@code code}. The concepts are unimpeachable,
     * which is the point.
     */
    private static final String PUBLISHED = """
            {"resourceType":"CodeSystem","status":"active","content":"complete",
             "url":"https://terms.test/CodeSystem/diagnoses","version":"1",
             "name":"Diagnoses",
             "property":[
               {"code":"synonym","uri":"http://hl7.org/fhir/concept-properties#synonym",
                "type":"string"},
               {"code":"exclude","uri":"exclude","type":"string"}],
             "concept":[
               {"code":"A00","display":"Cholera",
                "property":[{"code":"synonym","valueString":"koolera"}]},
               {"code":"A01","display":"Typhoid fever"}]}""";

    private static final String BOUND_VALUE_SET = """
            {"resourceType":"ValueSet","status":"active",
             "url":"https://terms.test/ValueSet/diagnoses-bindable",
             "compose":{"include":[{"system":"https://terms.test/CodeSystem/diagnoses"}]}}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String mirrored;
    static String authoredHere;
    static String replicated;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-replicated");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ReplicatedTerminologyIsHeldIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("carries.json"), spec("carries", "mirrored"));
        Files.writeString(dir.resolve("authors.json"), spec("authors", "projected-config"));
        Files.writeString(dir.resolve("inherits.json"), spec("inherits", "replicated"));
        UntilServed.scan(manager, up -> up.contains("carries") && up.contains("authors")
                && up.contains("inherits"));
        mirrored = manager.baseUrl("carries");
        authoredHere = manager.baseUrl("authors");
        replicated = manager.baseUrl("inherits");
    }

    /** Identical but for the one word this issue is about. */
    private static String spec(String code, String handling) {
        return """
                {"code":"%s","face":"r4",
                 "types":[
                   {"name":"CodeSystem","identity":"canonical","handling":"%s"},
                   {"name":"ValueSet","identity":"canonical","handling":"%s"}]}"""
                .formatted(code, handling, handling);
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

    private static HttpResponse<String> post(String base, String type, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/" + type))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * The refusal this issue exists to change — asserted FIRST, because
     * without it the acceptance below proves only that nothing was wrong.
     */
    @Test
    void configurationThisSystemAuthorsIsStillRefusedForTheSameDefects() throws Exception {
        HttpResponse<String> refused = post(authoredHere, "CodeSystem", PUBLISHED);
        assertEquals(422, refused.statusCode(),
                "a store that stopped refusing its OWN callers' bad declarations would "
                        + "have traded one problem for a worse one: " + refused.body());
        assertTrue(refused.body().contains("property"),
                "and it says which declaration: " + refused.body());
    }

    @Test
    @Proving(DboPromises.TERM_EVERY_TENANT_ANSWERS)
    void anotherAuthoritysPublicationIsHeldAndItsCodesResolve() throws Exception {
        HttpResponse<String> held = post(mirrored, "CodeSystem", PUBLISHED);
        assertEquals(201, held.statusCode(),
                "a vocabulary nobody here may correct is held as published: " + held.body());

        assertEquals(201, post(mirrored, "ValueSet", BOUND_VALUE_SET).statusCode());

        // the acceptance that matters to a clinician: the binding expands
        String expanded = get(mirrored
                + "/ValueSet/$expand?url=https://terms.test/ValueSet/diagnoses-bindable");
        assertTrue(expanded.contains("\"code\":\"A00\"") && expanded.contains("Cholera"),
                "a tenant inheriting this vocabulary can code a diagnosis with it: "
                        + expanded);
    }

    /**
     * A tenant's inherited copy is written by the lane that replicates it, and
     * by nobody else — including a caller holding this very publication.
     *
     * <p>Named here because it is the fact that made this issue's first design
     * a no-op: {@code replicated} content never reaches validation over REST at
     * all, so relaxing validation for it would have changed nothing anybody
     * could observe. The two classifications answer different questions —
     * "who may put this here" and "whose publication is it" — and the second is
     * what validation turns on.
     */
    @Test
    void anInheritedCopyIsNotWritableByACallerAtAll() throws Exception {
        HttpResponse<String> refused = post(replicated, "CodeSystem", PUBLISHED);
        assertEquals(403, refused.statusCode(),
                "read-only-here refuses the caller before validation is ever asked: "
                        + refused.body());
    }

    /**
     * Accepting is not correcting.
     *
     * <p>The document that comes back is the one that was published, with its
     * imperfect declarations intact. A store that quietly tidied them would
     * make its copy of a national vocabulary differ from everyone else's,
     * which in a clinical system is a correctness problem with liability
     * attached — and a private, prettier fork is worse than a faithful copy of
     * an imperfect publication.
     */
    @Test
    void whatIsHeldIsWhatWasPublished() throws Exception {
        post(mirrored, "CodeSystem", PUBLISHED);
        String stored = get(mirrored
                + "/CodeSystem?url=https://terms.test/CodeSystem/diagnoses");
        assertTrue(stored.contains("\"uri\":\"exclude\""),
                "the non-absolute property URI is still there, unfixed: " + stored);
        assertTrue(stored.contains("\"type\":\"string\""),
                "and so is the type the publisher declared: " + stored);
    }
}
