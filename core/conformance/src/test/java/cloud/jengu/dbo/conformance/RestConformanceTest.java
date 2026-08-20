package cloud.jengu.dbo.conformance;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static cloud.jengu.dbo.conformance.Conformance.Area.CONTENT;
import static cloud.jengu.dbo.conformance.Conformance.Area.ERRORS;
import static cloud.jengu.dbo.conformance.Conformance.Area.INSTANCE;
import static cloud.jengu.dbo.conformance.Conformance.Area.SEARCH;
import static cloud.jengu.dbo.conformance.Conformance.Area.SYSTEM;
import static cloud.jengu.dbo.conformance.Conformance.Area.TYPE;

/**
 * Drives the FHIR RESTful rules against the real HTTP surface and writes what
 * it saw.
 *
 * <p>This is not the proof suite — the harness proves behaviour and fails when
 * behaviour is wrong. This one <em>reports</em>. It passes as long as it
 * managed to observe the server; the observations themselves land in the
 * committed report, so a change in what dbo conforms to arrives as a diff
 * rather than as a red build over a boundary that was drawn on purpose.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestConformanceTest {

    private static final String EID = "https://ee.ee/eid";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource pg;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).build();

    /**
     * One server per version, wired the way a tenant is (#58): the version is
     * resolved by its code and asked for what a bring-up asks for. So a report
     * says what the store actually serves rather than what a rig assembled for
     * the occasion — and a third version is a line in a list.
     */
    private final java.util.Map<String, FhirHttpServer> servers = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> bases = new java.util.LinkedHashMap<>();

    @BeforeAll
    void up() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());

        for (String code : List.of("r4", "r5", "r6")) {
            serve(code);
        }
    }

    @AfterAll
    void down() {
        servers.values().forEach(FhirHttpServer::close);
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** Each version's own store, on its own port, over one database. */
    private void serve(String code) {
        int port = freePort();
        String base = "http://127.0.0.1:" + port + "/fhir";
        FhirVersion version = FhirVersions.installed().require(code);
        FhirVersion.ForTypes declared = version.forTypes(List.of(
                FhirTypeConfig.identifier("Patient", EID),
                FhirTypeConfig.internal("Observation")));
        FhirStoreFacade store = declared.store(
                new PgObjectStore(pg, declared.registrations()), base);
        servers.put(code, new FhirHttpServer(store, null, "127.0.0.1", port, "/fhir"));
        bases.put(code, base);
    }

    @Test
    void reportR4() throws Exception {
        writeReport("R4", bases.get("r4"));
    }

    @Test
    void reportR5() throws Exception {
        writeReport("R5", bases.get("r5"));
    }

    /**
     * R6 answers for the same catalogue.
     *
     * <p>A version still at ballot is held to the rules the released two are
     * held to: a rule it cannot meet is recorded as failed, or declared out of
     * scope with its reason, and never quietly left out of the run.
     */
    @Test
    void reportR6() throws Exception {
        writeReport("R6", bases.get("r6"));
    }

    private void writeReport(String version, String base) throws Exception {
        http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        Conformance c = new Conformance();
        runCatalogue(c, base);
        Path out = Path.of(System.getProperty("dbo.conformance.out", "build/conformance"));
        ConformanceReport.write(out, version, c);
        System.out.printf("FHIR %s: %d supported, %d failed, %d out of scope -> %s%n",
                version, c.count(Conformance.Level.SUPPORTED),
                c.count(Conformance.Level.FAILED),
                c.count(Conformance.Level.OUT_OF_SCOPE), out);
    }

    /**
     * The rules, in the order the specification states them. Each check returns
     * what it observed, so the report carries evidence rather than a tick.
     */
    private void runCatalogue(Conformance c, String base) {
        c.check(SYSTEM, "`GET /metadata` returns a CapabilityStatement",
                "http.html#capabilities", () -> {
                    HttpResponse<String> r = get(base + "/metadata");
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"resourceType\":\"CapabilityStatement\""),
                            "body was not a CapabilityStatement");
                    return "200, CapabilityStatement";
                });

        c.check(SYSTEM, "The CapabilityStatement declares the FHIR version it serves",
                "capabilitystatement.html", () -> {
                    HttpResponse<String> r = get(base + "/metadata");
                    expect(r.body().contains("\"fhirVersion\""), "no fhirVersion declared");
                    return "fhirVersion present";
                });

        c.check(TYPE, "`POST` creates and answers 201 with a `Location`",
                "http.html#create", () -> {
                    HttpResponse<String> r = post(base + "/Patient", patient("Create", "1970-01-01"));
                    expect(r.statusCode() == 201, "status " + r.statusCode() + ": " + r.body());
                    expect(r.headers().firstValue("Location").isPresent(), "no Location header");
                    return "201, Location: " + r.headers().firstValue("Location").orElse("");
                });

        c.check(TYPE, "A create answers with an `ETag` carrying the version",
                "http.html#versioning", () -> {
                    HttpResponse<String> r = post(base + "/Patient", patient("Etag", "1970-01-02"));
                    String etag = r.headers().firstValue("ETag").orElse(null);
                    expect(etag != null, "no ETag header");
                    expect(etag.startsWith("W/"), "ETag not a weak validator: " + etag);
                    return "ETag " + etag;
                });

        c.check(INSTANCE, "`GET [type]/[id]` reads the current version",
                "http.html#read", () -> {
                    String id = created(base, patient("Read", "1970-01-03"));
                    HttpResponse<String> r = get(base + "/Patient/" + id);
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"id\":\"" + id + "\""), "id absent from body");
                    return "200 with the resource";
                });

        c.check(INSTANCE, "A read carries `ETag` and `Last-Modified`",
                "http.html#read", () -> {
                    String id = created(base, patient("Headers", "1970-01-04"));
                    HttpResponse<String> r = get(base + "/Patient/" + id);
                    expect(r.headers().firstValue("ETag").isPresent(), "no ETag");
                    expect(r.headers().firstValue("Last-Modified").isPresent(), "no Last-Modified");
                    return "both present";
                });

        c.check(INSTANCE, "`PUT` updates and advances the version",
                "http.html#update", () -> {
                    String id = created(base, patient("Update", "1970-01-05"));
                    String before = versionOf(base, id);
                    HttpResponse<String> r = put(base + "/Patient/" + id,
                            patientWithId(id, "Updated", "1970-01-05"), null);
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    String after = versionOf(base, id);
                    expect(!before.equals(after), "version did not advance: " + before);
                    return "version " + before + " -> " + after;
                });

        c.check(INSTANCE, "`PUT` with a matching `If-Match` succeeds",
                "http.html#concurrency", () -> {
                    String id = created(base, patient("Match", "1970-01-06"));
                    String etag = get(base + "/Patient/" + id).headers()
                            .firstValue("ETag").orElseThrow();
                    HttpResponse<String> r = put(base + "/Patient/" + id,
                            patientWithId(id, "Matched", "1970-01-06"), etag);
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    return "200 on the current version";
                });

        c.check(INSTANCE, "`PUT` with a stale `If-Match` answers 412",
                "http.html#concurrency", () -> {
                    String id = created(base, patient("Stale", "1970-01-07"));
                    HttpResponse<String> r = put(base + "/Patient/" + id,
                            patientWithId(id, "Stale", "1970-01-07"), "W/\"0\"");
                    expect(r.statusCode() == 412, "status " + r.statusCode());
                    return "412 Precondition Failed";
                });

        c.check(INSTANCE, "`GET [type]/[id]/_history` returns a history Bundle",
                "http.html#history", () -> {
                    String id = created(base, patient("History", "1970-01-08"));
                    put(base + "/Patient/" + id, patientWithId(id, "History2", "1970-01-08"), null);
                    HttpResponse<String> r = get(base + "/Patient/" + id + "/_history");
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"type\":\"history\""), "Bundle.type was not history");
                    return "200, Bundle.type = history";
                });

        c.check(INSTANCE, "Reading an unknown id answers 404 with an OperationOutcome",
                "http.html#read", () -> {
                    HttpResponse<String> r = get(base
                            + "/Patient/01890000-0000-7000-8000-000000000000");
                    expect(r.statusCode() == 404, "status " + r.statusCode()
                            + ": " + r.body());
                    expect(r.body().contains("OperationOutcome"), "no OperationOutcome in body");
                    return "404 + OperationOutcome";
                });

        c.check(SEARCH, "A search returns a searchset Bundle with a self link",
                "search.html#return", () -> {
                    created(base, patient("Searchable", "1970-02-01"));
                    HttpResponse<String> r = get(base + "/Patient?_count=5");
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"type\":\"searchset\""), "Bundle.type was not searchset");
                    expect(r.body().contains("\"relation\":\"self\""), "no self link");
                    return "200, searchset with self link";
                });

        c.check(SEARCH, "Token search by identifier finds the resource",
                "search.html#token", () -> {
                    String value = "conf-" + System.nanoTime();
                    post(base + "/Patient", patientWithIdentifier(value));
                    HttpResponse<String> r = get(base + "/Patient?identifier="
                            + urlEncode(EID + "|" + value));
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    // On the entries, never on the body: the self link repeats
                    // the query, so a bundle with no results contains the value
                    // that was searched for.
                    expect(r.body().contains("\"resource\""),
                            "the identifier was not found: " + r.body());
                    return "200, matched on system|value";
                });

        c.check(SEARCH, "A system-only token matches every value in that system",
                "search.html#token", () -> {
                    String value = "conf-sys-" + System.nanoTime();
                    post(base + "/Patient", patientWithIdentifier(value));
                    HttpResponse<String> r = get(base + "/Patient?_summary=count&identifier="
                            + urlEncode(EID + "|"));
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    // The shape a caller uses precisely because it discloses
                    // nothing but a number: how many carry an identifier in
                    // this system at all.
                    expect(!r.body().contains("\"total\":0"),
                            "a system-only token answered zero for a resource that carries "
                                    + "an identifier in that system: " + r.body());
                    return "200, counted on system|";
                });

        c.check(SEARCH, "A bare-code token matches whatever system it is in",
                "search.html#token", () -> {
                    String value = "conf-bare-" + System.nanoTime();
                    post(base + "/Patient", patientWithIdentifier(value));
                    HttpResponse<String> r = get(base + "/Patient?identifier=" + urlEncode(value));
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"resource\""),
                            "a code with no system found nothing: " + r.body());
                    return "200, matched on the code alone";
                });

        c.check(SEARCH, "`_summary=count` answers a count without entries",
                "search.html#summary", () -> {
                    HttpResponse<String> r = get(base + "/Patient?_summary=count");
                    expect(r.statusCode() == 200, "status " + r.statusCode());
                    expect(r.body().contains("\"total\""), "no total in the Bundle");
                    return "200, total present";
                });

        c.check(SEARCH, "An unsupported search parameter is refused, not ignored",
                "search.html#errors", () -> {
                    HttpResponse<String> r = get(base + "/Patient?nonsense=1");
                    expect(r.statusCode() == 400, "status " + r.statusCode());
                    return "400 — strict by default, because a silently dropped "
                            + "filter returns a wrong result set";
                });

        c.check(ERRORS, "A malformed body is refused with an OperationOutcome",
                "http.html#mime-type", () -> {
                    HttpResponse<String> r = post(base + "/Patient", "{ not json");
                    expect(r.statusCode() >= 400 && r.statusCode() < 500,
                            "status " + r.statusCode());
                    expect(r.body().contains("OperationOutcome"), "no OperationOutcome in body");
                    return r.statusCode() + " + OperationOutcome";
                });

        c.check(CONTENT, "Responses are served as `application/fhir+json`",
                "http.html#mime-type", () -> {
                    HttpResponse<String> r = get(base + "/metadata");
                    String type = r.headers().firstValue("Content-Type").orElse("");
                    expect(type.contains("application/fhir+json"), "Content-Type was " + type);
                    return type;
                });

        c.check(CONTENT, "What the server returns, the server accepts again",
                "http.html#update", () -> {
                    String id = created(base, patient("RoundTrip", "1970-03-01"));
                    String read = get(base + "/Patient/" + id).body();
                    HttpResponse<String> r = put(base + "/Patient/" + id, read, null);
                    expect(r.statusCode() == 200, "the returned form was refused on write: "
                            + r.statusCode());
                    return "200 — the response form passes the write validation gate";
                });

        // Boundaries dbo has drawn on purpose. They belong in the report for
        // the same reason the supported rules do: a reader deciding whether to
        // adopt needs the shape of the subset, not a list of victories.
        c.outOfScope(TYPE, "`PATCH` on an instance", "http.html#patch",
                "Not implemented; update is whole-resource. The CapabilityStatement omits it.");
        c.outOfScope(SYSTEM, "Batch and transaction Bundles", "http.html#transaction",
                "Not implemented. Writes are single-resource, and the engine's guarantee "
                        + "is one transaction per write.");
        c.outOfScope(SEARCH, "`_filter`, `_has`, composite parameters, full-text",
                "search.html#filter",
                "Tier 2 and 3 by design — measured at zero production usage in the "
                        + "search inventory, and unclaimed in the CapabilityStatement.");
        c.outOfScope(SEARCH, "`_revinclude`", "search.html#revinclude",
                "Tier 2. `_include` is supported.");
        c.outOfScope(SYSTEM, "GraphQL", "graphql.html", "Not implemented.");
        c.outOfScope(CONTENT, "XML representation", "http.html#mime-type",
                "JSON only, deliberately: one representation to validate and to sign.");
    }

    // ---- plumbing ----------------------------------------------------------

    private static void expect(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }

    private static String created(String base, String body) throws Exception {
        HttpResponse<String> r = post(base + "/Patient", body);
        expect(r.statusCode() == 201, "create failed with " + r.statusCode()
                + ": " + r.body());
        return idOf(r.body());
    }

    private static String idOf(String json) {
        int at = json.indexOf("\"id\":\"");
        expect(at >= 0, "no id in " + json);
        return json.substring(at + 6, json.indexOf('"', at + 6));
    }

    private static String versionOf(String base, String id) throws Exception {
        return get(base + "/Patient/" + id).headers().firstValue("ETag").orElse("none");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String body, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/fhir+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String patient(String family, String birthDate) {
        return """
                {"resourceType":"Patient","name":[{"family":"%s"}],"birthDate":"%s"}"""
                .formatted(family, birthDate);
    }

    private static String patientWithId(String id, String family, String birthDate) {
        return """
                {"resourceType":"Patient","id":"%s","name":[{"family":"%s"}],"birthDate":"%s"}"""
                .formatted(id, family, birthDate);
    }

    private static String patientWithIdentifier(String value) {
        return """
                {"resourceType":"Patient","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Identified"}]}""".formatted(EID, value);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
