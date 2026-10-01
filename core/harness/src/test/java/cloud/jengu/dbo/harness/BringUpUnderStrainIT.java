package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.face.DeclaredFace;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ContentSyncEngine;
import cloud.jengu.dbo.tenant.FaceRequirements;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantDatabaseProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.TenantState;
import cloud.jengu.dbo.work.Introductions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-BRING-UP-UNDER-STRAIN: a deployment brings tenants up while the
 * things around it are slow, half-arrived, racing or wrong, and each of those
 * reads as what it is — a wait, a failure with its reason, or nothing at all.
 *
 * <p>The legs walk it in the order an operator meets it. Several tenants are
 * declared at once and come up together. Storage somebody else provisions has
 * not arrived, and the tenant behind it is not held up. A face that cannot
 * serve a declaration refuses it at bring-up, by name, and a tenant asking for
 * nothing unusual still comes up on that face. A mandatory step nobody
 * performs is an incident on a tenant that keeps serving. The engine's own
 * vocabulary arrives twice, once at bring-up and once over a stream, and is
 * one publication rather than an override. A tenant whose storage goes while
 * a racing node mounts it is not a tenant that failed. A node stopped
 * mid-sync says nothing about the pools it closed on purpose. And a stream
 * keeps moving while a tenant is held halfway through coming up.
 *
 * <p><b>Why this cannot be walked on Rowling Land.</b> Every leg stages a
 * failure the world would never show, and the staging belongs to the runtime
 * rather than to a tenant: a provisioner made to lag, to hold, to pair up or
 * to lose its pool under a racing node; a step catalogue and a face registry
 * built by hand; a node closed in the middle of a sync round. On the world
 * each of those would be done to every story at once — a face short of a
 * capability served to the clinic stories, a bring-up held open in front of
 * every tenant they declare, a node closed under all of them.
 *
 * <p><b>Three runtimes, and why not one.</b> Most legs share one runtime,
 * built over a provisioner scripted by tenant code, a step catalogue the legs
 * change, and a face registry that carries a stripped face under a name of
 * its own beside the installed ones — so each staging touches only the
 * tenants its leg names. Two legs cannot share it. The shutdown leg closes
 * the node it is about, and the node the other legs use is still in use; it
 * builds a node of its own and closes it. The teardown leg is about a second
 * node racing the first for one tenant, so the second node is the subject and
 * is built and closed inside the leg. The stream leg starts the shared
 * runtime's own loops, which then run under everything after it, so it is
 * walked last.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BringUpUnderStrainIT {

    // Enough to prove they overlap, and no more: a bring-up holds a
    // validator, and eight of them at four met OutOfMemoryError inside a
    // suite already several classes deep — the same hazard the bound itself
    // exists for.
    private static final int DECLARED = 4;
    private static final int AT_ONCE = 2;

    /** The installed r4 with its coarsening taken out, under a code of its own. */
    private static final String STRIPPED = "r4-sans-coarsening";

    /** How long the held tenant stays in its bring-up. */
    static final Duration HELD_FOR = Duration.ofSeconds(120);

    /** How long the stream is given to move while it is held. Far inside it. */
    static final Duration WAITED_FOR = Duration.ofSeconds(20);

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static Scripted storage;
    static TenantRuntimeManager manager;

    /**
     * The step catalogue as the manager sees it — swappable, the way the OSGi
     * registry's view changes when a module is installed mid-flight.
     */
    static volatile Steps contributed =
            Steps.of(StepDeclaration.of("lab.result.validate", "1.0", "r4"));

    /**
     * A provisioner made to go wrong, by tenant code, and the real one for
     * every code it was not told about.
     *
     * <p>Three arrangements, each confined to the codes its leg names:
     * storage that is somebody else's job and has not arrived — the in-cluster
     * shape, where an operator creates the role, the database and the Secret
     * and the serving node finds them or does not; a bring-up that cannot
     * finish alone, because each one waits for another to arrive, so a node
     * bringing them up one after another times out at the barrier, which is
     * the assertion made without a stopwatch; and a bring-up that does not
     * return until the leg releases it.
     */
    static final class Scripted implements TenantDatabaseProvisioner {

        private final LocalDatabasePerTenantProvisioner real;

        private final Set<String> withheld = ConcurrentHashMap.newKeySet();

        private final Set<String> pairing = ConcurrentHashMap.newKeySet();
        private final CyclicBarrier pairsUp = new CyclicBarrier(2);
        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger mostAtOnce = new AtomicInteger();

        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch reached = new CountDownLatch(1);
        private volatile String holding;

        Scripted(LocalDatabasePerTenantProvisioner real) {
            this.real = real;
        }

        void withhold(String code) {
            withheld.add(code);
        }

        void arrives(String code) {
            withheld.remove(code);
        }

        void pairsUp(String code) {
            pairing.add(code);
        }

        void hold(String code) {
            this.holding = code;
        }

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            String code = spec.code();
            if (withheld.contains(code)) {
                throw new NotProvisionedYet("tenant " + code + " is waiting for its secret");
            }
            if (pairing.contains(code)) {
                meet();
            }
            if (code.equals(holding)) {
                reached.countDown();
                try {
                    if (!released.await(HELD_FOR.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return real.provision(spec);
        }

        private void meet() {
            int now = inside.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            try {
                pairsUp.await(30, TimeUnit.SECONDS);
            } catch (TimeoutException aloneInHere) {
                throw new IllegalStateException("nothing else was being brought up: this "
                        + "tenant waited alone, so bring-up is still a queue", aloneInHere);
            } catch (InterruptedException | BrokenBarrierException e) {
                throw new IllegalStateException(e);
            } finally {
                inside.decrementAndGet();
            }
        }

        @Override
        public void deprovision(String tenantCode) {
            real.deprovision(tenantCode);
        }

        @Override
        public void release(String tenantCode) {
            real.release(tenantCode);
        }
    }

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-under-strain");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("BringUpUnderStrainIT"),
                postgres.getUsername(), postgres.getPassword());
        storage = new Scripted(provisioner);
        Steps live = new Steps() {
            @Override
            public Optional<StepDeclaration> byId(String id) {
                return contributed.byId(id);
            }

            @Override
            public Set<String> ids() {
                return contributed.ids();
            }
        };
        // A real KEK, because the pdi path needs one: without it the pdi
        // tenant the face refuses could fail for a reason that is not the
        // gate, and the refusal leg would prove nothing about the gate.
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, storage, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null), withTheStrippedFace(), live);
        manager.broughtUpTogether(AT_ONCE);
    }

    @AfterAll
    void down() {
        storage.released.countDown();
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    /**
     * Every installed version, and the installed r4 stripped of its coarsening
     * under a code of its own. A face registry belongs to the runtime, so the
     * stripped face is offered beside the real ones rather than in their place,
     * and only a tenant that names it is served by it.
     */
    private static FhirVersions withTheStrippedFace() {
        FhirVersions installed = FhirVersions.installed();
        FhirVersion stripped = new SansCoarsening(installed.require("r4"));
        return new FhirVersions() {
            @Override
            public Optional<FhirVersion> byCode(String code) {
                return STRIPPED.equals(code) ? Optional.of(stripped) : installed.byCode(code);
            }

            @Override
            public Set<String> codes() {
                Set<String> codes = new LinkedHashSet<>(installed.codes());
                codes.add(STRIPPED);
                return codes;
            }
        };
    }

    private static String observations(String code) {
        return """
                {"code":"%s","face":"r4","types":[
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(code);
    }

    private static TenantState.State stateOf(String code) {
        return manager.tenantStates().stream()
                .filter(state -> state.code().equals(code))
                .findFirst().orElseThrow().state();
    }

    private static Map<String, TenantState.State> states() {
        return manager.tenantStates().stream()
                .collect(Collectors.toMap(TenantState::code, TenantState::state));
    }

    // ---- Several declared at once -------------------------------------------

    /**
     * First, and that is what lets it assert on the whole of one scan's
     * answer: nothing else has been declared to this runtime yet, so what the
     * scan serves is exactly what this leg declared.
     */
    @Test
    @Order(1)
    @DisplayName("tenants declared at once all come up, together and no more at a time than "
            + "the node was told it could carry")
    @Proving(DboPromises.TEN_DECLARED_TOGETHER_COME_UP_TOGETHER)
    void tenantsDeclaredAtOnceAllComeUp() throws Exception {
        for (int clinic = 0; clinic < DECLARED; clinic++) {
            storage.pairsUp("at-once-" + clinic);
            Files.writeString(dir.resolve("at-once-" + clinic + ".json"), """
                    {"code":"at-once-%d","face":"r4","types":[
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(clinic));
        }

        Set<String> serving = manager.scanOnce();

        assertEquals(DECLARED, serving.size(),
                "a consumer declaring " + DECLARED + " tenants got " + serving.size()
                        + "; troubles=" + manager.troubles());
        assertTrue(storage.mostAtOnce.get() > 1,
                "every bring-up had the node to itself, which is the queue this removes");
        assertTrue(storage.mostAtOnce.get() <= AT_ONCE,
                "a node brought up " + storage.mostAtOnce.get() + " tenants at once, past the "
                        + AT_ONCE + " it was told it could carry");
    }

    // ---- Storage that has not arrived ---------------------------------------

    /**
     * The tenant behind the waiting one is the assertion. Waiting for storage
     * inside the scan would make the queue move at the speed of whoever is at
     * the front of it.
     */
    @Test
    @Order(2)
    @DisplayName("a tenant waiting for its storage does not hold up the one behind it, and "
            + "the ledger says what it is waiting for")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void aTenantWaitingForItsStorageDoesNotHoldUpTheOneBehindIt() throws Exception {
        storage.withhold("waiting-clinic");
        Files.writeString(dir.resolve("waiting-clinic.json"), observations("waiting-clinic"));
        Files.writeString(dir.resolve("ready-clinic.json"), observations("ready-clinic"));

        Set<String> serving = manager.scanOnce();

        assertTrue(serving.contains("ready-clinic"),
                "the tenant behind the waiting one did not come up: " + manager.troubles());
        assertFalse(serving.contains("waiting-clinic"));
        assertEquals(TenantState.State.COMING_UP, stateOf("waiting-clinic"),
                "storage that has not arrived is a wait, not a fault");
        assertTrue(manager.troubles().get("waiting-clinic").contains("waiting for its secret"),
                "the ledger has to say what it is waiting for: " + manager.troubles());
    }

    /** And when the storage arrives, the next pass is all it takes. */
    @Test
    @Order(3)
    @DisplayName("a tenant whose storage arrives comes up on the next pass and is nobody's "
            + "trouble any more")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void andItComesUpOnTheNextPassOnceTheStorageArrives() {
        storage.arrives("waiting-clinic");
        UntilServed.scan(manager, "waiting-clinic");
        assertEquals(TenantState.State.SERVING, stateOf("waiting-clinic"));
        assertFalse(manager.troubles().containsKey("waiting-clinic"),
                "a tenant that came up is nobody's trouble any more");
    }

    // ---- A face that cannot serve a declaration -----------------------------

    /** Absence is only an error against a requirement. */
    @Test
    @Order(4)
    @DisplayName("a tenant requiring nothing a face lacks comes up on that face")
    void aTenantRequiringNothingUnusualComesUpOnTheSameFace() throws Exception {
        // asks for coarsening (pdi) — must be refused
        Files.writeString(dir.resolve("keeldub.json"), """
                {"code":"keeldub","face":"%s","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(STRIPPED));
        // asks for nothing unusual — must come up on the same stripped face
        Files.writeString(dir.resolve("lubatud.json"), """
                {"code":"lubatud","face":"%s","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(STRIPPED));
        UntilServed.scan(manager, up -> up.contains("lubatud"));

        Map<String, TenantState.State> states = states();
        assertEquals(TenantState.State.SERVING, states.get("lubatud"),
                "a spec that asks for nothing the face lacks is served: " + states);
    }

    /** The failure arrives at bring-up, not mid-request — and not silently. */
    @Test
    @Order(5)
    @DisplayName("a spec the face cannot serve is refused at bring-up rather than degraded "
            + "while serving")
    void aSpecTheFaceCannotServeIsRefusedAtBringUpNotDegradedAtRuntime() {
        Map<String, TenantState.State> states = states();
        assertEquals(TenantState.State.FAILED, states.get("keeldub"),
                "pdi needs the face's coarsening, and this face has none — without the "
                        + "gate this comes up and silently turns GENERALISE into REMOVE: "
                        + states);
    }

    /** The refusal names both sides: the capability, and what asked for it. */
    @Test
    @Order(6)
    @DisplayName("the refusal names the capability, the part of the spec that asked for it, "
            + "and the face that fell short")
    void theRefusalNamesTheCapabilityAndTheRequirement() throws Exception {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"nimeline","face":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        DomainFace stripped = new SansCoarsening(
                FhirVersions.installed().require("r4")).face();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> FaceRequirements.refuseUnservable(spec, stripped));
        assertTrue(refusal.getMessage().contains("Coarsening"),
                "the capability, by name: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("pdi"),
                "and the part of the spec that asked for it: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains(stripped.name()),
                "and whose face fell short: " + refusal.getMessage());
    }

    // ---- A mandatory step nobody performs -----------------------------------

    /**
     * Buffering is the design: work queues when nothing serves a step, and a
     * participant arriving later drains it, so a missing executor must not
     * become an outage.
     */
    @Test
    @Order(7)
    @DisplayName("a tenant serves even when a mandatory step is missing — runs queue, the tenant stays up")
    @Proving(DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS)
    void aTenantServesEvenWhenAMandatoryStepIsMissing() throws Exception {
        // its mandatory step is contributed — no incident
        Files.writeString(dir.resolve("terve.json"), """
                {"code":"terve","face":"r4",
                 "mandatorySteps":["lab.result.validate"],"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        // names a step nobody contributed — serves anyway, with an open incident
        Files.writeString(dir.resolve("ootel.json"), """
                {"code":"ootel","face":"r4",
                 "mandatorySteps":["lab.result.sign"],"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        // its mandatory step will arrive by introduction over a lane
        Files.writeString(dir.resolve("sisse.json"), """
                {"code":"sisse","face":"r4",
                 "mandatorySteps":["ee-lab.result.sign"],"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "terve", "ootel", "sisse");

        Map<String, TenantState.State> states = states();
        assertEquals(TenantState.State.SERVING, states.get("ootel"),
                "nothing contributes 'lab.result.sign', and that must buffer work rather "
                        + "than take the tenant offline: " + states);
    }

    /** The list classifies: mandatory-and-missing is an incident, by name. */
    @Test
    @Order(8)
    @DisplayName("a missing mandatory step is an incident on the operator surface, and only a mandatory one")
    @Proving(DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS)
    void aMissingMandatoryStepIsAnIncidentByName() {
        Map<String, Set<String>> incidents = manager.stepIncidents();
        assertEquals(Set.of("lab.result.sign"), incidents.get("ootel"),
                "the incident names the step, because 'something is missing' alone is not "
                        + "actionable: " + incidents);
        assertFalse(incidents.containsKey("terve"),
                "a tenant whose mandatory steps are contributed has no open incident — and "
                        + "the absence of every UNDECLARED step is no incident at all: "
                        + incidents);
    }

    /** The incident clears when the step arrives; nothing is told, the scan sees it. */
    @Test
    @Order(9)
    @DisplayName("the incident clears on the scan after the step is contributed")
    @Proving(DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS)
    void theIncidentClearsWhenTheStepArrives() {
        // The module arrives — the registry's view changes, nothing is told.
        contributed = Steps.of(
                StepDeclaration.of("lab.result.validate", "1.0", "r4"),
                StepDeclaration.of("lab.result.sign", "1.0", "r4"));
        manager.scanOnce();
        assertFalse(manager.stepIncidents().containsKey("ootel"),
                "the step is contributed, so the incident is over: " + manager.stepIncidents());

        // and it reopens if the contribution goes away again
        contributed = Steps.of(StepDeclaration.of("lab.result.validate", "1.0", "r4"));
        manager.scanOnce();
        assertEquals(Set.of("lab.result.sign"), manager.stepIncidents().get("ootel"),
                "a participant leaving reopens the incident — classification follows what "
                        + "is contributed NOW: " + manager.stepIncidents());
    }

    /** The emergent catalogue's payoff: the platform's steps arrive as introductions. */
    @Test
    @Order(10)
    @DisplayName("a mandatory step satisfied by an introduction over a lane clears "
            + "the incident, without anything installed")
    @Proving({DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION,
            DboPromises.PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS})
    void aMandatoryStepCanArriveByIntroduction() {
        assertEquals(Set.of("ee-lab.result.sign"), manager.stepIncidents().get("sisse"),
                "nothing installed contributes it, so the incident is open");

        // The participant connects over a lane and introduces its step
        // into THIS tenant's own store — no module, no restart.
        new Introductions(manager.runtime("sisse").orElseThrow().engine(), Steps.of())
                .introduce(StepDeclaration.of("ee-lab.result.sign", "2.0", "r4"),
                        "ee-lab-connector");
        manager.scanOnce();
        assertFalse(manager.stepIncidents().containsKey("sisse"),
                "the composed catalogue counts introductions, so the mandatory claim is "
                        + "satisfied the way the platform's own steps will satisfy it — "
                        + "over a lane: " + manager.stepIncidents());
    }

    /** A typo must be refused at parse, not left silently unmatched forever. */
    @Test
    @Order(11)
    @DisplayName("a malformed mandatory step id is refused when the spec is parsed")
    void aMalformedMandatoryStepIdIsRefusedAtParse() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"vigane","face":"r4",
                         "mandatorySteps":["not-a-step-id"],"types":[
                          {"name":"Patient","identity":"internal","handling":"operational"}]}"""));
        assertTrue(refusal.getMessage().contains("not-a-step-id"),
                "the refusal names the entry: " + refusal.getMessage());
    }

    // ---- The engine's own vocabulary, arriving twice ------------------------

    private static String vocabulary(String code, String dependencies) {
        return """
                {"code":"%s","face":"r4","types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"mirrored"},
                  {"name":"ValueSet","identity":"canonical","handling":"mirrored"}]%s}"""
                .formatted(code, dependencies);
    }

    /**
     * Every tenant is given the engine's vocabulary at bring-up — that is what
     * makes a {@code urn:dbo:} code resolvable in the tenant that served it. A
     * tenant that also inherits CodeSystem from an upstream is therefore given
     * the identical publication twice, once by each route, under two object
     * ids, and the second must not read as a local override.
     *
     * <p>Two rounds, because one proves nothing: the collision recurs on the
     * sync cadence, so the second round is the one that would be re-attempting
     * a shadow it could never clear.
     */
    @Test
    @Order(12)
    @DisplayName("the engine's own vocabulary arriving from upstream is the publication the "
            + "tenant already holds, never parked as somebody else's override")
    void theEnginesOwnVocabularyNeverParksAsSomebodyElsesOverride() throws Exception {
        // Where the container log stood before this leg's own bring-ups, so
        // the first deliveries they make are inside what is judged.
        int logMark = postgres.getLogs().length();
        Files.writeString(dir.resolve("vocabzone.json"), vocabulary("vocabzone", ""));
        UntilServed.scan(manager, up -> up.contains("vocabzone"));
        // A tenant that inherits AND may write its own vocabulary — a zone
        // under a parent zone. A tenant whose CodeSystem is read-only here
        // never publishes its own copy and never meets this at all.
        Files.writeString(dir.resolve("vocabdep.json"), vocabulary("vocabdep",
                ",\"dependencies\":[{\"name\":\"vocabzone\","
                        + "\"types\":[\"CodeSystem\",\"ValueSet\"]}]"));
        UntilServed.scan(manager, up -> up.contains("vocabdep"));

        manager.syncRound();
        manager.syncRound();

        List<String> parked = new ArrayList<>();
        for (ContentSyncEngine engine : manager.streamsOf("vocabdep")) {
            for (ContentSyncEngine.ShadowedEvent shadow : engine.shadowedEvents()) {
                parked.add(shadow.typeName() + " " + canonicalOf(shadow.payload()));
            }
            assertTrue(engine.deadLetters().isEmpty(),
                    "nothing dead-lettered: " + engine.deadLetters());
        }
        assertEquals(List.of(), parked,
                "the engine's own vocabulary arriving from upstream is the same publication "
                        + "this tenant already has, not a local override of it");

        // The other half of the harm, measured where it lands: a dedup
        // discovered by a failed INSERT puts a duplicate-key ERROR in the
        // Postgres log on every first delivery, describing a situation the
        // code handles — and a log that keeps errors for handled situations
        // buries the ones that matter. Only this leg's slice of the shared
        // container's log is judged, and only for the types it writes: the
        // log is shared and filled asynchronously, so a line written before
        // the mark can appear after it, and the suite holds a test that races
        // replicas at one identity on purpose. A mark cannot be made reliable
        // against an async log; naming the subject can.
        String sinceMark = postgres.getLogs().substring(Math.min(logMark,
                postgres.getLogs().length()));
        List<String> noise = sinceMark.lines()
                .filter(line -> line.contains("already exists"))
                .filter(line -> line.contains("(CodeSystem,") || line.contains("(ValueSet,"))
                .toList();
        assertEquals(List.of(), noise,
                "a handled conflict is resolved by asking, not by failing an insert "
                        + "the database logs");
    }

    /**
     * A genuine local decision still shadows. The cure for "identical content
     * is not an override" must not become "an upstream copy always wins": the
     * tenant writes its own definition at a canonical the upstream also
     * publishes, with different content, and a stream that overwrote it would
     * be doing exactly what shadowing exists to prevent.
     */
    @Test
    @Order(13)
    @DisplayName("a local decision that differs from the upstream still shadows it, and the "
            + "tenant reads its own")
    @Proving(DboPromises.SYNC_LOCAL_SHADOWING)
    void aLocalDecisionThatDiffersStillShadowsTheUpstream() {
        String canonical = "https://terms.test/CodeSystem/disputed";
        write("vocabdep", canonical, "LocalOverride");
        write("vocabzone", canonical, "Upstream");
        manager.syncRound();
        manager.syncRound();

        List<String> parked = new ArrayList<>();
        for (ContentSyncEngine engine : manager.streamsOf("vocabdep")) {
            engine.shadowedEvents().forEach(shadow ->
                    parked.add(shadow.typeName() + " " + canonicalOf(shadow.payload())));
        }
        assertEquals(List.of("CodeSystem " + canonical), parked,
                "the tenant's own decision is held and the upstream version parks for a "
                        + "person to resolve");

        String held = manager.runtime("vocabdep").orElseThrow().store()
                .read("CodeSystem", localIdOf(canonical));
        assertTrue(held.contains("LocalOverride"),
                "and what the tenant reads is still its own: " + held);
    }

    private static void write(String tenant, String canonical, String name) {
        manager.runtime(tenant).orElseThrow().store().create("""
                {"resourceType":"CodeSystem","status":"active","content":"complete",
                 "url":"%s","version":"1","name":"%s","concept":[{"code":"x"}]}"""
                .formatted(canonical, name));
    }

    private static String localIdOf(String canonical) {
        return manager.runtime("vocabdep").orElseThrow().engine()
                .getByIdentifier("CodeSystem",
                        List.of(new Identifier(Identifier.CANONICAL_SYSTEM, canonical)))
                .get(0).id();
    }

    private static String canonicalOf(byte[] payload) {
        String body = new String(payload, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"url\":\"(urn:dbo:[^\"]+|https://terms[^\"]+)\"")
                .matcher(body);
        return matcher.find() ? matcher.group(1) : "(no canonical)";
    }

    // ---- A tenant going away while a racing node mounts it ------------------

    /**
     * A tenant being taken down is not a tenant that failed to come up.
     * Reporting it as one sends a reader to the declaration, the spec file and
     * whether the tenant is broken, when what happened is that its storage
     * went and the work in flight stopped. Nothing is wrong, so it is no state
     * at all: not COMING_UP, which says the next round fixes it, and not
     * FAILED, which says somebody must.
     */
    @Test
    @Order(14)
    @Timeout(600)
    @DisplayName("a tenant whose storage went away mid-flight is neither coming up nor failed, "
            + "and its declaration is not blamed")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void takenDownIsNotFailed() throws Exception {
        String code = "going-away";
        Files.writeString(dir.resolve(code + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(code));
        UntilServed.scan(manager, code);

        // The race itself, made to happen rather than waited for: a mount runs
        // against a pool that has already closed, which is what a deletion in
        // flight leaves behind. Waiting for the real interleaving would be a
        // test that passes for the wrong reason on a slow machine. The racing
        // node is the subject, so it is a node of its own, closed here.
        Path second = Files.createTempDirectory("dbo-going-away-2");
        Files.writeString(second.resolve(code + ".json"),
                Files.readString(dir.resolve(code + ".json")));
        try (TenantRuntimeManager racing = new TenantRuntimeManager(second,
                new ClosedBeforeItIsUsed(provisioner), "127.0.0.1", 0, null, null)) {
            racing.scanOnce();

            TenantState raced = racing.tenantStates().stream()
                    .filter(one -> code.equals(one.code()))
                    .findFirst().orElse(null);
            assertFalse(raced != null && raced.state() == TenantState.State.FAILED,
                    "a tenant whose storage was already gone was recorded as one that failed "
                            + "to come up, which sends a reader to its declaration: " + raced);
            assertFalse(racing.troubles().containsKey(code),
                    "a tenant being taken down left trouble against its name for somebody to "
                            + "investigate: " + racing.troubles());
        }
        manager.scanOnce();

        TenantState state = manager.tenantStates().stream()
                .filter(one -> code.equals(one.code()))
                .findFirst().orElse(null);
        if (state != null) {
            assertFalse(state.state() == TenantState.State.FAILED,
                    "a tenant being taken down was recorded as one that failed to come up, "
                            + "which sends a reader to its declaration: " + state);
        }
        assertFalse(manager.troubles().containsKey(code),
                "a tenant being taken down left trouble against its name for somebody to "
                        + "investigate: " + manager.troubles());
    }

    /**
     * A provisioner whose pool is closed before anything uses it — the state a
     * deletion in flight leaves for work that is already under way.
     */
    private record ClosedBeforeItIsUsed(TenantDatabaseProvisioner inner)
            implements TenantDatabaseProvisioner {

        @Override
        public TenantDatabase provision(TenantSpec spec) {
            TenantDatabase database = inner.provision(spec);
            // Through AutoCloseable rather than the pool's own type: this is
            // about a data source that has gone, not about which pool it was.
            if (database.dataSource() instanceof AutoCloseable pool) {
                try {
                    pool.close();
                } catch (Exception alreadyGone) {
                    // being closed is the point
                }
            }
            return database;
        }

        @Override
        public void deprovision(String tenantCode) {
            inner.deprovision(tenantCode);
        }
    }

    /** And a real failure is still reported as one. */
    @Test
    @Order(15)
    @Timeout(600)
    @DisplayName("a declaration that genuinely cannot come up is still recorded as failed")
    @Proving(DboPromises.OPS_RUNTIME_SAYS_WHAT_IT_SERVES)
    void arealFailureStillFails() throws Exception {
        Files.writeString(dir.resolve("broken.json"), """
                {"code":"broken","face":"no-such-face","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        manager.scanOnce();

        assertTrue(manager.troubles().containsKey("broken")
                        || manager.tenantStates().stream().anyMatch(one ->
                                "broken".equals(one.code())
                                        && one.state() == TenantState.State.FAILED),
                "a declaration naming a face nothing provides stopped being reported, so the "
                        + "quieting went too far: " + manager.troubles());

        // Retracted once said: the legs after this one share the runtime, and a
        // tenant failing on every pass and a pool the racing node closed are
        // work they would otherwise carry for nothing.
        Files.deleteIfExists(dir.resolve("broken.json"));
        Files.deleteIfExists(dir.resolve("going-away.json"));
        manager.scanOnce();
    }

    // ---- A shutdown mid-sync ------------------------------------------------

    /**
     * A shutdown that was asked for reads like one. A teardown that logs like
     * a crash is where a real crash goes to hide. The order is what decides
     * it: ending the loops by interrupting them ends the sleep between rounds
     * and does nothing to a round already reading through a pool, so the pools
     * must not close under a round still in flight.
     *
     * <p>A node of its own, built and closed here: what is asserted is what a
     * node says while it is being closed, and the shared node is still in use.
     */
    @Test
    @Order(16)
    @DisplayName("closing a node that is syncing says nothing about the pools it closed")
    void nothingIsSaidAboutPoolsClosedOnPurpose() throws Exception {
        String zone = "vaikne-zone";
        String edge = "vaikne-edge";
        Path quietDir = Files.createTempDirectory("dbo-quiet");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        TenantRuntimeManager quiet = new TenantRuntimeManager(quietDir, provisioner,
                "127.0.0.1", 0, null, new TenantRuntimeManager.AuthorityConfig(kek, null));
        boolean closed = false;
        try {
            // A dependent tenant, so the reconciler has streams to carry and a
            // round is doing real work when the close arrives.
            Files.writeString(quietDir.resolve(zone + ".json"), """
                    {"code":"%s","face":"r4","audit":{"level":"none"},"types":[
                      {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(zone));
            Files.writeString(quietDir.resolve(edge + ".json"), """
                    {"code":"%s","face":"r4","audit":{"level":"none"},
                     "dependencies":[{"name":"%s","types":["CodeSystem"]}],
                     "types":[
                      {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                      {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                    .formatted(edge, zone));
            UntilServed.scan(quiet, zone, edge);

            // Enough for the edge to still be carrying when the close arrives:
            // a round over an empty feed is over before anything can interrupt
            // it, and a test whose window does not exist proves nothing.
            String token = tokenFor(quiet, zone);
            String zoneBase = "http://127.0.0.1:" + quiet.port() + "/t/" + zone;
            for (int i = 0; i < 150; i++) {
                HTTP.send(HttpRequest.newBuilder(URI.create(zoneBase + "/fhir/CodeSystem"))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/fhir+json")
                                .POST(HttpRequest.BodyPublishers.ofString("""
                                        {"resourceType":"CodeSystem",
                                         "url":"https://vaikne.example/cs/%d","status":"active",
                                         "content":"complete",
                                         "concept":[{"code":"a"},{"code":"b"},{"code":"c"}]}"""
                                        .formatted(i))).build(),
                        HttpResponse.BodyHandlers.ofString());
            }

            // Rounds running against live pools, so a close lands mid-flight.
            quiet.start(50);
            Thread.sleep(300);

            PrintStream realOut = System.out;
            PrintStream realErr = System.err;
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(said, true, StandardCharsets.UTF_8);
            System.setOut(capture);
            System.setErr(capture);
            try {
                closed = true;
                quiet.close();
                // whatever a straggler would have said, it has had its chance
                Thread.sleep(1500);
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
            }

            // Only what this node said about ITS OWN tenants. System.out is the
            // whole JVM's, and other classes log into the same stream — an
            // assertion over all of it fails on somebody else's ordinary line,
            // which is a test that cries wolf about the very thing it exists
            // to keep quiet.
            String shutdown = said.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.contains(zone) || line.contains(edge))
                    .collect(Collectors.joining("\n"));

            assertFalse(shutdown.contains("has been closed"),
                    "a shutdown said this about a pool it closed on purpose, which is the "
                            + "noise a real failure has to be found in:\n" + shutdown);
            assertFalse(shutdown.contains("marked as broken") || shutdown.contains("PSQLException"),
                    "a round was still reading through a connection when the node was taken "
                            + "down, so stopping it broke the connection under it:\n" + shutdown);
        } finally {
            if (!closed) {
                quiet.close();
            }
        }
    }

    // ---- A stream keeps moving while a tenant is held coming up -------------

    private static String tokenFor(TenantRuntimeManager on, String tenant) throws Exception {
        on.authority(tenant).ensureClient("loader", "loader-secret",
                List.of("system/*.read", "system/*.write"));
        return Extracted.tokenIn(HTTP.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + on.port() + "/t/" + tenant + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&client_id=loader&client_secret="
                                        + "loader-secret")).build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    }

    private static final String STREAMED_TYPES = """
            [{"name":"CodeSystem","identity":"canonical","handling":"operational"}]""";

    /**
     * Bringing a tenant up and keeping a stream in step must not wait on each
     * other: a tenant catching up with a large dependency would delay every
     * other bring-up, and a bring-up waiting on somebody else's storage would
     * stop every stream in the deployment — invisibly, because neither is
     * anybody's failure.
     *
     * <p>Last, because it starts this runtime's own loops and they run under
     * whatever comes after it.
     */
    @Test
    @Order(17)
    @DisplayName("a stream keeps moving while a tenant somewhere else is stuck coming up")
    @Proving(DboPromises.TEN_COMING_UP_AND_KEEPING_UP_ARE_NOT_ONE_QUEUE)
    void aStreamKeepsMovingWhileATenantIsStuckComingUp() throws Exception {
        Files.writeString(dir.resolve("loops-source.json"),
                "{\"code\":\"loops-source\",\"face\":\"r4\",\"types\":" + STREAMED_TYPES + "}");
        Files.writeString(dir.resolve("loops-reader.json"),
                "{\"code\":\"loops-reader\",\"face\":\"r4\",\"types\":" + STREAMED_TYPES + ","
                        + "\"dependencies\":[{\"name\":\"loops-source\","
                        + "\"types\":[\"CodeSystem\"]}]}");
        UntilServed.scan(manager, "loops-source", "loops-reader");
        String sourceToken = tokenFor(manager, "loops-source");
        String readerToken = tokenFor(manager, "loops-reader");

        // Both loops running, and a third tenant whose provisioning does not
        // return: the scan is now inside a bring-up and stays there.
        manager.start(200);
        storage.hold("loops-stuck");
        Files.writeString(dir.resolve("loops-stuck.json"),
                "{\"code\":\"loops-stuck\",\"face\":\"r4\",\"types\":" + STREAMED_TYPES + "}");
        assertTrue(storage.reached.await(30, TimeUnit.SECONDS),
                "the scan never reached the tenant this leg holds");

        // Written while the scan is stuck. Sharing one thread, nothing would
        // carry it: the sync round would sit behind the bring-up that is waiting.
        assertEquals(201, HTTP.send(HttpRequest.newBuilder(URI.create(
                                manager.baseUrl("loops-source") + "/CodeSystem"))
                        .header("Authorization", "Bearer " + sourceToken)
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"CodeSystem","url":"https://loops.test/cs/one",
                                 "status":"active","content":"complete","concept":[{"code":"kept-moving"}]}"""))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .statusCode());

        // Strictly inside the hold, and that is the whole assertion: sharing
        // one thread, nothing can arrive until the held bring-up gives up, so
        // a wait that outlived the hold would pass either way.
        long deadline = System.currentTimeMillis() + WAITED_FOR.toMillis();
        boolean arrived = false;
        while (!arrived && System.currentTimeMillis() < deadline) {
            // The count, not the body: a search echoes its own query in the
            // bundle's self link, so asking whether the answer mentions the
            // url is a question that says yes when the answer is empty.
            arrived = HTTP.send(HttpRequest.newBuilder(URI.create(
                                    manager.baseUrl("loops-reader")
                                            + "/CodeSystem?url=https://loops.test/cs/one"
                                            + "&_summary=count"))
                            .header("Authorization", "Bearer " + readerToken)
                            .GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body().contains("\"total\":1");
            if (!arrived) {
                Thread.sleep(200);
            }
        }
        assertTrue(arrived,
                "the stream stopped because a tenant somewhere else was still coming up");
        assertTrue(storage.reached.getCount() == 0 && !manager.codes().contains("loops-stuck"),
                "the held tenant came up anyway, so this proved nothing");

        storage.released.countDown();
    }

    /** The installed r4, minus one capability, everything else delegated. */
    private static final class SansCoarsening implements FhirVersion {
        private final FhirVersion real;
        private final DomainFace face;

        SansCoarsening(FhirVersion real) {
            this.real = real;
            DeclaredFace.Builder builder = DeclaredFace.named(real.face().name() + "-sans-coarsening");
            for (Class<?> capability : real.face().capabilities()) {
                if (!Coarsening.class.equals(capability)) {
                    provide(builder, capability, real.face());
                }
            }
            this.face = builder.build();
        }

        @SuppressWarnings("unchecked")
        private static <T> void provide(DeclaredFace.Builder builder,
                Class<T> type, DomainFace from) {
            builder.providing(type, (T) from.capability(type).orElseThrow());
        }

        @Override
        public String code() {
            return STRIPPED;
        }

        @Override
        public String domain() {
            return real.domain();
        }

        @Override
        public String payloadVersion() {
            return real.payloadVersion();
        }

        @Override
        public DomainFace face() {
            return face;
        }

        @Override
        public ForTypes forTypes(List<FhirTypeConfig> types) {
            return real.forTypes(types);
        }
    }
}
