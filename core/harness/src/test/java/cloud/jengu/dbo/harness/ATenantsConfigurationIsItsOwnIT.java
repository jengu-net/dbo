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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant owner's configuration, kept in a git repository the owner commits
 * to and pushes to the store, applied commit by commit.
 *
 * <p>A world of its own because the tenant's repository holds this class's
 * commits, and a tenant of the shared world holding them would hold them for
 * every class after it. Its
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
    private static final String CONNECT = "clinic.lab.connect";
    private static final String PEEK = "clinic.lab.peek";
    private static final String CONNECTIONS = "clinic.lab.connections";

    private final HttpClient http = HttpClient.newHttpClient();
    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;
    private Path repository;
    private Git git;
    private RevCommit first;
    private RevCommit second;
    /** The deployment's Vault: a dev server, its root token the store's. */
    private org.testcontainers.containers.GenericContainer<?> vault;
    /** Where Vault and the reference SOPS reach each other, as an owner's machine would. */
    private final org.testcontainers.containers.Network network =
            org.testcontainers.containers.Network.newNetwork();

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
                .withNetwork(network).withNetworkAliases("vault")
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
                 "configuration":{"ref":"main"},
                 "types":[
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}],
                 "steps":[
                  {"code":"%s","slots":{"organisation":"Organization"},
                   "secrets":["lab-connection-password"]},
                  {"code":"%s","slots":{"organisation":"Organization"}},
                  {"code":"%s","slots":{"organisation":"Organization"},
                   "secrets":["connection.*","zone.token"]}]}"""
                .formatted(OWNER, ORG, CONNECT, PEEK, CONNECTIONS));
        UntilServed.scan(manager, OWNER);
        manager.authority(OWNER).ensureClient("owner", "owner-secret",
                List.of("configuration"));
        // The owner's own git client: reads, and pushes any branch.
        manager.authority(OWNER).ensureClient("owner-git", "owner-git-secret",
                List.of("config/read", "config/push:*"));
        pushed("main");
        git.branchCreate().setName("opening").setStartPoint(first).call();
        pushed("opening");
        apply("");
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
        network.close();
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("a tenant whose owner pushed its configuration applies the declared ref, and "
            + "a second apply of the same commit reads nothing")
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
        // Declared operational, so the configuration is not its only writer,
        // and the answer says so before anybody else's write does.
        assertTrue(preview.contains("\"othersMayChange\":[\"Organization\"]"), preview);
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

    @Test
    @Order(8)
    @DisplayName("a step reaches a secret it declares by name, in process and sealed to a "
            + "participant's key over HTTP; a step that does not declare it, and a participant "
            + "holding no key, are refused")
    @Proving(DboPromises.TEN_A_STEP_REACHES_THE_SECRETS_IT_DECLARES)
    void aStepReachesTheSecretsItDeclares() throws Exception {
        git.rm().addFilepattern("secrets/borrowed.sops.json").call();
        write("secrets/lab.sops.json", sealed("tenant-" + OWNER,
                java.util.Map.of("lab-connection-password", "lab-pa55")));
        commit("The lab's connection, again");
        String applied = apply("");
        assertEquals("0", number(applied, "skipped"), applied);

        cloud.jengu.dbo.work.Executor performer = new cloud.jengu.dbo.work.Executor(
                "performer", "1", "example.clinic", cloud.jengu.dbo.work.Scope.BASELINE);
        assertEquals("lab-pa55", reached(declared(manager.fleetLane(OWNER, CONNECT, performer)
                .orElseThrow(), CONNECT), CONNECT, "in-process"));
        String peeked = reached(declared(manager.fleetLane(OWNER, PEEK, performer)
                .orElseThrow(), PEEK), PEEK, "peeked");
        assertTrue(peeked.startsWith("failed:") && peeked.contains("does not declare"), peeked);

        cloud.jengu.dbo.core.api.seal.ParticipantKeys keys =
                cloud.jengu.dbo.core.api.seal.ParticipantKeys.adoptOrMake(
                        Files.createTempDirectory("dbo-keyed"));
        manager.authority(OWNER).ensureClient("keyed", "keyed-secret", List.of("work"),
                keys.sealingKey(), keys.signingKey());
        manager.authority(OWNER).ensureClient("keyless", "keyless-secret", List.of("work"));
        cloud.jengu.dbo.runner.Lane keyed = cloud.jengu.dbo.runner.http.HttpLane.holding(
                URI.create(base() + "/work"), () -> tokenOf("keyed", "keyed-secret"), OWNER,
                "keyed", new cloud.jengu.dbo.work.Executor("keyed", "1", "example.clinic",
                        cloud.jengu.dbo.work.Scope.BASELINE), keys.sealing(), keys.signing());
        assertEquals("lab-pa55", reached(declared(keyed, CONNECT), CONNECT, "keyed"));
        cloud.jengu.dbo.runner.Lane keyless = cloud.jengu.dbo.runner.http.HttpLane.to(
                URI.create(base() + "/work"), () -> tokenOf("keyless", "keyless-secret"), OWNER,
                "keyless", new cloud.jengu.dbo.work.Executor("keyless", "1", "example.clinic",
                        cloud.jengu.dbo.work.Scope.BASELINE));
        // Refused before its run is taken, naming the step, rather than at
        // the first secret the step asks for.
        var started = manager.runInitiator().starting(OWNER, CONNECT, java.util.Map.of(
                        "organisation", cloud.jengu.dbo.work.RunInitiator.Slot.object(
                                organisation("lab-keyless", "The lab"))),
                "keyless-" + System.nanoTime());
        Run offered = new Runs(engine()).byKey(started.key()).orElseThrow();
        cloud.jengu.dbo.runner.Lane keylessLane = declared(keyless, CONNECT);
        RuntimeException refused = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> keylessLane.claim(offered, java.time.Duration.ofMinutes(5)));
        assertTrue(refused.getMessage().contains(CONNECT)
                && refused.getMessage().contains("holds no key"), refused.getMessage());
    }

    @Test
    @Order(11)
    @DisplayName("a worker holding its keys reaches the secrets its step declares, sealed on the "
            + "wire, and no other; with its keys made again it is refused, saying why, until "
            + "its client is ensured with them; and the trail names each secret it reached")
    @Proving(DboPromises.TEN_A_WORKER_HOLDS_ITS_KEYS_AND_REACHES_ITS_STEPS_SECRETS)
    void aWorkerHoldingItsKeysReachesItsSecrets() throws Exception {
        cloud.jengu.dbo.core.api.seal.ParticipantKeys first =
                cloud.jengu.dbo.core.api.seal.ParticipantKeys.adoptOrMake(
                        Files.createTempDirectory("dbo-worker-first"));
        manager.authority(OWNER).ensureClient("worker", "worker-secret", List.of("work"),
                first.sealingKey(), first.signingKey());
        cloud.jengu.dbo.runner.Lane holding = workerLane(first);
        String[] reached = reachedAs(holding, "worker", "connection.sample-1.password");
        assertEquals("sample-pa55", reached[0]);
        String undeclared = reachedAs(holding, "worker-other", "lab-connection-password")[0];
        assertTrue(undeclared.startsWith("failed:") && undeclared.contains("does not declare"),
                undeclared);

        String trail = trailOf(reached[1]);
        assertTrue(trail.contains("connection.sample-1.password") && trail.contains("worker"),
                "the trail does not name the secret and who reached it: " + trail);

        cloud.jengu.dbo.core.api.seal.ParticipantKeys remade =
                cloud.jengu.dbo.core.api.seal.ParticipantKeys.adoptOrMake(
                        Files.createTempDirectory("dbo-worker-remade"));
        cloud.jengu.dbo.runner.Lane afterRestart = workerLane(remade);
        String stale = reachedAs(afterRestart, "worker-stale", "connection.sample-1.password")[0];
        assertTrue(stale.startsWith("failed:") && stale.contains("no longer holds"), stale);
        manager.authority(OWNER).ensureClient("worker", "worker-secret", List.of("work"),
                remade.sealingKey(), remade.signingKey());
        assertEquals("sample-pa55",
                reachedAs(afterRestart, "worker-again", "connection.sample-1.password")[0]);
    }

    private cloud.jengu.dbo.runner.Lane workerLane(
            cloud.jengu.dbo.core.api.seal.ParticipantKeys keys) {
        return declared(cloud.jengu.dbo.runner.http.HttpLane.holding(
                URI.create(base() + "/work"), () -> tokenOf("worker", "worker-secret"), OWNER,
                "worker", new cloud.jengu.dbo.work.Executor("worker", "1", "example.clinic",
                        cloud.jengu.dbo.work.Scope.BASELINE), keys.sealing(), keys.signing()),
                CONNECTIONS);
    }

    private String trailOf(String runKey) {
        StringBuilder said = new StringBuilder();
        for (StoredObject entry : engine().select(cloud.jengu.dbo.core.api.Criteria.of(
                "AuditEntry").eq("run", cloud.jengu.dbo.core.api.EnvelopeValue.of(runKey)))) {
            said.append(new String(entry.payload(), StandardCharsets.UTF_8));
        }
        return said.toString();
    }

    @Test
    @Order(9)
    @DisplayName("a step declaring a family reaches every secret its prefix extends, in process "
            + "and sealed, and nothing beside it; the run's trail names the secret it reached")
    @Proving(DboPromises.TEN_A_STEP_REACHES_A_FAMILY_OF_SECRETS)
    void aStepReachesAFamilyOfSecrets() throws Exception {
        java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        secrets.put("connection.sample-1.password", "sample-pa55");
        secrets.put("connections.sample-1.password", "not-in-the-family");
        secrets.put("connection", "not-in-the-family-either");
        secrets.put("zone.token", "zone-t0ken");
        write("secrets/connections.sops.json", sealed("tenant-" + OWNER, secrets));
        commit("The lab's connections");
        String applied = apply("");
        assertEquals("0", number(applied, "skipped"), applied);

        cloud.jengu.dbo.work.Executor performer = new cloud.jengu.dbo.work.Executor(
                "performer", "1", "example.clinic", cloud.jengu.dbo.work.Scope.BASELINE);
        cloud.jengu.dbo.runner.Lane inProcess = declared(manager.fleetLane(OWNER, CONNECTIONS,
                performer).orElseThrow(), CONNECTIONS);
        String[] family = reachedAs(inProcess, "family", "connection.sample-1.password");
        assertEquals("sample-pa55", family[0]);
        assertEquals("zone-t0ken", reachedAs(inProcess, "named", "zone.token")[0]);
        for (String beside : List.of("connections.sample-1.password", "connection")) {
            String refused = reachedAs(inProcess, "beside", beside)[0];
            assertTrue(refused.startsWith("failed:") && refused.contains("does not declare"),
                    beside + ": " + refused);
        }

        cloud.jengu.dbo.core.api.seal.ParticipantKeys keys =
                cloud.jengu.dbo.core.api.seal.ParticipantKeys.adoptOrMake(
                        Files.createTempDirectory("dbo-family"));
        manager.authority(OWNER).ensureClient("family", "family-secret", List.of("work"),
                keys.sealingKey(), keys.signingKey());
        cloud.jengu.dbo.runner.Lane sealedLane = declared(cloud.jengu.dbo.runner.http.HttpLane
                .holding(URI.create(base() + "/work"), () -> tokenOf("family", "family-secret"),
                        OWNER, "family", new cloud.jengu.dbo.work.Executor("family", "1",
                                "example.clinic", cloud.jengu.dbo.work.Scope.BASELINE),
                        keys.sealing(), keys.signing()), CONNECTIONS);
        assertEquals("sample-pa55",
                reachedAs(sealedLane, "sealed", "connection.sample-1.password")[0]);

        List<String> named = new java.util.ArrayList<>();
        for (StoredObject entry : engine().select(cloud.jengu.dbo.core.api.Criteria.of(
                "AuditEntry").eq("run", cloud.jengu.dbo.core.api.EnvelopeValue.of(family[1])))) {
            String said = new String(entry.payload(), StandardCharsets.UTF_8);
            if (said.contains("\"secret\"")) {
                named.add(said);
            }
        }
        assertEquals(1, named.size(), "the run's trail does not say once what it reached: "
                + named);
        assertTrue(named.get(0).contains("connection.sample-1.password")
                && !named.get(0).contains("connection.*"), named.get(0));
        assertFalse(named.get(0).contains("sample-pa55"), "the trail holds the secret itself");
    }

    @Test
    @Order(10)
    @DisplayName("a deployment writes the tenant's secrets as SOPS does: the reference sops "
            + "opens the file through Vault and with the owner's age key alone, .sops.yaml "
            + "decides the recipients by path, a writer that may only encrypt cannot open what "
            + "it wrote, and an applied file is the tenant's secrets")
    @Proving(DboPromises.TEN_A_DEPLOYMENT_WRITES_AN_OWNERS_SECRETS_AS_SOPS_DOES)
    void aDeploymentWritesAFileOfSecrets() throws Exception {
        java.security.KeyPair owner = new com.exceptionfactory.jagged.x25519.X25519KeyPairGenerator()
                .generateKeyPair();
        String recipient = owner.getPublic().toString();
        String transit = "http://vault:8200/v1/transit/keys/tenant-" + OWNER;
        write(".sops.yaml", """
                creation_rules:
                  - path_regex: secrets/owner/.*
                    hc_vault_transit_uri: %s
                    age: %s
                  - hc_vault_transit_uri: %s
                """.formatted(transit, recipient, transit));
        commit("Who the owner's secrets are encrypted to");

        String ownerFile = post("/secrets", """
                {"path":"secrets/owner/lab.sops.json",
                 "secrets":{"a":"first-pa55","b":"second-pa55"}}""");
        assertTrue(ownerFile.contains(recipient), "the rule's age recipient is missing");
        assertFalse(ownerFile.contains("first-pa55"), "a value is in the file in the clear");
        String plainFile = post("/secrets", """
                {"path":"secrets/plain.sops.json","secrets":{"c":"third-pa55"}}""");
        assertFalse(plainFile.contains("\"age\""), "a path with no age recipient carries one");
        HttpResponse<String> notAFile = send("/secrets", """
                {"path":"secrets/plain.json","secrets":{"c":"x"}}""");
        assertEquals(422, notAFile.statusCode(), notAFile.body());

        String throughVault = sopsDecrypts(ownerFile, java.util.Map.of(
                "VAULT_ADDR", "http://vault:8200", "VAULT_TOKEN", "root"));
        assertTrue(throughVault.contains("first-pa55") && throughVault.contains("second-pa55"),
                "the reference sops did not open the file through Vault: " + throughVault);
        String withAge = sopsDecrypts(ownerFile, java.util.Map.of(
                "SOPS_AGE_KEY", owner.getPrivate().toString(),
                "VAULT_ADDR", "http://nowhere.invalid:8200"));
        assertTrue(withAge.contains("first-pa55"),
                "the owner's age key alone did not open the file: " + withAge);

        vaultPost("/v1/sys/policies/acl/writer-only", "{\"policy\":\"path \\\"transit/encrypt/"
                + "tenant-" + OWNER + "\\\" { capabilities = [\\\"update\\\"] }\"}");
        String writerToken = field(vaultPost("/v1/auth/token/create",
                "{\"policies\":[\"writer-only\"]}"), "client_token");
        byte[] written = new cloud.jengu.dbo.tenant.SopsWriter(
                new cloud.jengu.dbo.tenant.VaultTransit(vaultAddress(), "transit",
                        () -> writerToken), "tenant-" + OWNER)
                .write("secrets/writer.sops.json", java.util.Map.of("d", "fourth-pa55"), null);
        String writtenText = new String(written, StandardCharsets.UTF_8);
        String wrapped = field(writtenText.substring(writtenText.indexOf("hc_vault")), "enc");
        HttpResponse<String> reading = http.send(HttpRequest.newBuilder(vaultAddress().resolve(
                        "/v1/transit/decrypt/tenant-" + OWNER))
                        .header("X-Vault-Token", writerToken)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"ciphertext\":\"" + wrapped + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, reading.statusCode(), "a writer that may only encrypt decrypted: "
                + reading.body());

        write("secrets/owner/lab.sops.json", ownerFile);
        write("secrets/plain.sops.json", plainFile);
        write("secrets/writer.sops.json", writtenText);
        commit("The lab's secrets, as the deployment wrote them");
        String applied = apply("");
        assertEquals("0", number(applied, "skipped"), applied);
        assertEquals("first-pa55", secret("a"));
        assertEquals("third-pa55", secret("c"));
        assertEquals("fourth-pa55", secret("d"));
    }

    /** What the reference SOPS prints opening a file, or why it would not. */
    private String sopsDecrypts(String file, java.util.Map<String, String> env) {
        try (var sops = new org.testcontainers.containers.GenericContainer<>(
                "ghcr.io/getsops/sops:v3.13.1-alpine")
                .withNetwork(network).withEnv(env)
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(
                        file.getBytes(StandardCharsets.UTF_8)), "/work/file.sops.json")
                .withCommand("-d", "--input-type", "json", "--output-type", "json",
                        "/work/file.sops.json")
                .withStartupCheckStrategy(
                        new org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy()
                                .withTimeout(java.time.Duration.ofSeconds(120)))) {
            try {
                sops.start();
                return sops.getLogs();
            } catch (RuntimeException failed) {
                return "failed: " + failed.getMessage() + " " + sops.getLogs();
            }
        }
    }

    /**
     * A run of the family step performed on a lane by a service that asks for
     * one secret: what it was handed, or why it failed, and the run's key.
     */
    private String[] reachedAs(cloud.jengu.dbo.runner.Lane lane, String key, String name)
            throws Exception {
        var started = manager.runInitiator().starting(OWNER, CONNECTIONS, java.util.Map.of(
                        "organisation", cloud.jengu.dbo.work.RunInitiator.Slot.object(
                                organisation("lab-" + key + "-" + System.nanoTime(), "The lab"))),
                key + "-" + System.nanoTime());
        assertEquals(201, started.status(), started.body());
        Run run = new Runs(engine()).byKey(started.key()).orElseThrow();
        Run held = lane.claim(run, java.time.Duration.ofMinutes(5)).orElseThrow(
                () -> new AssertionError("nothing took " + started.key()));
        String[] handed = new String[1];
        cloud.jengu.dbo.runner.Outcome outcome = cloud.jengu.dbo.runner.Performing.performed(
                lane, held, new cloud.jengu.dbo.runner.StepService() {
                    @Override
                    public String step() {
                        return CONNECTIONS;
                    }

                    @Override
                    public cloud.jengu.dbo.runner.Outcome perform(
                            cloud.jengu.dbo.runner.Work work) {
                        handed[0] = work.secret(name);
                        return cloud.jengu.dbo.runner.Outcome.done();
                    }
                }, java.time.Duration.ofMinutes(5));
        if (outcome instanceof cloud.jengu.dbo.runner.Outcome.Failed failed) {
            return new String[] {"failed: " + failed.reason(), started.key()};
        }
        return new String[] {handed[0], started.key()};
    }

    private static cloud.jengu.dbo.runner.Lane declared(cloud.jengu.dbo.runner.Lane lane,
            String step) {
        int dot = step.lastIndexOf('.');
        lane.declare(new cloud.jengu.dbo.work.Declarations.Declared(step.substring(0, dot),
                step.substring(dot + 1), lane.identity().name(), lane.identity().version(),
                lane.identity().provider(), lane.identity().scope(), lane.identity().name()));
        return lane;
    }

    /**
     * A run of a step started and performed on a lane by a service that asks
     * for the lab's password: what it was handed, or why it failed.
     */
    private String reached(cloud.jengu.dbo.runner.Lane lane, String step, String key)
            throws Exception {
        var started = manager.runInitiator().starting(OWNER, step, java.util.Map.of(
                        "organisation", cloud.jengu.dbo.work.RunInitiator.Slot.object(
                                organisation("lab-" + key, "The lab"))),
                key + "-" + System.nanoTime());
        assertEquals(201, started.status(), started.body());
        Run run = new Runs(engine()).byKey(started.key()).orElseThrow();
        Run held = lane.claim(run, java.time.Duration.ofMinutes(5)).orElseThrow(
                () -> new AssertionError("nothing took " + started.key()));
        String[] handed = new String[1];
        cloud.jengu.dbo.runner.Outcome outcome = cloud.jengu.dbo.runner.Performing.performed(
                lane, held, new cloud.jengu.dbo.runner.StepService() {
                    @Override
                    public String step() {
                        return step;
                    }

                    @Override
                    public cloud.jengu.dbo.runner.Outcome perform(
                            cloud.jengu.dbo.runner.Work work) {
                        handed[0] = work.secret("lab-connection-password");
                        return cloud.jengu.dbo.runner.Outcome.done();
                    }
                }, java.time.Duration.ofMinutes(5));
        if (outcome instanceof cloud.jengu.dbo.runner.Outcome.Failed failed) {
            return "failed: " + failed.reason();
        }
        return handed[0];
    }

    private String tokenOf(String client, String secret) {
        try {
            String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=client_credentials&client_id=" + client
                                            + "&client_secret=" + secret)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return field(body, "access_token");
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
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

    @Test
    @Order(12)
    @DisplayName("the repository is cloned only by a client the tenant issued that may read it, "
            + "and a branch is pushed only by one granted that branch")
    @Proving(DboPromises.TEN_A_TENANTS_CONFIGURATION_REPOSITORY_IS_SERVED_UNDER_ITS_AUTHORITY)
    void itIsServedUnderTheTenantsAuthority() throws Exception {
        String remote = base() + "/configuration.git";
        Path nobody = Files.createTempDirectory("dbo-owner-config-nobody");
        assertThrows(org.eclipse.jgit.api.errors.TransportException.class,
                () -> Git.cloneRepository().setURI(remote).setDirectory(nobody.toFile()).call()
                        .close(), "the repository was cloned without signing in");

        manager.authority(OWNER).ensureClient("auditor-git", "auditor-git-secret",
                List.of("config/read"));
        manager.authority(OWNER).ensureClient("ada-git", "ada-git-secret",
                List.of("config/read", "config/push:ada"));
        Path auditor = Files.createTempDirectory("dbo-owner-config-auditor");
        try (Git read = Git.cloneRepository().setURI(remote).setDirectory(auditor.toFile())
                .setCredentialsProvider(signedIn("auditor-git")).call()) {
            assertEquals(git.getRepository().resolve("main"), read.getRepository()
                    .resolve("origin/main"), "the clone is not what the owner pushed");
            assertThrows(org.eclipse.jgit.api.errors.TransportException.class,
                    () -> read.push().setCredentialsProvider(signedIn("auditor-git")).call(),
                    "a client that may only read pushed");
        }

        Path ada = Files.createTempDirectory("dbo-owner-config-ada");
        try (Git hers = Git.cloneRepository().setURI(remote).setDirectory(ada.toFile())
                .setCredentialsProvider(signedIn("ada-git")).call()) {
            Files.writeString(ada.resolve("draft.json"), organisation("ward-12", "Ward 12"));
            hers.add().addFilepattern(".").call();
            hers.commit().setMessage("A ward of her own").setAuthor("Ada", "ada@example.org")
                    .setCommitter("Ada", "ada@example.org").call();
            assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK,
                    pushedAs(hers, "ada-git", "HEAD:refs/heads/ada"),
                    "her own branch was refused");
            assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
                    pushedAs(hers, "ada-git", "HEAD:refs/heads/main"),
                    "the applied branch took a push from somebody granted only her own");
            hers.tag().setName("hers").call();
            assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
                    pushedAs(hers, "ada-git", "refs/tags/hers:refs/tags/hers"),
                    "a tag was kept where only branches are");
        }
        assertEquals(git.getRepository().resolve("main"), lsRemote("main"),
                "a refused push moved the applied branch");
    }

    private static org.eclipse.jgit.transport.CredentialsProvider signedIn(String client) {
        return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(client,
                client + "-secret");
    }

    private org.eclipse.jgit.transport.RemoteRefUpdate.Status pushedAs(Git from, String client,
            String refSpec) throws Exception {
        org.eclipse.jgit.transport.RemoteRefUpdate.Status status = null;
        for (org.eclipse.jgit.transport.PushResult result : from.push()
                .setRemote(base() + "/configuration.git")
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(refSpec))
                .setCredentialsProvider(signedIn(client)).call()) {
            for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                status = update.getStatus();
            }
        }
        return status;
    }

    private org.eclipse.jgit.lib.ObjectId lsRemote(String branch) throws Exception {
        return Git.lsRemoteRepository().setRemote(base() + "/configuration.git")
                .setCredentialsProvider(signedIn("owner-git")).callAsMap()
                .get("refs/heads/" + branch).getObjectId();
    }

    // ── the repository ──

    private void write(String path, String content) throws Exception {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Committed, and pushed to the store as the owner pushes it, once the tenant is up. */
    private RevCommit commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        PersonIdent ada = new PersonIdent("Ada Lovelace", "ada@example.org");
        RevCommit committed = git.commit().setMessage(message).setAuthor(ada).setCommitter(ada)
                .call();
        if (manager != null) {
            pushed("main");
        }
        return committed;
    }

    /** A branch pushed to the tenant's repository, which the store must have taken. */
    private void pushed(String branch) throws Exception {
        for (org.eclipse.jgit.transport.PushResult result : git.push()
                .setRemote(base() + "/configuration.git")
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                        "+refs/heads/" + branch + ":refs/heads/" + branch))
                .setCredentialsProvider(new org.eclipse.jgit.transport
                        .UsernamePasswordCredentialsProvider("owner-git", "owner-git-secret"))
                .call()) {
            for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                assertTrue(update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate
                                .Status.OK
                                || update.getStatus() == org.eclipse.jgit.transport
                                .RemoteRefUpdate.Status.UP_TO_DATE,
                        "the store did not take " + branch + ": " + update.getStatus() + " "
                                + update.getMessage());
            }
        }
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
