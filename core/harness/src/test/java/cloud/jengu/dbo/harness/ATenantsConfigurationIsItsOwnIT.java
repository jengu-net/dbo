package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant owner's configuration, kept in a git repository the owner commits
 * to, applied commit by commit.
 *
 * <p>A world of its own because the tenant's declaration names a repository
 * this class writes, on a path only this run has; a tenant of the shared world
 * holding one would hold this class's commits for every class after it. Its
 * tenant is named for this run, because a tenant's database is named by its
 * code and a database server may outlive the run that made it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantsConfigurationIsItsOwnIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String OWNER = "owner-" + RUN;
    private static final String ORG = "urn:example:org";
    private static final String TRIAGE = "http://example.org/ValueSet/triage";

    private final HttpClient http = HttpClient.newHttpClient();
    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;
    private Path repository;
    private Git git;
    private RevCommit first;
    private RevCommit second;

    @BeforeAll
    void aTenantWhoseOwnerKeepsItsConfiguration() throws Exception {
        repository = Files.createTempDirectory("dbo-owner-config");
        git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call();
        write("organisations/ward-7.json", organisation("ward-7", "Ward 7"));
        write("value-sets/triage.json", """
                {"resourceType":"ValueSet","url":"%s","status":"active","name":"Triage"}"""
                .formatted(TRIAGE));
        write("README.md", "The clinic's configuration.");
        first = commit("Open the clinic with ward 7 and its triage codes");
        git.tag().setName("opening").call();

        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantsConfigurationIsItsOwnIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        Path dir = Files.createTempDirectory("dbo-owner-config-tenants");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        Files.writeString(dir.resolve(OWNER + ".json"), """
                {"code":"%s","face":"r5",
                 "configuration":{"git":"%s","ref":"main"},
                 "types":[
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(OWNER, repository.toString().replace("\\", "\\\\"), ORG));
        UntilServed.scan(manager, OWNER);
        manager.authority(OWNER).ensureClient("owner", "owner-secret",
                List.of("configuration"));
    }

    @AfterAll
    void down() {
        if (git != null) {
            git.close();
        }
        if (manager != null) {
            manager.close();
        }
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("a tenant declared with a repository applies its declared ref when it comes "
            + "up, and a second apply of the same commit reads nothing")
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    void itComesUpConfigured() throws Exception {
        assertEquals("Ward 7", organisationName("ward-7"));
        assertTrue(valueSet().isPresent(), "the triage codes were not applied");
        assertEquals(first.getName(), sweep().correlated().orElse(null),
                "the run is not correlated with the commit applied");

        String again = apply("");
        assertEquals(first.getName(), field(again, "commit"));
        assertEquals("0", number(again, "read"), again);
        assertEquals("0", number(again, "applied"), again);
    }

    @Test
    @Order(2)
    @DisplayName("a commit changing one file reads and applies that file alone, and one "
            + "deleting a file withdraws what it declared and nothing anybody else did")
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    void aCommitIsAChangelist() throws Exception {
        long triageVersion = valueSet().orElseThrow().versionId();
        write("organisations/ward-7.json", organisation("ward-7", "Ward 7, cardiology"));
        write("organisations/ward-8.json", organisation("ward-8", "Ward 8"));
        second = commit("Rename ward 7 and open ward 8");

        String applied = apply("{\"ref\":\"main\"}");
        assertEquals("2", number(applied, "read"), applied);
        assertEquals("2", number(applied, "applied"), applied);
        assertEquals("Ward 7, cardiology", organisationName("ward-7"));
        assertEquals("Ward 8", organisationName("ward-8"));
        assertEquals(triageVersion, valueSet().orElseThrow().versionId(),
                "a file that did not change was written");

        // Another declarer's record of the same type, beside the owner's.
        String sent = post("", """
                {"declarations":[{"type":"Organization","name":"ward-9",
                 "payload":%s}]}""".formatted(organisation("ward-9", "Ward 9")));
        assertEquals("1", number(sent, "applied"), sent);

        git.rm().addFilepattern("organisations/ward-8.json").call();
        commit("Close ward 8");
        String closed = apply("");
        assertEquals("0", number(closed, "read"), closed);
        assertEquals("1", number(closed, "withdrawn"), closed);
        assertFalse(organisation("ward-8").isPresent(), "the deleted file's record stayed");
        assertTrue(organisation("ward-9").isPresent(),
                "a record the repository never declared was withdrawn");
        assertTrue(organisation("ward-7").isPresent(), "a record still declared was withdrawn");
    }

    @Test
    @Order(3)
    @DisplayName("applying an older commit undoes what was applied since, as a changelist")
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    void anOlderCommitIsARollback() throws Exception {
        String back = apply("{\"ref\":\"" + second.getName() + "\"}");
        assertEquals("1", number(back, "applied"), back);
        assertTrue(organisation("ward-8").isPresent(), "ward 8 did not come back");

        String opening = apply("{\"ref\":\"opening\"}");
        assertEquals(first.getName(), field(opening, "commit"));
        assertEquals("Ward 7", organisationName("ward-7"));
        assertFalse(organisation("ward-8").isPresent(), "ward 8 outlived the rollback");
        assertEquals("1", number(opening, "withdrawn"), opening);
    }

    @Test
    @Order(4)
    @DisplayName("the run names the ref, the commit, its author and message, and who asked; "
            + "a preview lists what applying would change and changes nothing")
    @Proving({DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY,
            DboPromises.TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING})
    void theRunSaysWhatWasApplied() throws Exception {
        String applied = apply("{\"ref\":\"main\"}");
        assertEquals("Ada Lovelace <ada@example.org>", field(applied, "author"));
        assertEquals("Close ward 8", field(applied, "message"));
        assertEquals("owner", field(applied, "by"));
        String head = field(applied, "commit");
        Run act = acts().stream().filter(one -> one.assignment().note().contains(head))
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new AssertionError("no act names commit " + head));
        assertTrue(act.assignment().note().contains("applied main at " + head
                + ", by Ada Lovelace <ada@example.org>: Close ward 8"), act.toString());
        assertEquals("owner", act.assignment().claimant(), act.toString());

        long before = organisation("ward-7").orElseThrow().versionId();
        String preview = post("/preview", "{\"ref\":\"opening\"}");
        assertTrue(preview.contains("\"preview\":true"), preview);
        assertTrue(preview.contains("{\"path\":\"organisations/ward-7.json\","
                + "\"change\":\"modified\"}"), preview);
        assertEquals(before, organisation("ward-7").orElseThrow().versionId(),
                "a preview wrote");
        assertEquals("Ward 7, cardiology", organisationName("ward-7"));
    }

    @Test
    @Order(5)
    @DisplayName("a file nothing can withdraw is a card naming it, and a link posing as a "
            + "declaration is refused rather than followed")
    @Proving(DboPromises.TEN_AN_OWNERS_CONFIGURATION_IS_APPLIED_FROM_ITS_REPOSITORY)
    void whatCannotBeTakenBackIsRefused() throws Exception {
        write("organisations/nameless.json", """
                {"resourceType":"Organization","name":"Nobody's"}""");
        commit("An organisation with no identifier");
        String carded = apply("");
        assertTrue(carded.contains("\"declaration\":\"organisations/nameless.json\""), carded);
        assertEquals("1", number(carded, "skipped"), carded);

        git.rm().addFilepattern("organisations/nameless.json").call();
        Files.createSymbolicLink(repository.resolve("organisations/elsewhere.json"),
                Path.of("/etc/hosts"));
        commit("A link where a declaration should be");
        HttpResponse<String> refused = send("", "");
        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("organisations/elsewhere.json"), refused.body());
    }

    // ── the repository ──

    private void write(String path, String content) throws Exception {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private RevCommit commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        PersonIdent ada = new PersonIdent("Ada Lovelace", "ada@example.org");
        return git.commit().setMessage(message).setAuthor(ada).setCommitter(ada).call();
    }

    private static String organisation(String code, String name) {
        return """
                {"resourceType":"Organization","identifier":[{"system":"%s","value":"%s"}],\
                "name":"%s"}""".formatted(ORG, code, name);
    }

    // ── the store ──

    private ObjectStore engine() {
        return manager.runtime(OWNER).orElseThrow().engine();
    }

    private Optional<StoredObject> organisation(String code) {
        return engine().getByIdentifier("Organization", List.of(new Identifier(ORG, code)))
                .stream().findFirst();
    }

    private String organisationName(String code) {
        String json = new String(organisation(code).orElseThrow(() -> new AssertionError(
                "no organisation " + code)).payload(), StandardCharsets.UTF_8);
        return field(json, "name");
    }

    private Optional<StoredObject> valueSet() {
        return engine().getByIdentifier("ValueSet", List.of(new Identifier(
                Identifier.CANONICAL_SYSTEM, TRIAGE))).stream().findFirst();
    }

    private Run sweep() {
        return new Runs(engine()).byKey("dbo.config.applied/apply/git." + OWNER).orElseThrow(
                () -> new AssertionError("no run applies the owner's configuration"));
    }

    private List<Run> acts() {
        String parent = sweep().key();
        return engine().select(cloud.jengu.dbo.core.api.Criteria.of(
                        cloud.jengu.dbo.work.WorkModel.TYPE).eq("parent",
                        cloud.jengu.dbo.core.api.EnvelopeValue.of(parent))).stream()
                .map(Run::of).filter(run -> "commit".equals(run.step())).toList();
    }

    // ── the door ──

    private String apply(String body) throws Exception {
        return post("", body);
    }

    private String post(String path, String body) throws Exception {
        HttpResponse<String> answered = send(path, body);
        assertEquals(200, answered.statusCode(), answered.body());
        return answered.body();
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/configuration" + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String token() throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=owner"
                                        + "&client_secret=owner-secret")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return field(body, "access_token");
    }

    private String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/" + OWNER;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }

    private static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), name + " is not in " + json);
        return m.group(1);
    }
}
