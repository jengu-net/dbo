package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.stream.LaneClaims;
import cloud.jengu.dbo.stream.StepConsumer;
import cloud.jengu.dbo.tenant.FleetRegister;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.RegisterVersusTrail;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.UnapprovedProcessing;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.work.Holder;
import cloud.jengu.dbo.work.Run;
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
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link cloud.jengu.dbo.promises.DboStories#A_STEP_IS_RUN_FOR_THE_FLEET}: the
 * operator running a step for every tenant looks at what only the deployment
 * can see.
 *
 * <p>Which tenants admitted the step or declined it, what each tenant's
 * register says is opened of its data, the rows nobody authorised and the
 * posture each obeys, a processor enrolled on every tenant, a substrate
 * prepared for each step and kept when the step is withdrawn, a writeback held
 * to each tenant's own rules, and one bean found for a step rather than wired
 * to it.
 *
 * <p><b>Not in Rowling Land</b>, for two reasons a reader can check. The
 * register, the disagreement incidents, the unauthorised rows, a processor's
 * enrolment and the beans awaiting a declaration are answered only inside the
 * deployment's own process: no door serves them, so a story acting through
 * the sample application cannot ask. And the rest needs the management tenant
 * to declare what {@code mom} does not: two steps placed on one substrate, a
 * step every tenant must accept, a step that waits for approval, a step
 * withdrawn, and a processor named.
 *
 * <p><b>One runtime of the class's own</b>, under one management declaration
 * that carries every step these legs ask about. Each leg group has step codes
 * of its own, because a step's substrate is a database named from its code
 * and the suite shares one Postgres, and tenants of its own, so what one group
 * declares is never what another counts. The register is derived from the
 * whole declaration, so a leg reading it reads only its own steps' rows. The
 * declaration grows inside the enrolment legs and loses a step in the last
 * leg on this runtime, so those legs run in that order and nothing after them
 * reads a register.
 *
 * <p><b>The rest stays on the harness's shared runtime</b>, whose deployment
 * already declares the steps those legs need: a consumer built, closed and
 * rebuilt by hand, two consumers on one substrate, a router that opens what
 * it declared it would not, and a report the step's own declaration refuses.
 * Those legs build their consumers over the shared deployment's substrates
 * and close them themselves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AStepIsRunForTheFleetIT {

    // ---- The management tenant, which declares every step below. ----

    /** Named in a refusal as where a requirement is written, and asserted on. */
    private static final String MANAGEMENT = "registry";

    // ---- A bean found rather than wired. ----

    private static final String FOUND = "foundbean";
    private static final String FOUND_SWEEP = "fleet.found.sweep";
    /** Placed on the same substrate, to prove one consumer comes of two steps. */
    private static final String FOUND_EXPIRE = "fleet.found.expire";
    /** Declared by no deployment anywhere, which is the point of it. */
    private static final String FOUND_NOTHING = "fleet.found.nothing";
    /** Over a type whose identifier is an identifying element. */
    private static final String FOUND_PERSON = "fleet.found.person";

    private static final StepDeclaration FOUND_SWEEPING =
            StepDeclaration.of(FOUND_SWEEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration FOUND_EXPIRING =
            StepDeclaration.of(FOUND_EXPIRE, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    private static final Executor FOUND_AS =
            new Executor("found-bean", "1", "cloud.jengu.test", Scope.BASELINE);

    // ---- A processor enrolled per tenant. ----

    private static final String ENROL_ONE = "enrolone";
    private static final String ENROL_TWO = "enroltwo";
    private static final String PROCESSOR = "fleet-processor";
    private static final String ENROL_STEP = "fleet.enrolling.normalise";
    private static final String ENROL_SECOND = "fleet.enrolling.review";
    private static final String ENROL_THIRD = "fleet.enrolling.third";

    // ---- A tenant admits or declines. ----

    private static final String ADMITS = "admits";
    private static final String DECLINES = "declines";
    private static final String ADMIT_OPTIONAL = "fleet.admitting.normalise";
    private static final String ADMIT_REQUIRED = "fleet.admitting.retain";

    private static final StepDeclaration ADMIT_NORMALISING =
            StepDeclaration.of(ADMIT_OPTIONAL, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    // ---- An unauthorised row obeys its posture. ----

    private static final String POSTURES = "postures";
    private static final String POSTURE_NAMED = "fleet.posture.named";
    private static final String POSTURE_WITHHELD = "fleet.posture.withheld";
    private static final String POSTURE_APPLIED = "fleet.posture.applied";

    // ---- Declaring a step prepares its substrate. Two share one by naming it;
    // the third names none. ----

    private static final String RETENTION_SWEEP = "fleet.retention.sweep";
    private static final String RETENTION_EXPIRE = "fleet.retention.expire";
    private static final String CODING_ALONE = "fleet.coding.normalise";

    // ---- On the shared deployment: what a tenant reads of what is opened. ----

    /** Opens a slot, so it is on the register. */
    private static final String READ_PROCESSOR = SharedTenants.Fleet.READ_OPENED.code();
    /** Opens nothing — a router, and not on the register at all. */
    private static final String READ_ROUTER = SharedTenants.Fleet.READ_ROUTER.code();

    private static final StepDeclaration READ_ROUTING =
            StepDeclaration.of(READ_ROUTER, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    // ---- On the shared deployment: one bean for every tenant. ----

    private static final String TIDIED = SharedTenants.Fleet.TIDIED.code();
    /** A second step on the SAME substrate, which is what placement is for. */
    private static final String TIDIED_BESIDE = SharedTenants.Fleet.TIDIED_BESIDE.code();

    private static final StepDeclaration TIDY_SWEEPING =
            StepDeclaration.of(TIDIED, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration TIDY_EXPIRING =
            StepDeclaration.of(TIDIED_BESIDE, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    // ---- On the shared deployment: the writeback. ----

    /**
     * A name of these legs' own, asserted on below.
     *
     * <p>The tenant is shared and a participant name is claimed by identity: a
     * neighbour enrolling "performing-bean" WITH a key makes a performer's
     * inputs travel sealed, and the refusal asserted on would come back as a
     * sealing complaint rather than the action the step never declared.
     */
    private static final String WRITEBACK_PERFORMER = "writeback-bean";

    private static final String WRITTEN = SharedTenants.Fleet.WRITTEN_BACK.code();
    /** Declares OPEN and not CLOSE, so closing it is a rule to break. */
    private static final String JUDGED = SharedTenants.Fleet.WRITTEN_BACK_JUDGED.code();

    private static final StepDeclaration WRITTEN_SWEEPING =
            StepDeclaration.of(WRITTEN, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration JUDGED_REVIEWING =
            StepDeclaration.of(JUDGED, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record")
                    .containing("open");

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path dir;
    Path managementSpec;
    /** Every step the management tenant declares, in the order it declares them. */
    final Map<String, String> declared = new LinkedHashMap<>();

    Runs foundRuns;
    final ConcurrentLinkedQueue<String> swept = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> expired = new ConcurrentLinkedQueue<>();

    KeyPair sealing;
    KeyPair signing;

    SharedTenants.Tenant sharedOne;
    SharedTenants.Tenant sharedTwo;
    /** The performer's own signing half, which an opening it reports is signed with. */
    KeyPair signingOfThePerformer;

    StepConsumer tidyConsumer;
    final ConcurrentLinkedQueue<String> performed = new ConcurrentLinkedQueue<>();
    StepConsumer writebackConsumer;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-fleet");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AStepIsRunForTheFleetIT"),
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
        manager.performing(bean(FOUND_SWEEP, swept), FOUND_AS);
        manager.performing(bean(FOUND_EXPIRE, expired), FOUND_AS);
        manager.performing(bean(FOUND_NOTHING, new ConcurrentLinkedQueue<>()), FOUND_AS);

        // The processor's own pair, generated where the processor is. Only the
        // public halves are handed over, which is what makes handing them over
        // safe: a copy of what a tenant records opens nothing. Named before any
        // tenant comes up, because a tenant is enrolled as it comes up.
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.processor(new TenantRuntimeManager.Processor(PROCESSOR,
                ParticipantKey.of(sealing.getPublic()), SigningKey.of(signing.getPublic())));

        String basic = "{\"record\":\"Reference(Basic)\"}";
        declared.put(FOUND_SWEEP, step(FOUND_SWEEP, basic,
                ",\"opens\":[\"record\"],\"substrate\":\"found\""));
        declared.put(FOUND_EXPIRE, step(FOUND_EXPIRE, basic,
                ",\"opens\":[\"record\"],\"substrate\":\"found\""));
        declared.put(FOUND_PERSON, step(FOUND_PERSON, "{\"who\":\"Reference(Person)\"}",
                ",\"opens\":[\"who\"],\"substrate\":\"found\""));
        declared.put(ENROL_STEP, enrolling(ENROL_STEP));
        declared.put(ADMIT_OPTIONAL, step(ADMIT_OPTIONAL, basic,
                ",\"opens\":[\"record\"],\"substrate\":\"admitting\""));
        declared.put(ADMIT_REQUIRED, step(ADMIT_REQUIRED, basic,
                ",\"required\":true,\"substrate\":\"admitting\""));
        declared.put(POSTURE_NAMED, step(POSTURE_NAMED, basic,
                ",\"opens\":[\"record\"],\"substrate\":\"postures\""));
        declared.put(POSTURE_WITHHELD, step(POSTURE_WITHHELD, basic,
                ",\"opens\":[\"record\"],\"posture\":\"not-until-approved\","
                        + "\"substrate\":\"postures\""));
        declared.put(POSTURE_APPLIED, step(POSTURE_APPLIED, basic,
                ",\"opens\":[\"record\"],\"posture\":\"applied\",\"substrate\":\"postures\""));
        String observation = "{\"record\":\"Reference(Observation)\"}";
        declared.put(RETENTION_SWEEP, step(RETENTION_SWEEP, observation,
                ",\"substrate\":\"retention\""));
        declared.put(RETENTION_EXPIRE, step(RETENTION_EXPIRE, observation,
                ",\"substrate\":\"retention\""));
        declared.put(CODING_ALONE, step(CODING_ALONE, observation, ""));
        managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        declare();

        // BEHIND THE MEMBRANE, and holding a Person keyed by a number. Both
        // are for the identifying search: what makes a search identifying is
        // the element it matches on, and only a tenant with pdi has any.
        Files.writeString(dir.resolve(FOUND + ".json"), """
                {"code":"%s","face":"r4","pdi":true,"types":[
                   {"name":"Basic","identity":"internal","handling":"operational"},
                   {"name":"Person","identity":"identifier",
                    "systems":["urn:found:nid"],"handling":"operational"}]}"""
                .formatted(FOUND));
        Files.writeString(dir.resolve(ENROL_ONE + ".json"), basicTenant(ENROL_ONE, ""));
        Files.writeString(dir.resolve(ENROL_TWO + ".json"), basicTenant(ENROL_TWO, ""));
        // One tenant says nothing, which admits both. One declines the
        // optional step, which is the whole of what a tenant has to write.
        Files.writeString(dir.resolve(ADMITS + ".json"), basicTenant(ADMITS, ""));
        Files.writeString(dir.resolve(DECLINES + ".json"),
                basicTenant(DECLINES, ",\"declines\":[\"" + ADMIT_OPTIONAL + "\"]"));
        // AUTHORISING NOTHING, which is the state every posture leg is about.
        Files.writeString(dir.resolve(POSTURES + ".json"), basicTenant(POSTURES, ""));
        UntilServed.scan(manager, FOUND, ENROL_ONE, ENROL_TWO, ADMITS, DECLINES, POSTURES);
        foundRuns = new Runs(manager.runtime(FOUND).orElseThrow().engine());

        // THE SHARED DEPLOYMENT, for the legs whose steps it already declares.
        // Two tenants of one shape, because one bean answering for both is the
        // claim; a numbered pair is how a shared world hands out two that
        // cannot see each other. R4_INTERNAL audits its writes, and what the
        // incident leg reads is the access trail.
        SharedTenants.deploymentPerforms();
        sharedOne = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL, 1);
        sharedTwo = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL, 2);
        SharedTenants.manager().fleetLane(sharedOne.code(), READ_ROUTER, readingPerformer())
                .orElseThrow().introduce(READ_ROUTING);
        SharedTenants.manager().fleetLane(sharedOne.code(), WRITTEN, writebackPerformer())
                .orElseThrow().introduce(WRITTEN_SWEEPING);
        SharedTenants.manager().fleetLane(sharedOne.code(), JUDGED, writebackPerformer())
                .orElseThrow().introduce(JUDGED_REVIEWING);

        // THE PERFORMER IS ENROLLED, and that is load-bearing rather than
        // setup. The access entry the comparison reads is written on the
        // SEALED path — a payload opened by a participant the tenant holds
        // keys for — so an unenrolled opener discloses nothing the trail
        // records and there is nothing to disagree with. The shared
        // deployment names no processor, so the enrolment is made by hand.
        KeyPair performerSealing = KeyWrap.newParticipantKeyPair();
        signingOfThePerformer = SigningKey.newKeyPair();
        SharedTenants.manager().authority(sharedOne.code()).ensureClient("performing-bean",
                "performing-secret", List.of(cloud.jengu.dbo.auth.Scopes.WORK),
                ParticipantKey.of(performerSealing.getPublic()),
                SigningKey.of(signingOfThePerformer.getPublic()));
    }

    @AfterAll
    void down() {
        // The consumers built by hand over the shared deployment's substrates;
        // the shared runtime itself outlives this class.
        if (tidyConsumer != null) {
            tidyConsumer.close();
        }
        if (writebackConsumer != null) {
            writebackConsumer.close();
        }
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    // ======== A bean is found rather than wired ========

    @Test
    @Order(1)
    @DisplayName("a bean registered before the declaration was read is performing work after "
            + "it, and is handed the object the run referred to")
    @Proving({DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED,
            DboPromises.PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS})
    void heldThenTakenUp() throws Exception {
        authorFoundRunOf(FOUND_SWEEPING);
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
        authorFoundRunOf(FOUND_EXPIRING);
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
        assertEquals(Set.of(FOUND_NOTHING), manager.awaitingDeclaration(),
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
        manager.authority(FOUND).ensureClient("asker", "asker-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + FOUND_SWEEP));
        String record = manager.runtime(FOUND).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();

        var answered = post("/t/" + FOUND + "/step/" + FOUND_SWEEP,
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

        var unknown = post("/t/" + FOUND + "/step/" + FOUND_NOTHING,
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
        manager.authority(FOUND).ensureClient("asker", "asker-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + FOUND_SWEEP));
        // Two records, so that "it matched one" is a fact about the search
        // rather than about there being only one Basic in the tenant.
        String wanted = basicWith("the-one-wanted");
        basicWith("another-entirely");

        var answered = post("/t/" + FOUND + "/step/" + FOUND_SWEEP,
                "{\"inputs\":{\"record\":\"Basic?code=the-one-wanted\"}}");
        assertEquals(201, answered.statusCode(),
                "a slot filled by a search was not taken, so naming a record by what is known "
                        + "about it is not a way to author work: " + answered.body());

        // THE RUN RECORDS THE REFERENCE, not the search. What the work is over
        // is fixed when the work is created: a run that kept the query would
        // be over whatever matched at the moment somebody got round to it.
        String key = between(answered.body(), "\"key\":\"", "\"");
        Run authored = new Runs(manager.runtime(FOUND).orElseThrow().engine())
                .byKey(key).orElseThrow(() -> new AssertionError("no run " + key));
        assertEquals(List.of("Basic/" + wanted),
                authored.inputs().get("record").values(),
                "the run did not record the reference the search matched, so what it is over "
                        + "can still change underneath it");
    }

    @Test
    @Order(6)
    @DisplayName("a search matching several fills no slot that takes one, and says how many")
    @Proving(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH)
    void aSearchThatMatchesSeveralIsRefused() throws Exception {
        manager.authority(FOUND).ensureClient("asker", "asker-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + FOUND_SWEEP));
        basicWith("two-of-these");
        basicWith("two-of-these");

        var answered = post("/t/" + FOUND + "/step/" + FOUND_SWEEP,
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
        manager.authority(FOUND).ensureClient("asker", "asker-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK, "work/" + FOUND_PERSON));
        // Somebody who IS here, so that the refusal is about the question
        // rather than about there being nobody to find.
        manager.runtime(FOUND).orElseThrow().engine().put(PutRequest.create("Person",
                ("{\"resourceType\":\"Person\",\"identifier\":[{\"system\":\"urn:found:nid\","
                        + "\"value\":\"38102030405\"}]}").getBytes(StandardCharsets.UTF_8)));

        var refused = post("/t/" + FOUND + "/step/" + FOUND_PERSON,
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
        var withPurpose = post("/t/" + FOUND + "/step/" + FOUND_PERSON,
                "{\"inputs\":{\"who\":\"Person?identifier=urn:found:nid|38102030405\"}}",
                "TREAT");
        assertEquals(400, withPurpose.statusCode(),
                "stating a purpose opened an identifying search at the step door, so the "
                        + "refusal above is advice rather than a rule: " + withPurpose.body());
    }

    // ======== A processor is enrolled per tenant ========

    @Test
    @Order(8)
    @DisplayName("each tenant holds the processor's own enrolment, with both public halves and "
            + "nothing that opens or signs")
    @Proving(DboPromises.PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT)
    void enrolledOnEveryTenant() {
        for (String tenant : List.of(ENROL_ONE, ENROL_TWO)) {
            var authority = manager.authority(tenant);
            assertEquals(Optional.of(ParticipantKey.of(sealing.getPublic())),
                    authority.participantKey(PROCESSOR),
                    "tenant '" + tenant + "' holds no key to seal this processor's work to, so "
                            + "its payloads would travel in the clear or not at all");
            assertEquals(Optional.of(SigningKey.of(signing.getPublic())),
                    authority.signingKey(PROCESSOR),
                    "tenant '" + tenant + "' cannot check an opening this processor reports, so "
                            + "its trail could not be trusted to say who opened what");
        }
    }

    @Test
    @Order(9)
    @DisplayName("one enrolment covers every step on the register, so a second step needs no "
            + "second act from the tenant")
    @Proving(DboPromises.PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT)
    void oneEnrolmentCoversEveryStep() throws Exception {
        // SCOPED TO THESE LEGS' STEPS: the register is derived from the whole
        // declaration, and every other leg group's opened slots are on it too.
        assertEquals(1, enrollingRows(ENROL_ONE).size(), String.valueOf(enrollingRows(ENROL_ONE)));

        declared.put(ENROL_SECOND, enrolling(ENROL_SECOND));
        declare();

        assertEquals(2, enrollingRows(ENROL_ONE).size(),
                "the second step is not on the register, so this proves nothing about covering "
                        + "it: " + enrollingRows(ENROL_ONE));
        assertEquals(Optional.of(SigningKey.of(signing.getPublic())),
                manager.authority(ENROL_ONE).signingKey(PROCESSOR),
                "a step was added and the tenant's enrolment no longer answers for it, so "
                        + "enrolment has become one act per step after all");
    }

    @Test
    @Order(10)
    @DisplayName("a tenant that authorised the register it read sees no change, and one that "
            + "read a different register sees one")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void authorisingIsOneComparison() throws Exception {
        assertEquals(Optional.empty(), manager.fleetRegisterChanged(ENROL_ONE),
                "a tenant that never read a register is being told whether it changed, and "
                        + "never having read one is a different answer");

        // EVERY ROW IT READ, in one act. That is what authorising is: all at
        // once from the tenant's side, and individually named so the store can
        // still say which single row is new when the deployment adds one. The
        // whole register, every leg group's rows included, because the
        // comparison is over the whole of it.
        String asItIs = manager.fleetRegister(ENROL_ONE).stream()
                .map(row -> '"' + row.digest() + '"')
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(dir.resolve(ENROL_ONE + ".json"),
                basicTenant(ENROL_ONE, ",\"authorised\":[" + asItIs + "]"));
        manager.scanOnce();

        assertEquals(Optional.of(false), manager.fleetRegisterChanged(ENROL_ONE),
                "a tenant that authorised exactly what is happening is told it changed: "
                        + manager.fleetRegister(ENROL_ONE));

        // The deployment widens what it opens. The tenant's copy stops
        // matching, which is the whole mechanism — a change it can see in one
        // comparison rather than by reading rows.
        declared.put(ENROL_THIRD, enrolling(ENROL_THIRD));
        declare();

        assertEquals(Optional.of(true), manager.fleetRegisterChanged(ENROL_ONE),
                "the deployment added a row it opens and the tenant's authorisation still "
                        + "matches, so a deployment can widen what it reads unnoticed");
    }

    @Test
    @Order(11)
    @DisplayName("moving a row's posture changes the register too, so a deployment cannot "
            + "approve its own widening")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void thePostureIsPartOfWhatWasAuthorised() {
        var asDeclared = enrollingRows(ENROL_ONE);
        String before = FleetRegister.digestOf(asDeclared);

        var moved = asDeclared.stream()
                .map(row -> new FleetRegister.Row(row.step(), row.slot(), row.type(),
                        row.required(), TenantSpec.FleetStep.Posture.APPLIED))
                .toList();

        assertFalse(before.equals(FleetRegister.digestOf(moved)),
                "a row moved from one posture to another leaves the register's value unchanged, "
                        + "so a deployment could move a row from 'not until approved' to "
                        + "'processed and named' without the tenant's copy ceasing to match — "
                        + "which is a deployment approving its own widening");
        assertTrue(before.equals(FleetRegister.digestOf(asDeclared)),
                "the same rows give two values, so no tenant could ever authorise anything");
    }

    // ======== A tenant admits or declines what is done to it ========

    @Test
    @Order(12)
    @DisplayName("a tenant that said nothing has its work offered, and one that declined the "
            + "step has its work left where it is")
    @Proving(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT)
    void decliningMeansNotOffered() throws Exception {
        Run admitted = authorAdmittingRun(ADMITS);
        Run refusedIt = authorAdmittingRun(DECLINES);

        manager.stepJoiner().orElseThrow().joinOnce(200);

        List<String> queued = queuedIds(ADMIT_OPTIONAL);
        assertTrue(queued.contains(FleetWork.idFor(ADMITS, admitted.id())),
                "the tenant that admitted the step by saying nothing had its work left behind, "
                        + "so admitting now takes a declaration after all: " + queued);
        assertFalse(queued.contains(FleetWork.idFor(DECLINES, refusedIt.id())),
                "the tenant that declined the step had its work offered to it anyway, so "
                        + "declining is a note in a file rather than a rule: " + queued);
    }

    @Test
    @Order(13)
    @DisplayName("declining a step the deployment requires is refused by name, saying where "
            + "the requirement is written")
    @Proving(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT)
    void aRequiredStepCannotBeDeclined() throws Exception {
        Files.writeString(dir.resolve(DECLINES + ".json"),
                basicTenant(DECLINES, ",\"declines\":[\"" + ADMIT_REQUIRED + "\"]"));
        manager.scanOnce();

        String said = String.valueOf(manager.troubles().get(DECLINES));
        assertTrue(said.contains(ADMIT_REQUIRED),
                "the refusal does not name the step that cannot be declined: " + said);
        assertTrue(said.contains(MANAGEMENT),
                "it does not say where the requirement is written, so a tenant cannot read the "
                        + "set of them before joining: " + said);
        assertTrue(said.contains("joining"),
                "it reads as a setting rather than as an agreement, which is the one thing a "
                        + "required step is not: " + said);
    }

    // ======== An unauthorised row obeys its posture ========

    @Test
    @Order(14)
    @DisplayName("a row that says not until approved has the tenant's work withheld from the "
            + "step entirely, because approval is known before anything is sealed")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void notUntilApprovedWithholdsTheWork() throws Exception {
        Run named = authorPostureRun(POSTURE_NAMED);
        Run withheld = authorPostureRun(POSTURE_WITHHELD);
        Run applied = authorPostureRun(POSTURE_APPLIED);
        manager.stepJoiner().orElseThrow().joinOnce(200);

        List<String> offered = queuedIds(POSTURE_NAMED);
        assertTrue(offered.contains(FleetWork.idFor(POSTURES, named.id())),
                "the default posture withheld the work, so an unanswered register is an outage "
                        + "caused by nobody clicking: " + offered);
        assertTrue(offered.contains(FleetWork.idFor(POSTURES, applied.id())),
                "work under a row applied by agreement was withheld: " + offered);
        assertFalse(offered.contains(FleetWork.idFor(POSTURES, withheld.id())),
                "work reached a step whose row says not until approved, so the one refusal this "
                        + "design can actually make was not made: " + offered);
    }

    @Test
    @Order(15)
    @DisplayName("the row that ran unauthorised stands as an incident naming what is opened, "
            + "and the row that was withheld does not — nothing happened under it")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void whatRanIsNamedAndWhatDidNotIsNot() {
        List<UnapprovedProcessing.Incident> standing = manager.unapprovedProcessing(POSTURES);
        List<String> steps = standing.stream().map(UnapprovedProcessing.Incident::step).toList();

        assertTrue(steps.contains(POSTURE_NAMED),
                "work went through an unauthorised row and nothing says so, which is the one "
                        + "thing the default posture rests on: " + standing);
        assertFalse(steps.contains(POSTURE_WITHHELD),
                "a row whose work was never offered is reported as processing that happened, "
                        + "so a tenant is told about something that did not: " + standing);
        assertFalse(steps.contains(POSTURE_APPLIED),
                "a row applied under the agreement is reported as unauthorised: " + standing);

        UnapprovedProcessing.Incident said = standing.stream()
                .filter(one -> POSTURE_NAMED.equals(one.step())).findFirst().orElseThrow();
        assertEquals("record", said.slot());
        // THE DECLARED FORM, not the bare type: a tenant deciding about a
        // row is deciding about its OWN data, and Reference(Basic) says
        // that is what is opened where a bare Basic would mean an object
        // handed to the step and never held here.
        assertEquals("Reference(Basic)", said.type());
        assertTrue(said.since().isPresent(),
                "the incident cannot say when this started, so it reads the same on day one "
                        + "and day ninety and nobody acts on it: " + said.says());
        assertTrue(said.says().contains(POSTURES) && said.says().contains("has not authorised"),
                "the line a tenant reads does not say whose data or what is missing: "
                        + said.says());
    }

    @Test
    @Order(16)
    @DisplayName("authorising the rows clears the incident and releases the withheld step, "
            + "per row rather than per register")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void authorisingClearsItPerRow() throws Exception {
        // ONLY THE WITHHELD ROW, so this shows the grain: authorising one row
        // releases that step and leaves the other incident standing.
        String onlyOne = manager.fleetRegister(POSTURES).stream()
                .filter(row -> POSTURE_WITHHELD.equals(row.step()))
                .map(FleetRegister.Row::digest).findFirst().orElseThrow();
        Files.writeString(dir.resolve(POSTURES + ".json"),
                basicTenant(POSTURES, ",\"authorised\":[\"" + onlyOne + "\"]"));
        manager.scanOnce();

        Run nowAllowed = authorPostureRun(POSTURE_WITHHELD);
        manager.stepJoiner().orElseThrow().joinOnce(200);

        List<String> queued = queuedIds(POSTURE_NAMED);
        assertTrue(queued.contains(FleetWork.idFor(POSTURES, nowAllowed.id())),
                "the row was authorised and its work is still withheld, so authorising is a "
                        + "note in a file rather than a release: " + queued);
        assertTrue(manager.unapprovedProcessing(POSTURES).stream()
                        .anyMatch(one -> POSTURE_NAMED.equals(one.step())),
                "authorising one row cleared an incident about a different one, so the grain "
                        + "is the register after all");
    }

    // ======== Declaring a step prepares its substrate ========

    @Test
    @Order(17)
    @DisplayName("two steps naming one substrate share it, and a step naming none gets its own")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void placementIsHonoured() {
        var substrates = manager.stepSubstrates();

        // EVERY STEP DECLARED, and nothing else: this deployment's declaration
        // is the class's own, so the whole map is the claim.
        assertEquals(declared.keySet(), substrates.keySet(),
                "a declared step has nowhere for its work to sit: " + substrates.keySet());
        assertSame(substrates.get(RETENTION_SWEEP), substrates.get(RETENTION_EXPIRE),
                "two steps named one substrate and got two, so naming it twice costs twice "
                        + "the connections — which is the dial this placement decision is");
        assertNotSame(substrates.get(CODING_ALONE), substrates.get(RETENTION_SWEEP),
                "a step that named no substrate was put on somebody else's, so 'no placement "
                        + "stated' silently means shared rather than its own");
    }

    @Test
    @Order(18)
    @DisplayName("the databases exist, named apart from tenants, and carry no store schema")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void theyAreDatabasesAndNotTenants() throws Exception {
        assertTrue(databaseExists(TenantSpec.substrateDatabaseName("retention")),
                "the shared substrate was not created, so two steps point at nothing");
        assertTrue(databaseExists(TenantSpec.substrateDatabaseName(CODING_ALONE)),
                "a step naming no substrate got no database of its own either");

        // Not a tenant: nothing provisioned it as one, so it holds none of
        // what a tenant's database holds. Asked of the database rather than of
        // the code, because the claim is about what was created.
        //
        // A DURABLE BOOTSTRAP AND NOTHING ELSE, which is two assertions and
        // not one. The joiner migrates each substrate as it is built, so
        // `dbos` is here and is supposed to be; what must not be here is any
        // schema of the store's — that would mean this went through the path
        // that provisions a tenant and is now a thing which is not a tenant
        // looking exactly like one.
        try (Connection c = manager.stepSubstrates().get(CODING_ALONE).getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT table_schema, count(*) FROM information_schema.tables "
                                + "WHERE table_schema NOT IN ('pg_catalog','information_schema') "
                                + "GROUP BY table_schema");
                ResultSet rs = ps.executeQuery()) {
            Map<String, Integer> bySchema = new LinkedHashMap<>();
            while (rs.next()) {
                bySchema.put(rs.getString(1), rs.getInt(2));
            }
            assertTrue(bySchema.containsKey("dbos"),
                    "the substrate carries no durable layer, so a step declared has a "
                            + "database and still nowhere for its work to go: " + bySchema);
            assertEquals(Set.of("dbos"), bySchema.keySet(),
                    "a step's substrate carries a schema that is not the durable layer's, so "
                            + "it went through the path that provisions a tenant: " + bySchema);
        }

        assertTrue(TenantSpec.substrateDatabaseName(CODING_ALONE).startsWith("step_"),
                "a step's database is not told apart from a tenant's by name, so `\\l` reads "
                        + "as a list of tenants and two namespaces can collide");
    }

    /**
     * The last leg on this class's own runtime, because it takes a step out of
     * the declaration every leg above reads.
     */
    @Test
    @Order(19)
    @DisplayName("a withdrawn step stops being performed and its substrate stays, because the "
            + "work in it belongs to tenants who believe it is being done")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void withdrawalRemovesNothing() throws Exception {
        declared.remove(CODING_ALONE);
        declare();

        assertTrue(databaseExists(TenantSpec.substrateDatabaseName(CODING_ALONE)),
                "the substrate went when the declaration did, so a configuration change "
                        + "destroyed work that tenants are still waiting on");
    }

    // ======== A tenant reads what is opened of its data (shared deployment) ========

    @Test
    @Order(20)
    @DisplayName("the register says which slot of which step is opened, and a step that only "
            + "routes is not on it")
    @Proving(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA)
    void theRegisterIsWhatIsOpened() {
        // SCOPED TO THESE LEGS' STEPS, because the register is derived from
        // the WHOLE declaration and the deployment is shared: every class's
        // opened slot is on every tenant's register, which is correct and is
        // not what this claim is about. Filtering keeps the claim exactly —
        // of the two steps declared for this, the one that opens is on the
        // register and the one that only routes is not.
        List<FleetRegister.Row> register = SharedTenants.manager()
                .fleetRegister(sharedOne.code()).stream()
                .filter(row -> row.step().equals(READ_PROCESSOR) || row.step().equals(READ_ROUTER))
                .toList();

        assertEquals(1, register.size(),
                "the register does not hold exactly the opened slots, so a tenant reading it "
                        + "cannot tell what is disclosed from what is merely carried: "
                        + register);
        FleetRegister.Row row = register.get(0);
        assertEquals(READ_PROCESSOR, row.step());
        assertEquals("record", row.slot());
        // The declared form, which is what says whose data it is.
        assertEquals("Reference(Basic)", row.type());
        assertTrue(row.required(),
                "the row does not say the tenant cannot decline it, which is the first thing "
                        + "somebody deciding needs to know");
        assertEquals(TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED, row.posture(),
                "the row does not carry the posture, so what happens to work nobody has "
                        + "authorised yet is not readable where the decision is made");
    }

    @Test
    @Order(21)
    @DisplayName("a step the tenant declined contributes no rows, because declined and not "
            + "performed are one fact from its side")
    @Proving(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA)
    void aDeclinedStepIsNotOnTheRegister() {
        // Asked of the derivation directly: the tenant above cannot decline
        // this step, because the deployment requires it — which is the point,
        // and is why the declining case is asked here rather than by writing a
        // declaration the store would rightly refuse.
        List<FleetRegister.Row> declined = FleetRegister.of(
                SharedTenants.manager().fleetRegister(sharedOne.code()).isEmpty()
                        ? List.of() : readingSteps(),
                Set.of(READ_PROCESSOR));

        assertTrue(declined.isEmpty(),
                "a declined step still appears on the register, so a tenant reads rows for "
                        + "processing that will never happen: " + declined);
    }

    @Test
    @Order(22)
    @DisplayName("a step that opens a payload the register does not declare is named as an "
            + "incident in the tenant's own account")
    @Proving(DboPromises.PROC_A_DISAGREEMENT_IS_AN_INCIDENT_NOT_A_REFUSAL)
    void anUndeclaredOpeningIsAnIncident() {
        String tenant = sharedOne.code();
        assertTrue(SharedTenants.manager().fleetDisagreements(tenant).isEmpty(),
                "something already disagrees before anything has opened anything: "
                        + SharedTenants.manager().fleetDisagreements(tenant));

        // THE ROUTER OPENS. Its declaration says it opens nothing, so the
        // register has no row for it — and opening the run's input anyway is
        // exactly the case detection exists for. Nothing stops it, which is
        // the premise rather than a gap.
        Run routed = authorRun(sharedOne.engine(), READ_ROUTING, READ_ROUTER);
        var lane = SharedTenants.manager().fleetLane(tenant, READ_ROUTER, readingPerformer())
                .orElseThrow();
        Run held = lane.claim(routed, Duration.ofMinutes(5)).orElseThrow();
        // SEALED, not in the clear: an enrolled participant is refused its
        // inputs in the clear even when it asks, which is the store being
        // right — a payload for somebody holding a key travels sealed to that
        // key.
        var work = lane.sealed(held);
        assertEquals(1, work.payload().size(),
                "the router was sent nothing to open, so there is nothing for the trail to "
                        + "disagree about");

        // AND THEN IT REPORTS THE OPENING, which is the only way the store can
        // know: it happened where the store cannot see, so what lands on the
        // document is what the participant says it did, signed with the key it
        // enrolled. That asymmetry is the whole reason a disagreement is an
        // incident rather than a refusal — the store is told, it does not
        // permit.
        String reference = held.inputs().get("record").one();
        String previous = work.manifest().head();
        String link = cloud.jengu.dbo.work.RunChain.accessLink(
                previous, held.key(), reference, "performing-bean");
        String signature = SigningKey.sign(
                link.getBytes(StandardCharsets.UTF_8), signingOfThePerformer.getPrivate());
        lane.opened(held, reference, new cloud.jengu.dbo.work.RunChain.Link(
                "access", previous, link, "performing-bean", reference, signature));

        List<RegisterVersusTrail.Incident> said =
                SharedTenants.manager().fleetDisagreements(tenant);
        assertEquals(1, said.size(),
                "the store did not notice a payload opened that the register never declared: "
                        + said);
        RegisterVersusTrail.Incident incident = said.get(0);
        assertEquals(READ_ROUTER, incident.step());
        assertEquals("record", incident.slot());
        assertEquals("performing-bean", incident.by(),
                "the incident does not name who opened it, which is the one thing a tenant "
                        + "has to act on: " + incident.says());
        assertTrue(incident.says().contains("register does not say"),
                "it does not say what was disagreed with: " + incident.says());
    }

    // ======== One bean performs for every tenant (shared deployment) ========

    @Test
    @Order(23)
    @DisplayName("one bean performs work authored in two tenants, naming neither")
    @Proving(DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT)
    void oneBeanTwoTenants() throws Exception {
        authorRun(sharedOne.engine(), TIDY_SWEEPING, TIDY_SWEEPING.id().toString());
        authorRun(sharedTwo.engine(), TIDY_SWEEPING, TIDY_SWEEPING.id().toString());
        assertTrue(SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100) >= 2,
                "the joiner offered fewer than the two runs authored, so this proves nothing "
                        + "about a consumer");

        // The application's side, and all of it: one consumer over the step's
        // substrate, one bean, and no tenant named anywhere in either.
        // BOTH STEPS, because a consumer polls the queues it was built with
        // and registering a bean later does not add one. Which is also the
        // placement claim: two steps sharing a substrate are served by one
        // consumer, one listener and one pool.
        tidyConsumer = new StepConsumer(SharedTenants.manager().stepSubstrates().get(TIDIED),
                Set.of(TIDIED, TIDIED_BESIDE), tidyingClaims());
        tidyConsumer.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED, work -> {
            performed.add(work.tenant() + "/" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));

        assertTrue(until(() -> performed.size() >= 2),
                "one bean over one queue did not perform both tenants' work: " + performed);

        assertEquals(Set.of(sharedOne.code(), sharedTwo.code()),
                performed.stream().map(done -> done.split("/")[0])
                        .collect(java.util.stream.Collectors.toSet()),
                "the two items performed were not one from each tenant, so a queue carrying "
                        + "a fleet's work is carrying one tenant's: " + performed);
    }

    @Test
    @Order(24)
    @DisplayName("a consumer restarted keeps nothing, and work authored while it was gone is "
            + "performed when it comes back")
    @Proving(DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT)
    void itKeepsNothingBetweenAsks() throws Exception {
        tidyConsumer.close();
        tidyConsumer = null;
        performed.clear();

        authorRun(sharedOne.engine(), TIDY_SWEEPING, TIDY_SWEEPING.id().toString());
        SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100);

        tidyConsumer = new StepConsumer(SharedTenants.manager().stepSubstrates().get(TIDIED),
                Set.of(TIDIED, TIDIED_BESIDE), tidyingClaims());
        tidyConsumer.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED, work -> {
            performed.add(work.tenant() + "/" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));

        assertTrue(until(() -> !performed.isEmpty()),
                "work offered while the consumer was down was not performed when it came "
                        + "back, so a restart loses whatever was in flight");
    }

    @Test
    @Order(25)
    @DisplayName("one consumer serves every step on the substrate it was given, and takes work "
            + "that arrives while it is already running")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void oneConsumerServesTheSubstratesQueues() throws Exception {
        // WHAT PLACEMENT IS FOR. Two steps naming one substrate share a
        // database and a pool, and that trade is only real if one consumer can
        // serve both their queues — otherwise a deployment pays a listener and
        // a pool per STEP however it places them, and naming a substrate buys
        // nothing.
        //
        // And it takes work that arrives while it is up, which is the other
        // half: a consumer that drained only what existed when it launched
        // would make the whole lane a startup activity.
        performed.clear();
        assertEquals(SharedTenants.manager().stepSubstrates().get(TIDIED),
                SharedTenants.manager().stepSubstrates().get(TIDIED_BESIDE),
                "the two steps named one substrate and got two, so this proves nothing about "
                        + "serving both from one consumer");

        // BOTH beans recording the same way, so what is counted is which STEP
        // performed rather than which of two spellings a bean happened to use.
        tidyConsumer.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED, work -> {
            performed.add(TIDIED + "|" + work.run().key());
            return cloud.jengu.dbo.runner.Outcome.done();
        }));
        tidyConsumer.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED_BESIDE,
                work -> {
                    performed.add(TIDIED_BESIDE + "|" + work.run().key());
                    return cloud.jengu.dbo.runner.Outcome.done();
                }));

        authorRun(sharedOne.engine(), TIDY_SWEEPING, TIDY_SWEEPING.id().toString());
        authorRun(sharedOne.engine(), TIDY_EXPIRING, TIDY_EXPIRING.id().toString());
        SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(200);

        assertTrue(until(() -> performed.size() >= 2),
                "one consumer did not perform both steps' work from the substrate they share: "
                        + performed);
        assertEquals(Set.of(TIDIED, TIDIED_BESIDE),
                performed.stream().map(done -> done.split("\\|")[0])
                        .collect(java.util.stream.Collectors.toSet()),
                "the two items performed were not one of each step: " + performed);
    }

    @Test
    @Order(26)
    @DisplayName("two consumers on one substrate each take only their own step's work, and "
            + "neither drains the other's")
    @Proving(DboPromises.PROC_A_CONSUMER_TAKES_ONLY_ITS_OWN_STEPS)
    void aConsumerTakesOnlyItsOwnSteps() throws Exception {
        tidyConsumer.close();
        tidyConsumer = null;
        performed.clear();
        ConcurrentLinkedQueue<String> other = new ConcurrentLinkedQueue<>();

        // TWO PROCESSES' WORTH, on one substrate: the shape a deployment takes
        // when it scales one step and leaves the other alone. Each registers
        // its own step, and a queue registered by either is visible to both in
        // the system database — which is exactly the trap.
        try (StepConsumer mine = new StepConsumer(
                        SharedTenants.manager().stepSubstrates().get(TIDIED),
                        Set.of(TIDIED), tidyingClaims());
                StepConsumer theirs = new StepConsumer(
                        SharedTenants.manager().stepSubstrates().get(TIDIED_BESIDE),
                        Set.of(TIDIED_BESIDE), tidyingClaims())) {
            mine.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED, work -> {
                performed.add(TIDIED);
                return cloud.jengu.dbo.runner.Outcome.done();
            }));
            theirs.performing(cloud.jengu.dbo.runner.StepService.performing(TIDIED_BESIDE,
                    work -> {
                        other.add(TIDIED_BESIDE);
                        return cloud.jengu.dbo.runner.Outcome.done();
                    }));

            assertThrows(IllegalArgumentException.class,
                    () -> mine.performing(cloud.jengu.dbo.runner.StepService.performing(
                            TIDIED_BESIDE, work -> cloud.jengu.dbo.runner.Outcome.done())),
                    "a bean was accepted for a step this consumer does not poll, so it would "
                            + "sit there correct and never be called");

            authorRun(sharedOne.engine(), TIDY_SWEEPING, TIDY_SWEEPING.id().toString());
            authorRun(sharedOne.engine(), TIDY_EXPIRING, TIDY_EXPIRING.id().toString());
            SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(200);

            assertTrue(until(() -> !performed.isEmpty() && !other.isEmpty()),
                    "one of the two steps' work never arrived: mine=" + performed
                            + " theirs=" + other);
            assertEquals(List.of(TIDIED), List.copyOf(performed),
                    "a consumer performed work of a step it does not serve: " + performed);
            assertEquals(List.of(TIDIED_BESIDE), List.copyOf(other),
                    "a consumer performed work of a step it does not serve: " + other);
        }
    }

    // ======== The writeback passes the tenant's rules (shared deployment) ========

    @Test
    @Order(27)
    @DisplayName("a run closes in the tenant that authored it, naming the performer the "
            + "application gave and not the deployment")
    @Proving(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES)
    void itClosesNamingTheExecutor() throws Exception {
        Run authored = authorRun(sharedOne.engine(), WRITTEN_SWEEPING, WRITTEN_SWEEPING.id().toString());
        SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100);

        // BOTH steps on one consumer, which is only possible because they
        // name one substrate: a queue lives on the substrate its step named,
        // and a consumer reaches the queues on the database it was given.
        writebackConsumer = new StepConsumer(
                SharedTenants.manager().stepSubstrates().get(WRITTEN), Set.of(WRITTEN, JUDGED),
                writebackClaims());
        writebackConsumer.performing(cloud.jengu.dbo.runner.StepService.performing(WRITTEN,
                work -> cloud.jengu.dbo.runner.Outcome.done(Map.of("swept", 1L))));

        Runs runs = new Runs(sharedOne.engine());
        assertTrue(until(() -> runs.byKey(authored.key())
                        .map(run -> run.holder() == Holder.NOBODY).orElse(false)),
                "the run never closed in the tenant that authored it, so work the deployment "
                        + "performed is not on that tenant's record: "
                        + runs.byKey(authored.key()));

        Run closed = runs.byKey(authored.key()).orElseThrow();
        assertEquals(Map.of("swept", 1L), closed.tally(),
                "what the performer counted did not come home with the closure");
        assertTrue(entriesFor(closed).stream().anyMatch(e -> e.contains(WRITEBACK_PERFORMER)),
                "the run does not name the performer the application gave, so a deployment "
                        + "has stamped its own name on work a bean did: " + entriesFor(closed));
    }

    @Test
    @Order(28)
    @DisplayName("a report the step does not declare is refused exactly as it would be on a "
            + "lane, naming the action")
    @Proving(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES)
    void aReportBreakingARuleIsRefused() throws Exception {
        Run authored = authorRun(sharedOne.engine(), JUDGED_REVIEWING, JUDGED_REVIEWING.id().toString());

        // THROUGH THE WRITEBACK AND NOT THROUGH THE QUEUE, deliberately. What
        // is being proven is that an outcome from a fleet consumer meets the
        // tenant's rules; that an item reaches a consumer at all is proven by
        // the legs above. Going through the queue here would make this leg
        // fail for the other reason as well as this one, and a leg that can
        // fail two ways proves neither.
        cloud.jengu.dbo.stream.FleetPerformer.Held held = writebackClaims()
                .held(sharedOne.code(), JUDGED, authored.key(), Duration.ofMinutes(5))
                .orElseThrow(() -> new AssertionError(
                        "no lane into the tenant for a run it authored, so nothing could be "
                                + "reported and nothing refused"));

        // A service that says it is done, over a step whose declaration does
        // not admit closing. The refusal is the LANE's, and what comes back is
        // the run released with its words — the same thing a runner does with a
        // report the tenant will not take, because both go through one mapping.
        cloud.jengu.dbo.runner.Outcome said = cloud.jengu.dbo.runner.Performing.performed(
                held.lane(), held.run(),
                cloud.jengu.dbo.runner.StepService.performing(JUDGED,
                        work -> cloud.jengu.dbo.runner.Outcome.done(Map.of("reviewed", 1L))),
                Duration.ofMinutes(5));

        assertTrue(said instanceof cloud.jengu.dbo.runner.Outcome.Failed,
                "a step declaring 'open' and not 'close' was closed by a fleet consumer, so a "
                        + "tenant's rules hold for everybody except the party doing most of "
                        + "its work: " + said);
        RuntimeException refusal = new IllegalStateException(
                ((cloud.jengu.dbo.runner.Outcome.Failed) said).reason());

        assertTrue(String.valueOf(refusal.getMessage()).contains("close"),
                "the refusal does not name the action the step never declared, which is the "
                        + "one thing somebody has to add: " + refusal.getMessage());
        assertTrue(new Runs(sharedOne.engine()).byKey(authored.key())
                        .map(run -> run.holder() != Holder.NOBODY).orElse(false),
                "the run closed anyway, so the refusal was a message rather than a rule");
    }

    // ======== The management declaration ========

    /** Writes the declaration as it stands and has the deployment read it. */
    private void declare() throws Exception {
        Files.writeString(managementSpec, """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"},
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "fleetSteps":[%s]}"""
                .formatted(MANAGEMENT, String.join(",", declared.values())));
        manager.manages(managementSpec);
    }

    private static String step(String code, String slots, String rest) {
        return "{\"code\":\"" + code + "\",\"slots\":" + slots + rest + "}";
    }

    private static String enrolling(String code) {
        return step(code, "{\"record\":\"Reference(Basic)\"}",
                ",\"opens\":[\"record\"],\"substrate\":\"enrolling\"");
    }

    /** A tenant holding Basic and nothing else, with whatever else it says. */
    private static String basicTenant(String code, String extra) {
        return """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]%s}"""
                .formatted(code, extra);
    }

    // ======== Helpers for the legs on this class's own runtime ========

    /** The register rows of the enrolment legs' own steps. */
    private List<FleetRegister.Row> enrollingRows(String tenant) {
        return manager.fleetRegister(tenant).stream()
                .filter(row -> row.step().startsWith("fleet.enrolling."))
                .toList();
    }

    private void authorFoundRunOf(StepDeclaration step) {
        String record = manager.runtime(FOUND).orElseThrow().engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();
        foundRuns.of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private Run authorAdmittingRun(String tenant) {
        return authorRun(manager.runtime(tenant).orElseThrow().engine(), ADMIT_NORMALISING,
                ADMIT_OPTIONAL);
    }

    private Run authorPostureRun(String step) {
        StepDeclaration declaredStep = StepDeclaration.of(step, "1.0", WorkModel.DOMAIN)
                .taking("record", "https://meristem.example/shape/record");
        return authorRun(manager.runtime(POSTURES).orElseThrow().engine(), declaredStep, step);
    }

    /** What the durable layer on that step's substrate holds, by workflow id. */
    private List<String> queuedIds(String step) throws Exception {
        try (Connection c = manager.stepSubstrates().get(step).getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT workflow_uuid FROM dbos.workflow_status");
                ResultSet rs = ps.executeQuery()) {
            List<String> ids = new java.util.ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        }
    }

    private boolean databaseExists(String dbName) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(
                SharedPostgres.urlFor("AStepIsRunForTheFleetIT"),
                postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** A Basic whose code text is searchable, and its id. */
    private String basicWith(String code) {
        return manager.runtime(FOUND).orElseThrow().engine()
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
                                java.net.URI.create(base + "/t/" + FOUND + "/oidc/token"))
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

    // ======== Helpers for the legs on the shared deployment ========

    private List<TenantSpec.FleetStep> readingSteps() {
        return List.of(new TenantSpec.FleetStep(READ_PROCESSOR,
                Map.of("record", "Reference(Basic)"), Set.of("record"), true,
                TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED, "reading"));
    }

    private static Executor readingPerformer() {
        return new Executor("performing-bean", "1", "cloud.jengu.test", Scope.BASELINE);
    }

    /** What an application performing the written-back steps calls itself. */
    private static Executor writebackPerformer() {
        return new Executor(WRITEBACK_PERFORMER, "1", "cloud.jengu.test", Scope.BASELINE);
    }

    /**
     * Where a report would go. The tidying legs are about the consumer side
     * and not about the writeback, so their beans report nothing — but a
     * consumer cannot be built without one, and handing it a real one keeps
     * them honest about what an application actually assembles.
     */
    private cloud.jengu.dbo.stream.FleetPerformer.Claims tidyingClaims() {
        return new LaneClaims(
                (tenant, step) -> SharedTenants.manager().fleetLane(tenant, step,
                        new Executor("fleet-test", "1", "cloud.jengu.test", Scope.BASELINE)),
                (tenant, runKey) -> new Runs(
                        SharedTenants.manager().runtime(tenant).orElseThrow().engine())
                        .byKey(runKey));
    }

    private cloud.jengu.dbo.stream.FleetPerformer.Claims writebackClaims() {
        return new LaneClaims(
                (tenant, step) -> SharedTenants.manager().fleetLane(tenant, step,
                        writebackPerformer()),
                (tenant, runKey) -> new Runs(
                        SharedTenants.manager().runtime(tenant).orElseThrow().engine())
                        .byKey(runKey));
    }

    /**
     * Every entry naming the run, read past the first page: this tenant audits
     * reads as well as writes, so its trail runs to thousands and a select's
     * default hundred ends long before the closure this looks for.
     */
    private List<String> entriesFor(Run run) {
        List<String> out = new java.util.ArrayList<>();
        sharedOne.engine()
                .select(cloud.jengu.dbo.core.api.Criteria.of("AuditEntry").limit(10_000))
                .forEach(o -> {
                    String e = new String(o.payload(), StandardCharsets.UTF_8);
                    if (e.contains(run.id())) {
                        out.add(e);
                    }
                });
        return out;
    }

    // ======== Shared by every leg ========

    /** A run of that step over a fresh Basic, keyed under the prefix given. */
    private static Run authorRun(ObjectStore engine, StepDeclaration step, String keyPrefix) {
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return new Runs(engine).of(step, RunKind.PIPELINE, keyPrefix + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private static boolean until(java.util.function.BooleanSupplier done) throws Exception {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < giveUp) {
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return done.getAsBoolean();
    }
}
