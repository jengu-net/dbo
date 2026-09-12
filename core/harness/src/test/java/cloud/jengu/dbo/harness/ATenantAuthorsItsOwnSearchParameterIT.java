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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant defines what its own data can be asked, and the store honours it.
 *
 * <p>Before this, a tenant could POST a {@code SearchParameter} — it is an
 * ordinary canonical resource — get a 201, and then be told
 * {@code unsupported search parameter} by the same store that had just
 * accepted the definition. A store that takes a definition and will not honour
 * it is worse than one that refuses the definition, because the refusal
 * arrives at a different person on a different day.
 *
 * <p>The half that matters is the reindex. Honouring a parameter only for rows
 * written after it arrived would give a search that answers, looks healthy, and
 * omits the tenant's history — which is a worse answer than the refusal it
 * replaced. So the patient below is written <b>first</b>, and found by a
 * parameter that did not exist when it was stored.
 *
 * <p>Ordered, because each step is the state the next one changes.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantAuthorsItsOwnSearchParameterIT {

    /** Not an R4 search parameter for Patient, which is the whole point of it. */
    private static final String MARITAL = """
            {"resourceType":"SearchParameter",
             "url":"https://otsija.example/SearchParameter/patient-marital-status",
             "name":"MaritalStatus","status":"active","description":"marital status",
             "code":"marital-status","base":["Patient"],"type":"token",
             "expression":"Patient.maritalStatus"}""";

    /** A date one, because a date parameter is the kind that declares an index. */
    private static final String REGISTERED = """
            {"resourceType":"SearchParameter",
             "url":"https://otsija.example/SearchParameter/patient-registered",
             "name":"Registered","status":"active","description":"when registered",
             "code":"registered","base":["Patient"],"type":"date",
             "expression":"Patient.birthDate"}""";

    private static final String MARRIED = """
            {"resourceType":"Patient","name":[{"family":"Abielus"}],
             "maritalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"M"}]}}""";

    private static final String SINGLE = """
            {"resourceType":"Patient","name":[{"family":"Vallaline"}],
             "maritalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus","code":"S"}]}}""";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;
    static String parameterId;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-otsija");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantAuthorsItsOwnSearchParameterIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
        Files.writeString(dir.resolve("otsija.json"), """
                {"code":"otsija","face":"r4","types":[
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("otsija"));
        base = manager.baseUrl("otsija");
        // whatever bring-up put on the feed is somebody else's news
        manager.shapesRound();
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

    @Test
    @Order(1)
    @DisplayName("two patients are stored before anything can ask about their marital status")
    void patientsArriveFirst() throws Exception {
        assertEquals(201, post("/Patient", MARRIED).statusCode());
        assertEquals(201, post("/Patient", SINGLE).statusCode());
    }

    @Test
    @Order(2)
    @DisplayName("and asking by a parameter nobody defined is refused, as it should be")
    void theParameterDoesNotExistYet() throws Exception {
        HttpResponse<String> refused = get("/Patient?marital-status=M");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("unsupported search parameter"), refused.body());
        assertFalse(get("/metadata").body().contains("marital-status"),
                "the statement advertises a parameter the store refuses");
    }

    @Test
    @Order(3)
    @DisplayName("an expression the store could never evaluate is refused at the door, "
            + "where somebody is standing")
    @Proving(DboPromises.SRCH_CUSTOM_PARAMETERS)
    void anUnevaluableExpressionIsRefusedOnTheWriteRatherThanLater() throws Exception {
        HttpResponse<String> refused = post("/SearchParameter", MARITAL
                .replace("Patient.maritalStatus", "Patient.maritalStatus[[["));
        assertEquals(422, refused.statusCode(),
                "an expression nothing can evaluate was stored, so the only report of it "
                        + "will be a reindex failing about a document nobody is watching: "
                        + refused.body());
        // A FHIR face's own validator refuses a malformed expression too, and
        // here it did. The store's reason is asserted separately because it is
        // the one that does not depend on the face: whether SearchParameter
        // .expression gets validated is the face's business, and extraction is
        // not — a face that is not FHIR at all has no such validator and the
        // same door has to hold.
        assertTrue(refused.body().contains("cannot be evaluated"),
                "the refusal is the validator's alone, so a face without one would store "
                        + "an expression its own extraction cannot use: " + refused.body());
    }

    @Test
    @Order(4)
    @DisplayName("the tenant defines it, and the store honours it — including for the rows "
            + "that were already there")
    @Proving(DboPromises.SRCH_CUSTOM_PARAMETERS)
    void whatTheTenantDefinesBecomesSearchableOverItsWholeHistory() throws Exception {
        HttpResponse<String> created = post("/SearchParameter", MARITAL);
        assertEquals(201, created.statusCode(), created.body());
        java.util.regex.Matcher id = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(created.body());
        assertTrue(id.find(), created.body());
        parameterId = id.group(1);

        // Nothing has honoured it yet, and the store says so rather than
        // answering about the handful of rows written since. The window is
        // real and it is bounded by the round below.
        assertEquals(400, get("/Patient?marital-status=M").statusCode(),
                "the parameter answered before anything reindexed for it, so a search "
                        + "would have returned whatever happened to be extracted already");

        assertTrue(manager.shapesRound() >= 1,
                "the round saw nothing to do, so what follows is not testing the wiring");

        HttpResponse<String> found = get("/Patient?marital-status=M");
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("Abielus"),
                "the patient stored BEFORE the parameter existed was not found, so the "
                        + "parameter answers only about rows written since — a search that "
                        + "silently omits a tenant's history: " + found.body());
        assertFalse(found.body().contains("Vallaline"),
                "the filter matched a patient whose marital status is not M, so the "
                        + "parameter is not filtering at all: " + found.body());
    }

    @Test
    @Order(5)
    @DisplayName("and the statement advertises exactly what the store now accepts")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_HONEST_CAPABILITY})
    void theStatementSaysSo() throws Exception {
        assertTrue(get("/metadata").body().contains("marital-status"),
                "the store accepts a parameter its own statement does not mention, so a "
                        + "client reading the statement cannot discover what this tenant added");
    }

    @Test
    @Order(6)
    @DisplayName("a date parameter brings its index with it")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_DECLARED_INDEXES})
    void aDateParameterIsIndexed() throws Exception {
        assertEquals(201, post("/SearchParameter", REGISTERED).statusCode());
        manager.shapesRound();

        // Asked of the store rather than spelled here, so the assertion cannot
        // pass by naming an index that some other rule happened to create.
        cloud.jengu.dbo.core.api.TypeRegistration patient = manager.runtime("otsija")
                .orElseThrow().engine().registrationOf("Patient");
        assertTrue(patient.indexes().stream().anyMatch(i -> "registered".equals(i.path())),
                "the tenant's date parameter declares no index, so the declared-index rule "
                        + "applies to what a version defines and not to what a tenant adds: "
                        + patient.indexes());
        assertTrue(indexExists(patient.domain() + "_patient_registered_ix"),
                "the index is declared and does not exist, so every sort or range over the "
                        + "tenant's own date parameter is a sequential scan of the type");
    }

    @Test
    @Order(7)
    @DisplayName("withdrawing it takes the search away again, and the statement with it")
    @Proving({DboPromises.SRCH_CUSTOM_PARAMETERS, DboPromises.SRCH_HONEST_CAPABILITY})
    void withdrawingItPutsTheStoreBack() throws Exception {
        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/SearchParameter/" + parameterId)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(deleted.statusCode() < 300, deleted.body());

        manager.shapesRound();

        assertEquals(400, get("/Patient?marital-status=M").statusCode(),
                "the parameter was withdrawn and the store still answers by it, so a "
                        + "tenant cannot take back what it defined");
        assertFalse(get("/metadata").body().contains("marital-status"),
                "the statement still advertises a withdrawn parameter");
    }

    private static boolean indexExists(String name) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                        SharedPostgres.jdbcUrl("tenant_otsija"),
                        postgres.getUsername(), postgres.getPassword());
                java.sql.PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
            ps.setString(1, name);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("a parameter this store could not select with is refused where the author "
            + "is standing, naming what stopped it")
    @Proving(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES)
    void aParameterThatWillNotCompileIsRefusedByName() throws Exception {
        // Evaluable is the weaker question, and the one this store used to
        // ask. An expression can evaluate perfectly and still use a construct
        // that compiles to nothing the database selects with — and a
        // parameter like that is accepted, indexed against nothing, and
        // answers no search, which the author finds out from somebody else
        // much later.
        HttpResponse<String> refused = post("/SearchParameter", """
                {"resourceType":"SearchParameter",
                 "url":"https://otsija.example/SearchParameter/patient-distinct-name",
                 "name":"DistinctName","status":"active","description":"an unsayable one",
                 "code":"distinct-name","base":["Patient"],"type":"token",
                 "expression":"Patient.name.given.isDistinct()"}""");

        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("select"), refused.body());
        assertTrue(refused.body().contains("isDistinct") || refused.body().contains("Patient"),
                "the refusal does not say what stopped it, which is the whole of what the "
                        + "author can act on: " + refused.body());
    }

    @Test
    @Order(9)
    @DisplayName("the parameters a tenant can be asked by are held as rows, compiled")
    @Proving(DboPromises.SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES)
    void theParametersAreHeldAsCompiledRows() throws Exception {
        // The test before this withdrew it, which is that test's whole point,
        // so it is authored again here rather than depending on an order that
        // happens to leave it in place.
        assertTrue(post("/SearchParameter", MARITAL).statusCode() < 300);
        manager.shapesRound();

        cloud.jengu.dbo.definitions.DefinitionStore definitions =
                new cloud.jengu.dbo.definitions.DefinitionStore(tenantSource());

        var onPatient = definitions.parametersOf("Patient");
        assertFalse(onPatient.isEmpty(),
                "nothing was compiled for Patient, so every search is still an expression "
                        + "walked over an object tree");

        // The version's own, and the tenant's, in one set: what a type can be
        // asked is both together, and a reader of these rows should not have
        // to know which arrived by which route.
        assertTrue(onPatient.stream().anyMatch(one -> "identifier".equals(one.code())),
                "the version's own parameters are not among the rows: "
                        + onPatient.stream().map(one -> one.code()).sorted().toList());
        assertTrue(onPatient.stream().anyMatch(one -> "marital-status".equals(one.code())),
                "the parameter this tenant authored is not among the rows");

        var authored = onPatient.stream()
                .filter(one -> "marital-status".equals(one.code())).findFirst().orElseThrow();
        assertTrue(authored.enforceable(),
                "the authored parameter compiled to nothing: " + authored.unenforceable());
        assertFalse(authored.paths().isEmpty(), "it selects nowhere");
        assertEquals("token", authored.kind());
        assertEquals("Patient.maritalStatus", authored.expression(),
                "the expression as written is not kept beside the compiled form, so nobody "
                        + "can read why a search answers the way it does");
    }

    private static org.postgresql.ds.PGSimpleDataSource tenantSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_otsija"));
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        return source;
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
