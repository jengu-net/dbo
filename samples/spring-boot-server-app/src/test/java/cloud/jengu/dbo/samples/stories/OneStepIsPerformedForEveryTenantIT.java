package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.samples.worker.AskingForADirectoryCheck;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboTestContext;
import cloud.jengu.dbo.spring.worker.DboInitiator;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.RunSlot;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-FLEET-STEP, walked in Rowling Land, the sample world.
 *
 * <p>The deployment declares {@code fleet.directory.check} in its management
 * tenant, {@code mom}, and the serving application holds one bean performing
 * it for every tenant. Hogwarts is asked for a check by the worker
 * application, which performs none; a clinic joins while all of that is
 * already running, and has its own checks performed by the same bean without
 * anything being redeployed. Then the clinic declines the check.
 *
 * <p><b>The clinic is the story's own</b>, declared under its prefix and
 * retracted at the end, because joining and declining are changes to what a
 * tenant declares and a world member is read-only in that. Every question
 * asked of the step's queue names a tenant or a run this story made: the queue
 * belongs to the deployment and every story's checks pass through it.
 */
@AUserStory
class OneStepIsPerformedForEveryTenantIT {

    private static final String MANAGEMENT = "mom";
    private static final String HOSPITAL = "hogwarts";

    /** The step the deployment declares in mom.json, and performs for every tenant. */
    private static final String STEP = AskingForADirectoryCheck.STEP;

    /** Who performs it, as the serving sample's bean names itself in its annotation. */
    private static final String PERFORMER = "sample-directory-checker";

    /**
     * The database mom.json's {@code "substrate":"directory"} names: the
     * deployment's rule is {@code step_} and the substrate's name, which keeps
     * it apart from every {@code tenant_} database on the same server.
     */
    private static final String SUBSTRATE = "step_directory";

    /** What the deployment records about the tenants it was told about, in mom. */
    private static final String SERVING = "dbo.tenant.serving/serve/deployment";

    /** The joiner's name as a reader of every tenant's work. */
    private static final String JOINER = "fleet-joiner";

    /** What the deployment gave its nodes for an operator's questions: the stories profile. */
    private static final String OPS = "stories-ops";

    private static final StoryNames NAMES = StoryNames.of(DboStories.FLEET_STEP);
    private static final String CLINIC = NAMES.tenant("clinic");

    /**
     * The module half of every step code this story makes: a step id's parts
     * start with a letter, and the run mark need not.
     */
    private static final String MODULE = NAMES.prefix() + "-" + NAMES.run();

