package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overturning a closure is somebody's job, and it was nobody's door.
 *
 * <p>A closed run can be reopened — the store has promised it, and the
 * primitive has been proven since it was written. Nothing outside the
 * container could reach it: the console reads and never acts, and the lane's
 * vocabulary had every verb a participant needs and not this one. So an
 * operator who discovered a close was wrong had exactly the recourse the
 * promise exists to remove — inventing a second run to disagree with the
 * first.
 *
 * <p>The verb is now on the lane, and it is reached by <b>its own half</b> of
 * an entitlement. Performing a step and overturning its closures are
 * different authorities, the way erasing a person is not a consequence of
 * being able to write them: a bench that validates results does not thereby
 * get to reopen the ones somebody judged done, a credential speaking for the
 * whole tenant supervises nothing until the word is written down, and a
 * supervisor takes no work.
 *
 * <p>Driven over real HTTP against a real tenant, with real tokens through the
 * tenant's own authority — because a surface that was built and never mounted
 * is this repository's characteristic failure, and only a token going through
 * the authority tells a mounted lane from a class that compiles.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupervisionIsItsOwnEntitlementIT {

    private static final String TENANT = "jarelevalve";
    private static final String PROCESS = "dbo.lab";

    /** The full vocabulary, reopening included. */
    private static final StepDeclaration VALIDATE =
            StepDeclaration.of(PROCESS + ".validate", "1.0", WorkModel.DOMAIN)
                    .containing("open", "close", "reopen");

    /** Closes, and a close here is final: nobody declared reopen. */
    private static final StepDeclaration DISPATCH =
            StepDeclaration.of(PROCESS + ".dispatch", "1.0", WorkModel.DOMAIN)
                    .containing("open", "close");

    /** Its closure is a person's act: automation prepares, somebody decides. */
    private static final StepDeclaration REVIEW =
            StepDeclaration.of(PROCESS + ".review", "1.0", WorkModel.DOMAIN)
                    .containing("open");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static final HttpClient http = HttpClient.newHttpClient();
    static URI laneUri;
    static Runs runs;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-tenants-supervision");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("SupervisionIsItsOwnEntitlementIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null),
                FhirVersions.installed(), Steps.of(VALIDATE, DISPATCH, REVIEW));
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        laneUri = URI.create("http://127.0.0.1:" + manager.port() + "/t/" + TENANT + "/work");
        runs = new Runs(manager.runtime(TENANT).orElseThrow().engine(),
                Steps.of(VALIDATE, DISPATCH, REVIEW));
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

    /** A run of this step, closed — what somebody later decides was wrong. */
    private static Run closedRun(StepDeclaration step, String key) {
        Run run = runs.of(step, RunKind.PIPELINE, key);
        runs.closed(runs.byKey(run.key()).orElseThrow());
        Run closed = runs.byKey(run.key()).orElseThrow();
        assertFalse(closed.open(), "the fixture must start closed: " + closed.key());
        return closed;
    }

    @Test
    @DisplayName("a supervisor reopens a closed run over the lane, and it is claimable again "
            + "with the reason on the record")
    @Proving({DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
            DboPromises.PROC_CLOSED_CAN_BE_REOPENED})
    void aSupervisorReopensAClosedRunOverTheLane() throws Exception {
        Run closed = closedRun(VALIDATE, "supervised-validate-1");
        Lane supervisor = lane("valvur", "supervise/" + PROCESS + ".validate");

        supervisor.reopen(closed, "the control sample was expired");

        Run reopened = runs.byKey(closed.key()).orElseThrow();
        assertTrue(reopened.open(), "the run is still closed, so the door did not reach the "
                + "act — which is the whole of what was missing");
        assertEquals("the control sample was expired", reopened.assignment().note(),
                "the reason is on the record, or the reopening is an unexplained change");
    }

    @Test
    @DisplayName("a bench that performs the step cannot overturn its closures")
    @Proving(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT)
    void aBenchThatPerformsTheStepCannotOverturnItsClosures() throws Exception {
        Run closed = closedRun(VALIDATE, "supervised-validate-2");
        Lane bench = lane("pink", "work/" + PROCESS + ".validate");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bench.reopen(closed, "I would rather it were open"));

        assertTrue(refused.getMessage().contains("not entitled to reopen"),
                "refused by name, so the operator learns which half said no: "
                        + refused.getMessage());
        assertFalse(runs.byKey(closed.key()).orElseThrow().open(),
                "the run reopened anyway, so performing a step carries overturning it");
    }

    @Test
    @DisplayName("the tenant's own credential supervises nothing until somebody writes it down")
    @Proving(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT)
    void theTenantsOwnCredentialSupervisesNothingUnlessSaid() throws Exception {
        Run closed = closedRun(VALIDATE, "supervised-validate-3");
        // The deployment's own credential for this tenant: it speaks for the
        // whole tenant on the lane and may serve one in anybody's name. That
        // is the broadest working grant there is, and it is still not this.
        Lane theTenant = HttpLane.to(laneUri,
                () -> token("tenant-bootstrap", provisioner.bootstrapClientSecret(TENANT)),
                TENANT, "tenant-bootstrap",
                new Executor("tenant-bootstrap", "1.0", "cloud.jengu.test", Scope.BASELINE));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> theTenant.reopen(closed, "because I am the tenant"));

        assertTrue(refused.getMessage().contains("not entitled to reopen"), refused.getMessage());
        assertFalse(runs.byKey(closed.key()).orElseThrow().open(),
                "the broadest working credential overturned a closure, which is exactly the "
                        + "implication this scope was split to prevent");
    }

    @Test
    @DisplayName("a supervisor is refused a step its credential does not name")
    @Proving(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT)
    void aSupervisorIsRefusedAStepItDoesNotName() throws Exception {
        Run closed = closedRun(DISPATCH, "supervised-dispatch-1");
        Lane bounded = lane("valvur-piiratud", "supervise/" + PROCESS + ".validate");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.reopen(closed, "not mine to reopen"));

        assertTrue(refused.getMessage().contains("not entitled to reopen"), refused.getMessage());
        assertTrue(refused.getMessage().contains("dispatch"),
                "the refusal names the step asked for: " + refused.getMessage());
    }

    @Test
    @DisplayName("a step whose declaration omits reopening refuses every credential, "
            + "supervisory or not")
    @Proving({DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
            DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS})
    void aStepWhoseDeclarationOmitsReopeningRefusesEverybody() throws Exception {
        Run closed = closedRun(DISPATCH, "supervised-dispatch-2");
        // Bare: this credential supervises every step there is, so what
        // refuses here is the step's half and nothing else.
        Lane everyStep = lane("ulemvalvur", "supervise");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> everyStep.reopen(closed, "the batch was recalled"));

        assertTrue(refused.getMessage().contains("reopen"), refused.getMessage());
        assertFalse(runs.byKey(closed.key()).orElseThrow().open(),
                "a step that declared its closures final was reopened anyway");

        // and the same credential reopens the step that does declare it, so
        // what was refused is the declaration and not the credential
        Run reopenable = closedRun(VALIDATE, "supervised-validate-4");
        everyStep.reopen(reopenable, "the control sample was expired");
        assertTrue(runs.byKey(reopenable.key()).orElseThrow().open(),
                "the bare supervisory scope reopened nothing at all, so the refusal above "
                        + "was about the credential rather than the declaration");
    }

    @Test
    @DisplayName("a supervisor reaches the lane and takes no work")
    @Proving(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT)
    void aSupervisorTakesNoWork() throws Exception {
        Run waiting = runs.of(VALIDATE, RunKind.PIPELINE, "supervised-validate-5");
        Lane supervisor = lane("valvur-toota-mitte", "supervise/" + PROCESS + ".validate");

        assertTrue(supervisor.poll(Set.of("validate"), 10).isEmpty(),
                "a supervisory credential was offered work; supervising is not performing, "
                        + "and a door that offered both would make every supervisor a runner");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> supervisor.claim(waiting, Duration.ofMinutes(1)));
        assertTrue(refused.getMessage().contains("not entitled to claim"),
                "a claim outside the entitlement is refused by name: " + refused.getMessage());
    }

    @Test
    @DisplayName("the lane holds a participant to the step's declared actions, so a step "
            + "whose closure is a person's act refuses a bench that reports done")
    @Proving(DboPromises.PROC_REPORT_THROUGH_DECLARED_ACTIONS)
    void theLaneHoldsAParticipantToTheDeclaredActions() throws Exception {
        // The rule lives at the primitive so every door meets one copy of it,
        // and the primitive resolves the declaration through a catalogue. The
        // lane was built without one, so it resolved nothing and narrowed
        // nothing: this door accepted every verb of every step, and the rule
        // held only where a caller happened to pass a catalogue — which is
        // what a test does and what the container did not.
        Run prepared = runs.of(REVIEW, RunKind.PIPELINE, "supervised-review-1");
        Lane bench = lane("pink-review", "work/" + PROCESS + ".review");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bench.closed(prepared));

        assertTrue(refused.getMessage().contains("close"), refused.getMessage());
        assertTrue(runs.byKey(prepared.key()).orElseThrow().open(),
                "a step that said its closure is somebody's judgment was closed by a "
                        + "participant reporting done");
    }

    @Test
    @DisplayName("a bounded credential cannot reach another step's run by describing it as "
            + "one it is entitled to — the lane reads the store's own copy")
    @Proving({DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT,
            DboPromises.PROC_CLAIM_IS_THE_INTERSECTION})
    void aMisdescribedRunDoesNotWidenAnEntitlement() throws Exception {
        // The body crosses the wire, so the process and step in it are the
        // asker's words. The primitive re-reads the run before it writes, so
        // a lane that believed those words would check the entitlement
        // against a step the run does not have and then act on the run that
        // it does — the credential's bound holding only for callers who
        // describe their work honestly.
        Run dispatch = closedRun(DISPATCH, "supervised-dispatch-3");
        Run lying = new Run(dispatch.id(), dispatch.versionId(), dispatch.key(),
                PROCESS, "validate", dispatch.kind(), dispatch.holder(), dispatch.parent(),
                dispatch.correlation(), dispatch.trace(), dispatch.tally(), dispatch.item(),
                dispatch.domains(), dispatch.assignment(), dispatch.produced(),
                dispatch.stepVersion(), dispatch.inputs(), dispatch.milestone());

        Lane bounded = lane("valvur-valetaja", "supervise/" + PROCESS + ".validate");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.reopen(lying, "described as something I may reopen"));

        assertTrue(refused.getMessage().contains("dispatch"),
                "the refusal names the step the RUN has, not the one the body claimed: "
                        + refused.getMessage());
        assertFalse(runs.byKey(dispatch.key()).orElseThrow().open(),
                "a bounded supervisor reopened another step's run by misnaming it");

        // and the same shape on a claim, which had it too
        Run waiting = runs.of(DISPATCH, RunKind.PIPELINE, "supervised-dispatch-4");
        Run lyingOpen = new Run(waiting.id(), waiting.versionId(), waiting.key(),
                PROCESS, "validate", waiting.kind(), waiting.holder(), waiting.parent(),
                waiting.correlation(), waiting.trace(), waiting.tally(), waiting.item(),
                waiting.domains(), waiting.assignment(), waiting.produced(),
                waiting.stepVersion(), waiting.inputs(), waiting.milestone());
        Lane bench = lane("pink-valetaja", "work/" + PROCESS + ".validate");

        IllegalStateException claimRefused = assertThrows(IllegalStateException.class,
                () -> bench.claim(lyingOpen, Duration.ofMinutes(1)));
        assertTrue(claimRefused.getMessage().contains("dispatch"), claimRefused.getMessage());
    }

    @Test
    @DisplayName("a supervisor says which run and nothing else about it")
    @Proving(DboPromises.PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT)
    void aSupervisorNamesTheRunAndTheStoreSuppliesTheRest() throws Exception {
        Run closed = closedRun(VALIDATE, "supervised-validate-6");
        Lane supervisor = lane("valvur-nimeline", "supervise/" + PROCESS + ".validate");

        // Only the key travels. Everything the run is remains the store's,
        // which is what lets a reader that holds envelopes act at all.
        supervisor.reopen(Run.named(closed.key()), "the control sample was expired");

        Run reopened = runs.byKey(closed.key()).orElseThrow();
        assertTrue(reopened.open(), "a run named only by its key was not reopened");
        assertEquals("the control sample was expired", reopened.assignment().note());
        assertEquals(VALIDATE.id().toString(),
                reopened.process() + "." + reopened.step(),
                "the envelope was rewritten from the body rather than kept: "
                        + reopened.process() + "." + reopened.step());
    }

    private static Lane lane(String participant, String... scopes) throws Exception {
        String secret = participant + "-secret";
        manager.authority(TENANT).ensureClient(participant, secret, List.of(scopes));
        String token = token(participant, secret);
        return HttpLane.to(laneUri, () -> token, TENANT, participant,
                new Executor(participant, "1.0", "cloud.jengu.test", Scope.BASELINE));
    }

    private static String token(String clientId, String secret) {
        try {
            String form = "grant_type=client_credentials&client_id="
                    + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            String body = http.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + manager.port()
                                            + "/t/" + TENANT + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        } catch (Exception e) {
            throw new IllegalStateException("no token for " + clientId, e);
        }
    }
}
