package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application writes a bean. The container writes everything else.
 *
 * <p>Every other class about the fleet builds a {@code StepConsumer} by hand,
 * which is honest about what a consumer does and dishonest about what an
 * application does: a bean handed to a consumer somebody constructed is a bean
 * that could only ever be reached by the code that constructed it. This is the
 * class that asks the reachability question — who builds the consumer in a
 * deployment nobody wrote a test harness for — and the answer has to be the
 * deployment.
 *
 * <p>So nothing here constructs a consumer, a durable layer or a pool. A bean
 * is registered, and the proof is that work arrives at it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ABeanIsFoundRatherThanWiredIT {

    private static final String TENANT = "foundbean";
    /** Its own code, because a step's substrate is a database named from it. */
    private static final String STEP = "fleet.found.sweep";
    /** Placed on the same substrate, to prove one consumer comes of two steps. */
    private static final String BESIDE_IT = "fleet.found.expire";
    /** Declared by no deployment anywhere, which is the point of it. */
    private static final String NEVER_DECLARED = "fleet.found.nothing";
    /** Over a type whose identifier is an identifying element. */
    private static final String OVER_A_PERSON = "fleet.found.person";

    private static final StepDeclaration SWEEP =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration EXPIRE =
            StepDeclaration.of(BESIDE_IT, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    private static final Executor AS =
            new Executor("found-bean", "1", "cloud.jengu.test", Scope.BASELINE);

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Runs runs;
    final ConcurrentLinkedQueue<String> swept = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> expired = new ConcurrentLinkedQueue<>();

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-foundbean");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ABeanIsFoundRatherThanWiredIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        // THE BEANS FIRST, before the deployment has read a declaration. This
        // is the order an assembly actually produces — the application's beans
        // are constructed while the container is still coming up — and a
        // registration refused here would make the whole arrangement work only
        // in a test that happened to go the other way round.
        manager.performing(bean(STEP, swept), AS);
        manager.performing(bean(BESIDE_IT, expired), AS);
        manager.performing(bean(NEVER_DECLARED, new ConcurrentLinkedQueue<>()), AS);

        Path managementSpec = Files.createTempDirectory("dbo-management").resolve("reg.json");
        Files.writeString(managementSpec, """
                {"code":"reg","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"opens":["record"],
                    "substrate":"found"},
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"opens":["record"],
                    "substrate":"found"},
                   {"code":"%s","slots":{"who":"Reference(Person)"},"opens":["who"],
                    "substrate":"found"}]}"""
                .formatted(STEP, BESIDE_IT, OVER_A_PERSON));
        manager.manages(managementSpec);

        // BEHIND THE MEMBRANE, and holding a Person keyed by a number. Both
        // are for the last two tests: what makes a search identifying is the
        // element it matches on, and only a tenant with pdi has any.
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"types":[
                   {"name":"Basic","identity":"internal","handling":"operational"},
                   {"name":"Person","identity":"identifier",
                    "systems":["urn:found:nid"],"handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine());
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
    @DisplayName("a bean registered before the declaration was read is performing work after "
            + "it, and is handed the object the run referred to")
    @Proving({DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED,
            DboPromises.PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS})
    void heldThenTakenUp() throws Exception {
        authorRunOf(SWEEP);
        assertTrue(manager.stepJoiner().orElseThrow().joinOnce(100) >= 1,
                "the joiner offered nothing, so this proves nothing about the bean");

        assertTrue(until(() -> !swept.isEmpty()),
                "the bean registered before the deployment declared its steps was never "
                        + "called, so an application whose beans come up first performs "
                        + "nothing — which is the ordinary order under an assembly");

        // AND IT WAS HANDED THE OBJECT. Being called is not the claim: a
        // performer runs outside the store with no route into the tenant, so a
        // bean called with an empty map has been given the fact that there is
        // work and nothing to do it with — which looks identical from here
        // unless the slot is asserted.
        assertTrue(swept.stream().anyMatch(done -> done.endsWith("[record]")),
                "the bean was called with no slots, so the run's inputs were never resolved "
                        + "and the reference it was authored with reached a process that "
                        + "cannot resolve one: " + swept);
    }

    @Test
    @Order(2)
    @DisplayName("two steps placed on one substrate are served without a second consumer, and "
            + "without the application knowing either was placed")
    @Proving(DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED)
    void oneConsumerForTheSubstrate() throws Exception {
        // A consumer's queues are fixed when it launches. Registering the
        // second bean therefore only works if the first registration built a
        // consumer over EVERY step its substrate carries — which is a decision
        // the application cannot make, because placement is the deployment's.
        authorRunOf(EXPIRE);
        manager.stepJoiner().orElseThrow().joinOnce(100);

        assertTrue(until(() -> !expired.isEmpty()),
                "the step placed beside the first was never performed, so a deployment that "
                        + "places two steps together gets one of them performed");
    }

    @Test
    @Order(3)
    @DisplayName("a bean whose step nothing declares is named rather than left quiet")
    @Proving(DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED)
    void whatWillNeverBeCalledIsNamed() {
        assertEquals(Set.of(NEVER_DECLARED), manager.awaitingDeclaration(),
                "a bean naming a step this deployment does not declare is indistinguishable "
                        + "from a step with nothing to do, and the deployment is the only "
                        + "thing that can tell the difference");
    }

    @Test
    @Order(4)
    @DisplayName("a participant asks the tenant's own door for a run of the DEPLOYMENT's step, "
            + "and the door takes it as readily as one the tenant declared")
    @Proving(DboPromises.PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM)
    void theDoorTakesAStepTheDeploymentDeclared() throws Exception {
        // A CREDENTIAL THAT MAY ACT IN WORK, which is all an initiator needs.
        // There is no second enrolment for asking as against performing: the
        // participant that may take work of a step may ask for work of it.
        // 'work', not 'work/<step>'. The step-scoped grant is what a lane
        // claims with; the step DOOR admits a credential that may act in work
        // at all, and the two are deliberately not the same scope.
        manager.authority(TENANT).ensureClient("asker", "asker-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + STEP));
        String record = manager.runtime(TENANT).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();

        var answered = post("/t/" + TENANT + "/step/" + STEP,
                "{\"inputs\":{\"record\":\"Basic/" + record + "\"}}");

        // 201: the tenant never declared this step and never could — a step
        // code belongs to one level — and its door starts a run of it anyway,
        // because the DEPLOYMENT declared it. That is the difference from a
        // step a participant merely introduced, which the door goes on
        // refusing.
        assertEquals(201, answered.statusCode(),
                "the tenant's own door would not start a run of the deployment's step, so "
                        + "there is nowhere for fleet work to be authored and the joiner reads "
                        + "tenants: " + answered.body());

        var unknown = post("/t/" + TENANT + "/step/" + NEVER_DECLARED,
                "{\"inputs\":{\"record\":\"Basic/" + record + "\"}}");
        assertEquals(404, unknown.statusCode(),
                "the door started a run of a step no level declares, so asking for one is a "
                        + "way in rather than a capability: " + unknown.body());
    }

    @Test
    @Order(5)
    @DisplayName("a slot is filled by a search, and the run records what it matched rather "
            + "than the search")
    @Proving(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH)
    void aReferenceMayBeASearch() throws Exception {
        manager.authority(TENANT).ensureClient("asker", "asker-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + STEP));
        // Two records, so that "it matched one" is a fact about the search
        // rather than about there being only one Basic in the tenant.
        String wanted = basicWith("the-one-wanted");
        basicWith("another-entirely");

        var answered = post("/t/" + TENANT + "/step/" + STEP,
                "{\"inputs\":{\"record\":\"Basic?code=the-one-wanted\"}}");
        assertEquals(201, answered.statusCode(),
                "a slot filled by a search was not taken, so naming a record by what is known "
                        + "about it is not a way to author work: " + answered.body());

        // THE RUN RECORDS THE REFERENCE, not the search. What the work is over
        // is fixed when the work is created: a run that kept the query would
        // be over whatever matched at the moment somebody got round to it.
        String key = between(answered.body(), "\"key\":\"", "\"");
        cloud.jengu.dbo.work.Run authored =
                new cloud.jengu.dbo.work.Runs(manager.runtime(TENANT).orElseThrow().engine())
                        .byKey(key).orElseThrow(() -> new AssertionError("no run " + key));
        assertEquals(java.util.List.of("Basic/" + wanted),
                authored.inputs().get("record").values(),
                "the run did not record the reference the search matched, so what it is over "
                        + "can still change underneath it");
    }

    @Test
    @Order(6)
    @DisplayName("a search matching several fills no slot that takes one, and says how many")
    @Proving(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH)
    void aSearchThatMatchesSeveralIsRefused() throws Exception {
        manager.authority(TENANT).ensureClient("asker", "asker-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + STEP));
        basicWith("two-of-these");
        basicWith("two-of-these");

        var answered = post("/t/" + TENANT + "/step/" + STEP,
                "{\"inputs\":{\"record\":\"Basic?code=two-of-these\"}}");

        // Refused rather than resolved to the first. A run over one of two
        // matches is a run over whichever the index happened to return, and
        // nothing downstream could tell that had happened.
        assertEquals(400, answered.statusCode(),
                "a search matching two filled a slot that takes one, so a run was authored "
                        + "over whichever came back first: " + answered.body());
        assertTrue(answered.body().contains("matched 2"),
                "the refusal does not say how many it matched, which is the one thing the "
                        + "caller needs to narrow it: " + answered.body());
    }

    @Test
    @Order(7)
    @DisplayName("a search on an identifying element is refused at the door, whatever purpose "
            + "is stated")
    @Proving(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH)
    void anIdentifyingSearchIsRefusedHere() throws Exception {
        manager.authority(TENANT).ensureClient("asker", "asker-secret",
                java.util.List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + OVER_A_PERSON));
        // Somebody who IS here, so that the refusal is about the question
        // rather than about there being nobody to find.
        manager.runtime(TENANT).orElseThrow().engine().put(PutRequest.create("Person",
                ("{\"resourceType\":\"Person\",\"identifier\":[{\"system\":\"urn:found:nid\","
                        + "\"value\":\"38102030405\"}]}").getBytes(StandardCharsets.UTF_8)));

        var refused = post("/t/" + TENANT + "/step/" + OVER_A_PERSON,
                "{\"inputs\":{\"who\":\"Person?identifier=urn:found:nid|38102030405\"}}");

        assertEquals(400, refused.statusCode(),
                "the door matched on an identifying element, so a credential for work can ask "
                        + "whether a person with a given number is here: " + refused.body());
        assertTrue(refused.body().contains("no stated purpose will change that"),
                "the refusal does not say that stating a purpose is not the way through, so a "
                        + "caller will try one: " + refused.body());

        // AND A PURPOSE DOES NOT OPEN IT. This is the whole claim: on the
        // records surface a stated purpose turns an identifying search into an
        // exact lookup through the vault, and this door states none and accepts
        // none — so the header is inert here rather than a way round.
        var withPurpose = post("/t/" + TENANT + "/step/" + OVER_A_PERSON,
                "{\"inputs\":{\"who\":\"Person?identifier=urn:found:nid|38102030405\"}}",
                "TREAT");
        assertEquals(400, withPurpose.statusCode(),
                "stating a purpose opened an identifying search at the step door, so the "
                        + "refusal above is advice rather than a rule: " + withPurpose.body());
    }

    /** A Basic whose code text is searchable, and its id. */
    private String basicWith(String code) {
        return manager.runtime(TENANT).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        ("{\"resourceType\":\"Basic\",\"code\":{\"coding\":[{\"code\":\""
                                + code + "\"}]}}").getBytes(StandardCharsets.UTF_8))).id();
    }

    private static String between(String body, String after, String before) {
        int from = body.indexOf(after) + after.length();
        return body.substring(from, body.indexOf(before, from));
    }

    /** As a participant reaches the door: a work credential, and JSON. */
    private java.net.http.HttpResponse<String> post(String path, String body) throws Exception {
        return post(path, body, null);
    }

    /** The same, stating a purpose — which this door is supposed to ignore. */
    private java.net.http.HttpResponse<String> post(String path, String body, String purpose)
            throws Exception {
        String form = "grant_type=client_credentials&client_id=asker"
                + "&client_secret=asker-secret";
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + manager.port();
        String granted = http.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(base + "/t/" + TENANT + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        java.util.regex.Matcher found = java.util.regex.Pattern
                .compile("\"access_token\":\"([^\"]+)\"").matcher(granted);
        assertTrue(found.find(), granted);
        var asking = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + found.group(1));
        if (purpose != null) {
            asking.header("Purpose-Of-Use", purpose);
        }
        return http.send(
                asking.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /** A bean that records what it was handed and reports nothing else. */
    private cloud.jengu.dbo.runner.StepService bean(String step,
            ConcurrentLinkedQueue<String> into) {
        return new cloud.jengu.dbo.runner.StepService() {
            @Override
            public String step() {
                return step;
            }

            @Override
            public cloud.jengu.dbo.runner.Outcome perform(cloud.jengu.dbo.runner.Work work) {
                // THE SLOT, not just the fact of being called. A bean handed an
                // empty map would look exactly like a bean handed its work, and
                // the whole point of reaching it is that it can do something —
                // so what is recorded is that the object arrived, and which
                // tenant it was about.
                into.add(work.tenant() + "/" + work.run().key() + "/"
                        + work.inputs().keySet());
                return cloud.jengu.dbo.runner.Outcome.done();
            }
        };
    }

    private void authorRunOf(StepDeclaration step) {
        String record = manager.runtime(TENANT).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();
        runs.of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private static boolean until(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return done.getAsBoolean();
    }
}