    /** A step of the clinic's own, which the deployment has no business performing. */
    private static final String OWN_STEP = MODULE + ".clinic.review";
    private static final StepDeclaration REVIEW =
            StepDeclaration.of(OWN_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://story.dbo.test/shape/record");

    /** The deployment's step as a run of it is written inside a tenant. */
    private static final StepDeclaration CHECK =
            StepDeclaration.of(STEP, "1.0", WorkModel.DOMAIN)
                    .taking("org", "Organization");

    /** A step a participant brings to the clinic, over all three shapes of slot. */
    private static final String SHAPES = MODULE + ".clinic.shapes";
    private static final StepDeclaration OVER_ALL_THREE =
            StepDeclaration.of(SHAPES, "1.0", WorkModel.DOMAIN)
                    .taking("held", "Basic")
                    .taking("proposed", "Basic")
                    .taking("notes", "Basic");
    private static final String SHAPER = NAMES.value("shaper");

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @Autowired
    AskingForADirectoryCheck asking;

    @Autowired
    Environment environment;

    private WhereTheFleetsWorkWaits queue;
    private String clinicOrganisation;
    private Run hospitalsCheck;
    private Run clinicsCheck;

    @BeforeAll
    void theClinicAsksToJoin() {
        queue = new WhereTheFleetsWorkWaits(environment);
        // Declared first thing, offering the deployment's own code as a step
        // of its own: that is the first leg, and the declaration is refused
        // before any bring-up is paid for.
        dbo.declare(CLINIC, clinic(STEP, ""));
    }

    @AfterAll
    void theClinicIsRetracted() {
        dbo.retract(CLINIC);
    }

    // ── the step is the deployment's ──

    @Test
    @Order(1)
    @DisplayName("a clinic offering a step code the deployment performs does not come up, and "
            + "the deployment's record names the code, the management tenant and both keys")
    @Proving({DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
            DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE})
    void aClinicMayNotOfferTheDeploymentsStep() throws InterruptedException {
        Optional<Run> recorded = untilRecorded(CLINIC, true);
        assertTrue(recorded.isPresent(), "the deployment recorded nothing about a clinic it "
                + "was told about and does not serve, so the refusal is nowhere to be read");
        String said = recorded.get().item().message();

        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                "failed".equals(stateOf(CLINIC)) && !dbo.serving().contains(CLINIC),
                "the clinic came up offering the deployment's own step, so two schedulers now "
                        + "reach for one run and both are right: " + stateOf(CLINIC));
        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                said.contains(STEP) && said.contains("'" + CLINIC + "'"),
                "the refusal does not name the tenant and the code that collides, which are "
                        + "what somebody has to rename: " + said);
        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                said.contains("'steps'") && said.contains("'fleetSteps'"),
                "the refusal does not name both keys, so which of two files to open is a "
                        + "guess: " + said);
        // WHERE THE STEP IS DECLARED is the other half of what the refusal
        // says: the management tenant's own descriptor, under a key of its
        // own, which is why the clinic is told where the other side lives.
        Proves.that(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE,
                said.contains("management tenant '" + MANAGEMENT + "'"),
                "the refusal does not say the step is the management tenant's to declare, so "
                        + "the other side of the collision is somewhere to go looking for: "
                        + said);
    }

    @Test
    @Order(2)
    @DisplayName("renaming the clinic's own step lets it come up, the refusal leaves the "
            + "deployment's record, and a tenant with a step of its own was never touched")
    @Proving(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL)
    void renamingItIsTheWayIn() throws InterruptedException {
        dbo.declare(CLINIC, clinic(OWN_STEP, ""));

        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                dbo.until(CLINIC, true, Duration.ofMinutes(10)),
                "the clinic did what the refusal asked and still does not serve, which makes "
                        + "the refusal a dead end: " + stateOf(CLINIC));
        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                untilRecorded(CLINIC, false).isEmpty(),
                "the clinic serves and the deployment's record still holds its refusal open, "
                        + "so an operator reading it is chasing something that is fixed");
        // Hogwarts offers a step of its own under a code of its own, and the
        // rule is about one code rather than about declaring steps.
        Proves.that(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL,
                "serving".equals(stateOf(HOSPITAL)),
                "the hospital, which offers a step nobody else claims, is not serving, so "
                        + "the rule refuses the act of declaring rather than the collision");
    }

    @Test
    @Order(3)
    @DisplayName("the deployment prepared a database for its step before any work, carrying "
            + "a durable layer and nothing of a tenant's")
    @Proving(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE)
    void theStepHasSomewhereForItsWork() {
        Proves.that(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE,
                queue.exists(SUBSTRATE),
                "the step the deployment declares has no database, so it has nowhere for its "
                        + "work to sit");
        // Asked of the database rather than of the code, because the claim is
        // about what was made: a store schema here would mean it went through
        // the path that provisions a tenant.
        Set<String> schemas = queue.schemas(SUBSTRATE);
        Proves.that(DboPromises.PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE,
                schemas.equals(Set.of("dbos")),
                "the step's database is not a durable layer and nothing else, so it looks like "
                        + "a tenant to everything downstream: " + schemas);
    }

    // ── work is asked for on the tenant's own door ──

    @Test
    @Order(4)
    @DisplayName("the worker asks the hospital for a check it cannot perform, naming the "
            + "organisation by what it knows, and the run records the reference that matched")
    @Proving({DboPromises.PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM,
            DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH,
            DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT,
            DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE})
    void theHospitalIsAskedForACheck() {
        Proves.that(DboPromises.PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM,
                !dbo.performing().containsKey(STEP),
                "the worker performs " + STEP + " itself, so nothing here shows that a "
                        + "participant can ask for work it does not do: " + dbo.performing());

        String infirmary = organisationAt(HOSPITAL, "urn:rl:org", NAMES.value("infirmary"),
                NAMES.value("infirmary"));
        DboInitiator.Started started = asking.about(HOSPITAL,
                NAMES.value("infirmary"));
        // 201 for a step the hospital never declared and never could: the
        // DEPLOYMENT declared it, which is the one thing that makes a tenant's
        // door take a step it does not offer.
        Proves.that(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE,
                started.accepted(),
                "the hospital's door would not start a run of the deployment's step: "
                        + started.status() + " " + started.body());
        Proves.that(DboPromises.PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM,
                started.accepted(),
                "a participant that may act in work could not ask for work somebody else "
                        + "performs: " + started.body());

        Run asked = runs(HOSPITAL).byKey(started.key()).orElseThrow(
                () -> new AssertionError("the door answered with a run the hospital does not "
                        + "hold: " + started.body()));
        RunSlot org = asked.inputs().get("org");
        Proves.that(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH,
                org.referred() && List.of("Organization/" + infirmary).equals(org.values()),
                "the run did not record the reference the search matched, so what it is over "
                        + "can still change underneath it: " + org);
        RunSlot proposed = asked.inputs().get("proposed");
        RunSlot notes = asked.inputs().get("notes");
        Proves.that(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT,
                !proposed.referred() && !notes.referred() && notes.many()
                        && notes.values().size() == 2,
                "the proposal and the notes were not carried with the run as objects, the "
                        + "notes as a list of two: proposed=" + proposed + " notes=" + notes);
        hospitalsCheck = asked;
    }

    @Test
    @Order(5)
    @DisplayName("a search matching two organisations fills no slot that takes one and says "
            + "how many, and a step no level declares is not found")
    @Proving(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH)
    void aSearchMustNameOne() {
        String twins = NAMES.value("twins");
        organisationAt(HOSPITAL, "urn:rl:org", NAMES.value("twin-1"), twins);
        organisationAt(HOSPITAL, "urn:rl:org", NAMES.value("twin-2"), twins);

        HttpResponse<String> refused = askAt(HOSPITAL, STEP, "Organization?name=" + twins);
        Proves.that(DboPromises.PROC_A_REFERENCE_MAY_BE_A_SEARCH,
                refused.statusCode() == 400 && refused.body().contains("matched 2"),
                "a search matching two filled a slot that takes one, or was refused without "
                        + "saying how many it matched: " + refused.statusCode() + " "
                        + refused.body());

        HttpResponse<String> unknown = askAt(HOSPITAL, MODULE + ".nobody.declares",
                "Organization?name=" + twins);
        assertEquals(404, unknown.statusCode(),
                "the door started a run of a step no level declares, so asking for one is a "
                        + "way in rather than a capability: " + unknown.body());
    }

    // ── one bean performs it for every tenant ──

    @Test
    @Order(6)
    @DisplayName("the joiner offers the hospital's run into the step's queue, and the bean the "
            + "container found closes it in the hospital, naming itself and handed every slot")
    @Proving({DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
            DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED,
            DboPromises.PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS,
            DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES})
    void theBeanPerformsTheHospitalsCheck() throws InterruptedException {
        Run asked = hospitalsCheck;
        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                untilOffered(HOSPITAL, asked).equals(Optional.of(FleetWork.queueFor(STEP))),
                "the hospital's run never reached its step's queue under the tenant and the run "
                        + "it came from, so a deployment that declared a step performs none of "
                        + "its work");

        Run closed = untilClosed(HOSPITAL, asked.key());
        // THE BEAN THE CONTAINER FOUND. Nothing in either application built a
        // consumer, a durable layer or a pool, and the name on the run is the
        // one the serving sample's bean gave in its own annotation.
        Proves.that(DboPromises.PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED,
                closed != null && PERFORMER.equals(executorOf(closed)),
                "the hospital's check was not closed by the bean the serving application "
                        + "holds: " + closed);
        Proves.that(DboPromises.PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES,
                PERFORMER.equals(executorOf(closed)) && closed.tally().get("checked") == 1L,
                "the run did not close in the hospital naming the performer the application "
                        + "gave, with what it counted: " + closed);
        // The bean refuses the run unless the organisation, the proposal and
        // each note arrive as the type the step declares, so a run closed with
        // two notes counted is a run whose every slot was resolved and carried.
        Proves.that(DboPromises.PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS,
                closed.tally().get("notes") == 2L && closed.tally().get("bytes") > 0L,
                "the bean was not handed the objects the run named: " + closed.tally());
    }

    @Test
    @Order(7)
    @DisplayName("the clinic that joined after the bean was deployed has its check performed by "
            + "the same bean, and its own step's run is left where it is")
    @Proving({DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT,
            DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK})
    void theSameBeanPerformsTheClinicsCheck() throws InterruptedException {
        clinicOrganisation = organisationAt(CLINIC, NAMES.system(), NAMES.value("surgery"),
                NAMES.value("surgery"));
        // ITS OWN STEP FIRST, so that the clinic's check, authored after it, is
        // read after it: once the check is offered, the joiner has read past
        // the clinic's own run and left it behind or not.
        Run own = runs(CLINIC).of(REVIEW, RunKind.PIPELINE, OWN_STEP + "/" + NAMES.run(),
                Map.of("record", "Basic/" + basicAt(CLINIC, NAMES.value("reviewed"))));

        HttpResponse<String> asked = askAt(CLINIC, STEP,
                "Organization?identifier=" + NAMES.system() + "|" + NAMES.value("surgery"));
        assertEquals(201, asked.statusCode(),
                "the clinic's door would not start a check: " + asked.body());
        String key = dbo.says(asked).one("key").orElseThrow(
                () -> new AssertionError("the door started no run it names: " + asked.body()));
        Run check = runs(CLINIC).byKey(key).orElseThrow();

        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                untilOffered(CLINIC, check).isPresent(),
                "the clinic's check never reached the step's queue, so a tenant that joined "
                        + "after the deployment declared its step is not followed");
        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                queue.queueOf(SUBSTRATE, FleetWork.idFor(CLINIC, own.id())).isEmpty(),
                "a run of the clinic's own step was lifted into the deployment's queue, so the "
                        + "deployment performs a step it neither declares nor may read");

        clinicsCheck = untilClosed(CLINIC, key);
        Proves.that(DboPromises.PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT,
                clinicsCheck != null && PERFORMER.equals(executorOf(clinicsCheck)),
                "the clinic's check was not performed by the bean that performed the "
                        + "hospital's, so a tenant joining needs something redeployed: "
                        + clinicsCheck);
    }

    @Test
    @Order(8)
    @DisplayName("the joiner made to read the clinic's work again from the start offers every "
            + "run once, and performs nothing twice")
    @Proving(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK)
    void readingAgainOffersOnce() throws InterruptedException {
        Set<String> before = queue.offeredFrom(SUBSTRATE, CLINIC);
        assertEquals(Set.of(FleetWork.idFor(CLINIC, clinicsCheck.id())), before,
                "the clinic's work in the queue is not its one check, so there is nothing to "
                        + "read again: " + before);

        // What a restart that lost its last acknowledgement finds: the joiner's
        // place in the clinic's work, back at the beginning.
        queue.joinerForgets(CLINIC, JOINER);
        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                until(() -> queue.joinerHasRead(CLINIC, JOINER), Duration.ofMinutes(2)),
                "the joiner never read the clinic's work again, so this proves nothing about "
                        + "offering a run twice");

        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                before.equals(queue.offeredFrom(SUBSTRATE, CLINIC)),
                "reading the clinic's work again wrote a second item, so a restart duplicates "
                        + "every run in flight: " + queue.offeredFrom(SUBSTRATE, CLINIC));
        Run now = runs(CLINIC).byKey(clinicsCheck.key()).orElseThrow();
        Proves.that(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK,
                now.versionId() == clinicsCheck.versionId(),
                "the clinic's closed check changed after its work was read again, so it was "
                        + "performed a second time: " + now);
    }

    @Test
    @Order(9)
    @DisplayName("at the clinic's lane a referred record arrives resolved, a given object "
            + "arrives as no record, and a list arrives in the order it was written")
    @Proving(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT)
    void allThreeShapesArrive() {
        TenantAuthority authority = tenants.authority(CLINIC).orElseThrow();
        authority.ensureClient(SHAPER, SHAPER + "-secret", List.of("work/" + SHAPES));
        Supplier<String> credential = () -> {
            if (authority.token(SHAPER, SHAPER + "-secret", null)
                    instanceof TenantAuthority.TokenResult.Issued minted) {
                return minted.accessToken();
            }
            throw new IllegalStateException("the clinic would not issue the participant a "
                    + "token");
        };
        HttpLane lane = HttpLane.to(URI.create(dbo.at(CLINIC) + "/work"), credential, CLINIC,
                SHAPER, new Executor(SHAPER, "1", "test.dbo.story", Scope.BASELINE));
        lane.introduce(OVER_ALL_THREE);

        String stored = basicAt(CLINIC, NAMES.value("held"));
        Run run = runs(CLINIC).filling(OVER_ALL_THREE, RunKind.PIPELINE,
                SHAPES + "/" + stored, Map.of(
                        "held", RunSlot.referring("Basic/" + stored),
                        "proposed", RunSlot.given(basic("a proposal kept nowhere")),
                        "notes", RunSlot.givenAll(List.of(basic("first"), basic("second")))));

        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow(
                () -> new AssertionError("the participant could not claim the run"));
        Map<String, List<StoredObject>> inputs = lane.inputs(held);

        Proves.that(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT,
                NAMES.value("held").equals(textOf(inputs.get("held").get(0)))
                        && stored.equals(inputs.get("held").get(0).id())
                        && inputs.get("held").get(0).versionId() > 0,
                "the referred slot did not arrive as the record it names");
        StoredObject given = inputs.get("proposed").get(0);
        Proves.that(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT,
                "a proposal kept nowhere".equals(textOf(given)) && given.id() == null
                        && given.versionId() == 0L && "Basic".equals(given.typeName()),
                "the given object did not arrive as an object of its type with no id and no "
                        + "version, so a participant can treat something never stored as a "
                        + "record: id=" + given.id() + " version=" + given.versionId());
        // ORDER, asserted as order: a set would pass this and lose what a
        // repeat adds.
        List<String> notes = inputs.get("notes").stream()
                .map(OneStepIsPerformedForEveryTenantIT::textOf).toList();
        Proves.that(DboPromises.PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT,
                List.of("first", "second").equals(notes),
                "the repeating slot did not arrive whole and in the order it was filled: "
                        + notes);
    }

    // ── a tenant says no in one line ──

    // ── what the clinic reads of what is done to its data ──

    @Test
    @Order(10)
    @DisplayName("the clinic reads at its own door what the deployment opens of its data: one "
            + "row, for the one slot the check opens, and none for what it only carries")
    @Proving({DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA,
            DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR})
    void theClinicReadsItsRegister() {
        HttpResponse<String> register = dbo.get(dbo.at(CLINIC) + "/register",
                configurationToken(CLINIC));
        assertEquals(200, register.statusCode(), register.body());
        List<Map<?, ?>> rows = rowsOf(register.body(), "rows");
        Proves.that(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA,
                rows.size() == 1 && STEP.equals(rows.get(0).get("step"))
                        && "org".equals(rows.get(0).get("slot"))
                        && "processed-and-named".equals(rows.get(0).get("posture")),
                "the clinic's register is not one row for the one slot the deployment's check "
                        + "opens, so what is opened of its data and what it reads differ: "
                        + register.body());
        // The proposal and the notes travel with the run and are never opened
        // by the step, so they disclose nothing and are not on the register.
        Proves.that(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA,
                rows.stream().noneMatch(row -> "proposed".equals(row.get("slot"))
                        || "notes".equals(row.get("slot"))),
                "a slot the step only carries is on the register: " + register.body());
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                !fieldsOf(register.body()).containsKey("changedSinceAuthorised"),
                "the clinic never authorised a register and is told whether it changed, and "
                        + "never having read one is a different answer: " + register.body());

        // Reading the records is not entitlement to read what the deployment
        // may open of them, and the operator's token is not a tenant's.
        HttpResponse<String> withRecords = dbo.get(dbo.at(CLINIC) + "/register",
                dbo.token(CLINIC));
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                withRecords.statusCode() == 403 && withRecords.body().contains("configuration"),
                "a credential that reads records read the register, or was refused without "
                        + "saying which grant it lacks: " + withRecords.statusCode() + " "
                        + withRecords.body());
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                dbo.get(dbo.at(CLINIC) + "/register", OPS).statusCode() == 401,
                "the deployment's operator token read a tenant's register at the tenant's door");
    }

    @Test
    @Order(11)
    @DisplayName("the check ran over the clinic's data under a row it never authorised, and its "
            + "account says so — and the operator reads that it stands without reading the data")
    @Proving({DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE,
            DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR})
    void whatRanUnauthorisedIsNamed() {
        assertTrue(clinicsCheck != null, "the clinic's check was not performed in leg 7");
        HttpResponse<String> incidents = dbo.get(dbo.at(CLINIC) + "/register/incidents",
                configurationToken(CLINIC));
        assertEquals(200, incidents.statusCode(), incidents.body());
        List<Map<?, ?>> unauthorised = rowsOf(incidents.body(), "unauthorised");
        Proves.that(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE,
                unauthorised.stream().anyMatch(row -> STEP.equals(row.get("step"))
                        && "org".equals(row.get("slot")) && row.get("since") != null
                        && String.valueOf(row.get("says")).contains(CLINIC)),
                "the check ran over the clinic's data under the default row, which it never "
                        + "authorised, and no incident names the step, the slot and since "
                        + "when: " + incidents.body());

        HttpResponse<String> fleet = dbo.get(
                URI.create(dbo.at(HOSPITAL)).resolve("/runtime/fleet").toString(), OPS);
        assertEquals(200, fleet.statusCode(), fleet.body());
        Map<?, ?> clinicsRow = rowsOf(fleet.body(), "tenants").stream()
                .filter(row -> CLINIC.equals(row.get("code"))).findFirst()
                .orElseThrow(() -> new AssertionError("the operator's fleet view does not "
                        + "name the clinic: " + fleet.body()));
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                clinicsRow.get("unauthorisedIncidents") instanceof Number count
                        && count.intValue() >= 1
                        && List.of(STEP + "/org").equals(clinicsRow.get("unapproved"))
                        && !fleet.body().contains(clinicOrganisation),
                "the operator cannot read that the clinic has an unauthorised row standing, or "
                        + "reads the clinic's own records doing so: " + clinicsRow);
        // Every bean the sample application holds names a step the deployment
        // declares, so nothing is waiting, and the node says so rather than
        // keeping it to itself.
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                List.of().equals(fieldsOf(fleet.body()).get("awaitingDeclaration")),
                "the node does not say which of its beans wait for a declaration: "
                        + fleet.body());
        Proves.that(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR,
                dbo.get(URI.create(dbo.at(HOSPITAL)).resolve("/runtime/fleet").toString(),
                        configurationToken(CLINIC)).statusCode() == 401,
                "a tenant's credential read the whole deployment's fleet view");
    }

    @Test
    @Order(12)
    @DisplayName("the clinic authorises the register it read in one act and is told nothing "
            + "changed, its incident clears, and a register it did not read is a change")
    @Proving({DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE,
            DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE})
    void theClinicAuthorisesWhatItRead() throws InterruptedException {
        // EVERY ROW IT READ, in one act: that is what authorising is.
        List<Map<?, ?>> rows = rowsOf(dbo.get(dbo.at(CLINIC) + "/register",
                configurationToken(CLINIC)).body(), "rows");
        String asItIs = rows.stream().map(row -> "\"" + row.get("digest") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        dbo.declare(CLINIC, clinic(OWN_STEP, ",\"authorised\":[" + asItIs + "]"));
        Proves.that(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE,
                until(() -> Boolean.FALSE.equals(registerField(CLINIC,
                        "changedSinceAuthorised")), Duration.ofMinutes(2)),
                "the clinic authorised exactly the register it read and is not told that "
                        + "nothing changed: " + dbo.get(dbo.at(CLINIC) + "/register",
                        configurationToken(CLINIC)).body());
        Proves.that(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE,
                rowsOf(dbo.get(dbo.at(CLINIC) + "/register/incidents",
                        configurationToken(CLINIC)).body(), "unauthorised").isEmpty(),
                "the clinic authorised the row and its incident still stands");

        // A register it did not read: the comparison is the whole mechanism, so
        // a copy that is not what the deployment does is a change, at once.
        dbo.declare(CLINIC, clinic(OWN_STEP, ",\"authorised\":[\"" + NAMES.value("stale")
                + "\"]"));
        Proves.that(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE,
                until(() -> Boolean.TRUE.equals(registerField(CLINIC,
                        "changedSinceAuthorised")), Duration.ofMinutes(2)),
                "the clinic's authorisation names a register that is not the one in force, and "
                        + "it is not told the register changed");
    }

    @Test
    @Order(13)
    @DisplayName("the clinic declines the check: its door says so, and its work is not offered "
            + "while the hospital, which said nothing, goes on being served")
    @Proving({DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT,
            DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA})
    void theClinicDeclines() throws InterruptedException {
        // --8<-- [start:declines]
        dbo.declare(CLINIC, clinic(OWN_STEP, ",\"declines\":[\"" + STEP + "\"]"));
        // --8<-- [end:declines]

        String org = "Organization?identifier=" + NAMES.system() + "|" + NAMES.value("surgery");
        HttpResponse<String> refused = null;
        long giveUp = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < giveUp) {
            refused = askAt(CLINIC, STEP, org);
            if (refused.statusCode() == 409) {
                break;
            }
            Thread.sleep(500);
        }
        Proves.that(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT,
                refused.statusCode() == 409 && refused.body().contains("declines"),
                "the clinic declined the step while serving and its door still starts one, or "
                        + "refuses without saying it was the clinic's own word: "
                        + refused.statusCode() + " " + refused.body());
        Proves.that(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT,
                dbo.serving().contains(CLINIC),
                "declining a step took the clinic down, so withdrawing authorisation costs an "
                        + "outage");

        // A run of the check authored inside the clinic anyway, as its own
        // code might. Then two of the hospital's, one after the other: once the
        // second is offered, a whole pass of the joiner has begun after the
        // clinic's run was written, and has read the clinic too.
        Run declined = runs(CLINIC).of(CHECK, RunKind.PIPELINE,
                STEP + "/" + NAMES.value("declined"),
                Map.of("org", "Organization/" + clinicOrganisation));
        for (int i = 0; i < 2; i++) {
            DboInitiator.Started started = asking.about(HOSPITAL,
                    NAMES.value("infirmary"));
            assertTrue(started.accepted(), started.body());
            Run hospitals = runs(HOSPITAL).byKey(started.key()).orElseThrow();
            Proves.that(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT,
                    untilOffered(HOSPITAL, hospitals).isPresent(),
                    "the hospital, which said nothing about the step and so admits it, had "
                            + "its work left behind");
        }
        Proves.that(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT,
                queue.queueOf(SUBSTRATE, FleetWork.idFor(CLINIC, declined.id())).isEmpty(),
                "the clinic declined the step and its work was offered to it anyway, so "
                        + "declining is a note in a file rather than a rule");

        // From the clinic's side a step declined and a step not performed are
        // one fact, so the register it reads has no row for it.
        HttpResponse<String> register = dbo.get(dbo.at(CLINIC) + "/register",
                configurationToken(CLINIC));
        Proves.that(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA,
                rowsOf(register.body(), "rows").isEmpty()
                        && String.valueOf(fieldsOf(register.body()).get("declined"))
                                .contains(STEP),
                "the clinic declined the check and its register still lists what the check "
                        + "opens, or does not say it declined: " + register.body());
    }

    /**
     * A credential the tenant issued for saying what it agrees to: the
     * configuration scope, the one a register is authorised with and read by.
     */
    private String configurationToken(String tenant) {
        String client = NAMES.value("registrar");
        TenantAuthority authority = tenants.authority(tenant).orElseThrow();
        authority.ensureClient(client, client + "-secret", List.of("configuration"));
        if (authority.token(client, client + "-secret", null)
                instanceof TenantAuthority.TokenResult.Issued issued) {
            return issued.accessToken();
        }
        throw new AssertionError(tenant + " issued no token for its registrar");
    }

    private Object registerField(String tenant, String field) {
        return fieldsOf(dbo.get(dbo.at(tenant) + "/register", configurationToken(tenant))
                .body()).get(field);
    }

    private static Map<?, ?> fieldsOf(String body) {
        return (Map<?, ?>) cloud.jengu.dbo.core.wire.RecordWire.read(body);
    }

    private static List<Map<?, ?>> rowsOf(String body, String field) {
        Object listed = fieldsOf(body).get(field);
        return listed instanceof List<?> rows
                ? rows.stream().filter(Map.class::isInstance).<Map<?, ?>>map(row -> (Map<?, ?>) row)
                        .toList()
                : List.of();
    }

    // ── what the legs are made of ──

    /** The clinic's declaration, offering one step of its own. */
    private static String clinic(String ownStep, String more) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Organization","identity":"identifier","systems":["%s"],
                   "handling":"operational"},
                  {"name":"Basic","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"%s","slots":{"record":"Reference(Basic)"}}]%s}"""
                .formatted(CLINIC, NAMES.system(), ownStep, more);
    }

    private Runs runs(String tenant) {
        return new Runs(tenants.store(tenant).orElseThrow(
                () -> new AssertionError(tenant + " is not serving: " + dbo.serving())));
    }

    /** An organisation written through the tenant's records door, and its id. */
    private String organisationAt(String tenant, String system, String value, String name) {
        var written = dbo.write(tenant, "Organization", """
                {"resourceType":"Organization","name":"%s",
                 "identifier":[{"system":"%s","value":"%s"}]}""".formatted(name, system, value));
        assertTrue(written.accepted(), "the organisation was not accepted: " + written.body());
        return written.idOrFail();
    }

    private String basicAt(String tenant, String text) {
        var written = dbo.write(tenant, "Basic", basic(text));
        assertTrue(written.accepted(), "the record was not accepted: " + written.body());
        return written.idOrFail();
    }

    private static String basic(String text) {
        return "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"" + text + "\"}}";
    }

    private static String textOf(StoredObject object) {
        String json = new String(object.payload(), StandardCharsets.UTF_8);
        int from = json.indexOf("\"text\":\"") + "\"text\":\"".length();
        return json.substring(from, json.indexOf('"', from));
    }

    /**
     * Asks a tenant's step door for a check over one organisation, with a
     * proposal and two notes given, carrying a credential that may act in work.
     */
    private HttpResponse<String> askAt(String tenant, String step, String org) {
        String body = """
                {"inputs":{"org":"%s",
                  "proposed":{"resourceType":"Organization","name":"%s"},
                  "notes":[%s,%s]}}""".formatted(org, NAMES.value("proposed"),
                basic("the sign over the door is new"), basic("so is the telephone"));
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + "/step/" + step))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), dbo.workToken(tenant));
    }

    /** The queue a run was offered into, once it has been. */
    private Optional<String> untilOffered(String tenant, Run run) throws InterruptedException {
        String item = FleetWork.idFor(tenant, run.id());
        until(() -> queue.queueOf(SUBSTRATE, item).isPresent(), Duration.ofMinutes(2));
        return queue.queueOf(SUBSTRATE, item);
    }

    /** The run once nobody holds it any more, or null if that never happened. */
    private Run untilClosed(String tenant, String key) throws InterruptedException {
        until(() -> runs(tenant).byKey(key).map(run -> !run.open())
                .orElse(false), Duration.ofMinutes(3));
        return runs(tenant).byKey(key).filter(run -> !run.open())
                .orElse(null);
    }

    private static String executorOf(Run run) {
        return run == null || run.assignment() == null || run.assignment().executor() == null
                ? null : run.assignment().executor().name();
    }

    /**
     * What the deployment's own record in the management tenant says about a
     * tenant it was told about, while that item is open — or, asked for an
     * absence, once it has closed.
     */
    private Optional<Run> untilRecorded(String code, boolean open) throws InterruptedException {
        until(() -> recordedAbout(code).isPresent() == open, Duration.ofMinutes(2));
        return recordedAbout(code);
    }

    private Optional<Run> recordedAbout(String code) {
        Runs management = runs(MANAGEMENT);
        return management.byKey(SERVING).stream()
                .flatMap(sweep -> management.items(sweep).stream())
                .filter(item -> item.item() != null && code.equals(item.item().reference())
                        && item.open())
                .findFirst();
    }

    /** What the node says of one tenant's state, under the deployment's own token. */
    private String stateOf(String code) {
        HttpResponse<String> answered = dbo.get(
                URI.create(dbo.at(HOSPITAL)).resolve("/runtime/tenants").toString(), OPS);
        Object rows = ((Map<?, ?>) cloud.jengu.dbo.core.wire.RecordWire.read(answered.body()))
                .get("tenants");
        for (Object row : (List<?>) rows) {
            Map<?, ?> fields = (Map<?, ?>) row;
            if (code.equals(fields.get("code"))) {
                return String.valueOf(fields.get("state"));
            }
        }
        return null;
    }

    private static boolean until(java.util.function.BooleanSupplier done, Duration give)
            throws InterruptedException {
        long giveUp = System.nanoTime() + give.toNanos();
        while (System.nanoTime() < giveUp) {
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(500);
        }
        return done.getAsBoolean();
    }
}
