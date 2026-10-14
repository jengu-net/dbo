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
    /** The deployment's Vault: a dev server, its root token the store's. */
    private org.testcontainers.containers.GenericContainer<?> vault;

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

        vault = new org.testcontainers.containers.GenericContainer<>("hashicorp/vault:1.17")
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root").withExposedPorts(8200)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forHttp("/v1/sys/health").forStatusCode(200));
        vault.start();
        vaultPost("/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        vaultPost("/v1/transit/keys/tenant-" + OWNER, "{}");
        vaultPost("/v1/transit/keys/tenant-someone-else", "{}");

        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantsConfigurationIsItsOwnIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        Path dir = Files.createTempDirectory("dbo-owner-config-tenants");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.vault(new cloud.jengu.dbo.tenant.VaultTransit(vaultAddress(), "transit",
                () -> "root"));
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
        if (vault != null) {
            vault.stop();
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

    @Test
    @Order(6)
    @DisplayName("a file of secrets wrapped by the tenant's own Transit key is opened through "
            + "the deployment's Vault and kept by its authority, never as a record; a changed "
            + "value replaces it, and one withdrawn is forgotten")
    @Proving(DboPromises.TEN_AN_OWNERS_SECRETS_ARE_SEALED_IN_ITS_REPOSITORY)
    void aSecretIsKeptFromTheRepository() throws Exception {
        Files.delete(repository.resolve("organisations/elsewhere.json"));
        write("secrets/connections.sops.json", sealed("tenant-" + OWNER,
                java.util.Map.of("lab-connection-password", "first-pa55word")));
        commit("The lab's connection");
        String applied = apply("");
        assertEquals("0", number(applied, "skipped"), applied);
        assertEquals("first-pa55word", secret("lab-connection-password"));
        assertFalse(holdsInTheClear("first-pa55word"),
                "a record holds the secret in the clear");

        write("secrets/connections.sops.json", sealed("tenant-" + OWNER,
                java.util.Map.of("lab-connection-password", "second-pa55word")));
        commit("Rotate the lab's password");
        apply("");
        assertEquals("second-pa55word", secret("lab-connection-password"));

        git.rm().addFilepattern("secrets/connections.sops.json").call();
        commit("The lab is gone");
        String gone = apply("");
        assertEquals("1", number(gone, "withdrawn"), gone);
        assertEquals(null, secret("lab-connection-password"), "a withdrawn secret was kept");
    }

    @Test
    @Order(7)
    @DisplayName("a file of secrets wrapped by another tenant's key is refused naming the key, "
            + "and nothing of it is kept")
    @Proving(DboPromises.TEN_AN_OWNERS_SECRETS_ARE_SEALED_IN_ITS_REPOSITORY)
    void anotherTenantsKeyOpensNothing() throws Exception {
        write("secrets/borrowed.sops.json", sealed("tenant-someone-else",
                java.util.Map.of("borrowed-token", "not-this-tenants")));
        commit("A secret sealed for somebody else");
        String carded = apply("");
        assertTrue(carded.contains("\"declaration\":\"secrets/borrowed.sops.json\""), carded);
        assertTrue(carded.contains("tenant-" + OWNER), carded);
        assertEquals(null, secret("borrowed-token"));
    }

    // ── secrets ──

    /**
     * A file of secrets as SOPS writes one: each value under its name, a MAC
     * over them, and the data key wrapped by a Transit key — naming a Vault
     * other than the deployment's, which the store must not reach.
     */
    private String sealed(String key, java.util.Map<String, String> secrets) throws Exception {
        byte[] dataKey = new byte[32];
        new java.security.SecureRandom().nextBytes(dataKey);
        java.security.MessageDigest mac = java.security.MessageDigest.getInstance("SHA-512");
        StringBuilder json = new StringBuilder("{");
        for (var secret : new java.util.TreeMap<>(secrets).entrySet()) {
            json.append('"').append(secret.getKey()).append("\":\"")
                    .append(encrypted(dataKey, secret.getValue(), secret.getKey() + ":"))
                    .append("\",");
            mac.update(secret.getValue().getBytes(StandardCharsets.UTF_8));
        }
        String lastModified = java.time.Instant.now().truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS).toString();
        String wrapped = field(vaultPost("/v1/transit/encrypt/" + key, "{\"plaintext\":\""
                + java.util.Base64.getEncoder().encodeToString(dataKey) + "\"}"), "ciphertext");
        return json.append("\"sops\":{\"hc_vault\":[{\"vault_address\":"
                        + "\"http://vault.owner.example:8200\",\"engine_path\":\"transit\","
                        + "\"key_name\":\"").append(key).append("\",\"created_at\":\"")
                .append(lastModified).append("\",\"enc\":\"").append(wrapped)
                .append("\"}],\"lastmodified\":\"").append(lastModified)
                .append("\",\"mac\":\"").append(encrypted(dataKey, java.util.HexFormat.of()
                        .withUpperCase().formatHex(mac.digest()), lastModified))
                .append("\",\"version\":\"3.13.1\"}}").toString();
    }

    private static String encrypted(byte[] key, String plain, String additional)
            throws Exception {
        byte[] iv = new byte[32];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        gcm.updateAAD(additional.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = gcm.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        return "ENC[AES256_GCM,data:" + b64.encodeToString(java.util.Arrays.copyOf(sealed,
                sealed.length - 16)) + ",iv:" + b64.encodeToString(iv) + ",tag:"
                + b64.encodeToString(java.util.Arrays.copyOfRange(sealed, sealed.length - 16,
                        sealed.length)) + ",type:str]";
    }

    private String secret(String name) {
        return manager.authority(OWNER).secret(name)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    /** Whether any record the tenant holds about its configuration carries this text. */
    private boolean holdsInTheClear(String text) {
        for (String type : List.of("ConfigFile", "Organization", "ValueSet")) {
            for (StoredObject one : engine().select(cloud.jengu.dbo.core.api.Criteria.of(type))) {
                if (new String(one.payload(), StandardCharsets.UTF_8).contains(text)) {
                    return true;
                }
            }
        }
        for (Run run : acts()) {
            if (run.toString().contains(text)) {
                return true;
            }
        }
        return manager.authority(OWNER).toString().contains(text);
    }

    private java.net.URI vaultAddress() {
        return java.net.URI.create("http://" + vault.getHost() + ":"
                + vault.getMappedPort(8200));
    }

    private String vaultPost(String path, String body) throws Exception {
        HttpResponse<String> answered = http.send(HttpRequest.newBuilder(
                        vaultAddress().resolve(path)).header("X-Vault-Token", "root")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(answered.statusCode() / 100 == 2, path + ": " + answered.body());
        return answered.body();
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
