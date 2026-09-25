package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.StoreUnreachableException;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run reaches the lane whichever door authored it.
 *
 * <p>The last unexplained thing in item 029, isolated. A bean that brings its
 * own step performs nothing, and everything else has been eliminated by
 * measurement: the run is created and correctly shaped, the service is wired,
 * the credential narrows to everything, the lane reads the feed runs are
 * written to, and a sibling probe shows the lane offers a run of an introduced
 * step exactly as it offers one of an installed step.
 *
 * <p><b>The executor is declared at an organisation</b>, which is not the
 * baseline a participant performing the step it brought would use. That is
 * deliberate: it mirrors the worker assembly, which scopes an executor to the
 * tenant its lane is into — and it makes no difference to what is offered here,
 * so that is one more thing this eliminates rather than a detail of the setup.
 *
 * <p><b>What is left is how the run got into the store.</b> A tenant's own step
 * is asked for at the step door and its runs are performed. A step somebody
 * brought cannot be asked for there — that door is built from the tenant's spec
 * and refuses by name — so its run is authored through the face's document
 * door instead. That is the one difference, so it is the only thing this varies:
 * one step, one tenant, one lane, and two ways a run of it comes to exist.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AFaceAuthoredRunReachesTheLaneIT {

    private static final String PROCESS = "probe.doors";

    private static final String STEP = "countersign";

    /** Brought rather than installed, and with no slots, so a run needs no inputs. */
    private static final StepDeclaration BROUGHT =
            StepDeclaration.of(PROCESS + "." + STEP, "1", WorkModel.DOMAIN);

    private static final HttpClient http = HttpClient.newHttpClient();

    static SharedTenants.Tenant tenant;
    static URI laneUri;
    static Runs runs;

    @BeforeAll
    void up() {
        // Shared with the lane-over-http class rather than a tenant of its
        // own: a step name is claimed once per tenant and this one is nobody
        // else's, so the world does not grow for this question.
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL, 6);
        laneUri = URI.create(tenant.base() + "/work");
        runs = new Runs(tenant.engine());
        introduceTheStep();
    }

    /**
     * The step, brought as a participant brings one.
     *
     * <p>In the fixture rather than in the test that needs it first, because a
     * catalogue with no declaration for a step admits every executor — there is
     * nothing to check against — so a test relying on another to have
     * introduced it passes for the wrong reason when it runs second and proves
     * nothing when it runs first.
     */
    private static void introduceTheStep() {
        Lane introducing = HttpLane.to(laneUri, () -> work(), tenant.code(), "two-door-probe",
                new Executor("two-door-probe", "1", tenant.code(),
                        Scope.organisation(tenant.code())));
        try (StepRunner runner = new StepRunner(Duration.ofMinutes(5), Duration.ofMillis(50))) {
            runner.register(new StepService() {
                @Override
                public String step() {
                    return BROUGHT.id().toString();
                }

                @Override
                public Optional<StepDeclaration> declaration() {
                    return Optional.of(BROUGHT);
                }

                @Override
                public Outcome perform(Work work) {
                    return Outcome.done();
                }
            });
            runner.attach(introducing);
            runner.cycle();
        }
    }

    @Test
    @DisplayName("a run authored through the face's document door is offered on the lane, as a "
            + "run of the same step minted directly is")
    void bothDoorsReachTheLane() throws Exception {
        Lane lane = HttpLane.to(laneUri, () -> work(), tenant.code(), "two-door-probe",
                new Executor("two-door-probe", "1", tenant.code(),
                        Scope.organisation(tenant.code())));

        // DOOR ONE: the face's document door, which is the only door a brought
        // step has — the step door is built from the tenant's spec.
        HttpResponse<String> authored = post(tenant.fhir() + "/Task", records(), """
                {"resourceType":"Task","intent":"order","status":"requested",
                 "identifier":[{"system":"urn:dbo:run","value":"through-the-face"}],
                 "code":{"coding":[
                   {"system":"urn:dbo:process","code":"%s"},
                   {"system":"urn:dbo:step","code":"%s"}]}}"""
                .formatted(PROCESS, STEP));
        assertTrue(authored.statusCode() / 100 == 2,
                "the face would not take a run of the step this participant introduced, so the "
                        + "question this test asks does not arise: " + authored.statusCode()
                        + " " + authored.body());

        // DOOR TWO: minted directly, as every other run in this harness is.
        runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/minted-directly",
                List.of(WorkModel.DOMAIN));

        List<String> offered = lane.poll(Set.of(STEP), 50).stream().map(Run::key).sorted().toList();

        assertTrue(offered.contains(PROCESS + "/" + STEP + "/minted-directly"),
                "a directly minted run was not offered, so this tenant's lane is not answering "
                        + "at all and the comparison says nothing: " + offered);
        assertTrue(offered.stream().anyMatch(key -> key.endsWith("through-the-face")),
                "the lane offered " + offered + ". A run the face authored is work the tenant "
                        + "asked for, of a step its catalogue admits — and if it never reaches "
                        + "the lane while a directly minted run of the SAME step does, that is "
                        + "why a bean bringing its own capability performs nothing (item 029)");
    }


    @Test
    @DisplayName("a claim the step does not admit is refused as settled, not reported as a "
            + "store that did not answer")
    @Proving(DboPromises.PROC_REFUSED_IS_NOT_UNANSWERED)
    void anInadmissibleClaimIsARefusal() throws Exception {
        Lane lane = HttpLane.to(laneUri, () -> work(), tenant.code(), "not-admitted-probe",
                // An organisation, where a participant performing the step it
                // brought is the baseline. The step never opened itself to
                // being varied, so this claim cannot be admitted — which is
                // correct, and the only question is how it arrives.
                new Executor("not-admitted-probe", "1", tenant.code(),
                        Scope.organisation(tenant.code())));

        Run waiting = runs.pipeline(PROCESS, STEP, PROCESS + "/" + STEP + "/not-admitted",
                List.of(WorkModel.DOMAIN));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.claim(waiting, Duration.ofMinutes(1)));

        assertFalse(refused instanceof StoreUnreachableException,
                "the store decided about this caller and said so, and a participant told the "
                        + "store did not answer asks again for ever: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("does not admit")
                        && refused.getMessage().contains(STEP),
                "a refusal names what it refuses and for whom: " + refused.getMessage());
    }

    private static HttpResponse<String> post(String url, String token, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Authoring work is the tenant's own act, on its own records credential. */
    private static String records() throws Exception {
        return credential("two-door-author", "system/*.read", "system/*.write");
    }

    /** Holding the lane is a different one, which is the split the store insists on. */
    private static String work() {
        try {
            return credential("two-door-probe", cloud.jengu.dbo.auth.Scopes.WORK);
        } catch (Exception refused) {
            throw new IllegalStateException("no participation credential", refused);
        }
    }

    private static String credential(String clientId, String... scopes) throws Exception {
        String secret = clientId + "-secret";
        tenant.authority().ensureClient(clientId, secret, List.of(scopes));
        String form = "grant_type=client_credentials&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String body = http.send(HttpRequest.newBuilder(URI.create(tenant.base() + "/oidc/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int at = body.indexOf("\"access_token\"");
        int from = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(from, body.indexOf('"', from));
    }
}
