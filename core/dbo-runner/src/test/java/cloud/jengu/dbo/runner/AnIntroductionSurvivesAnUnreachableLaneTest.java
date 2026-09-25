package cloud.jengu.dbo.runner;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the runner does with a brought declaration when the lane is not
 * answering yet, and what a service nobody offers work for costs the one
 * beside it.
 *
 * <p>Both questions come from the same place. A worker application registered
 * a second step service, one the tenant had never declared, and stopped
 * performing the step it already performed — with the credential refused for
 * the first ninety seconds while the tenant finished coming up, and nothing in
 * any log after that. Two things could do it, and they are here rather than in
 * a six-minute end-to-end run because this is where they can be asked in
 * milliseconds.
 *
 * <p><b>The runner's only chance to introduce is inside a declaration</b>, and
 * a declaration runs on attach and at the END of a cycle. A cycle that fails
 * where it polls never reaches the end — so an introduction lost while the
 * store was unreachable is made again only if a later healthy cycle makes it.
 */
class AnIntroductionSurvivesAnUnreachableLaneTest {

    private static final String BROUGHT_ID = "a.process.brought";

    private static final StepDeclaration BROUGHT =
            StepDeclaration.of(BROUGHT_ID, "1", WorkModel.DOMAIN);

    @Test
    @DisplayName("an introduction refused while the store was unreachable is made again on the "
            + "first cycle that completes, rather than being lost with the attach that tried it")
    @Proving(DboPromises.PROC_STEPS_ARRIVE_BY_INTRODUCTION)
    void anIntroductionIsMadeAgainAfterTheStoreComesBack() {
        RecordingLogs.clear();
        ProvingLane offered = ProvingLane.offering(BROUGHT_ID)
                .with("thing", "Basic", "{\"resourceType\":\"Basic\"}").lane();

        // The tenant is still coming up: the verbs are there and the
        // credential is refused, which is what a worker attaching too early
        // actually meets.
        AtomicBoolean unreachable = new AtomicBoolean(true);
        List<String> introduced = new ArrayList<>();
        Lane comingUp = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "introduce" -> {
                            if (unreachable.get()) {
                                throw new IllegalStateException("a-tenant: introduce refused "
                                        + "(404) — this worker's credential");
                            }
                            introduced.add(((StepDeclaration) arguments[0]).id().toString());
                            return null;
                        }
                        case "declare" -> {
                            // Accepted, because what is under test is the
                            // introduction beside it and not the candidacy.
                            if (unreachable.get()) {
                                throw new IllegalStateException("a-tenant: declare refused (404)");
                            }
                            return null;
                        }
                        case "poll" -> {
                            if (unreachable.get()) {
                                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                                        "a-tenant: poll did not complete (503)");
                            }
                            return method.invoke(offered, arguments);
                        }
                        default -> {
                            return method.invoke(offered, arguments);
                        }
                    }
                });

        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .register(bringingItsOwn())
                .attach(comingUp);

        // Attached and introduced nothing, which is the starting position and
        // not the defect: the store said no.
        assertEquals(List.of(), introduced,
                "the lane was refusing and recorded an introduction anyway, so this test is not "
                        + "in the state it means to be in");
        assertEquals(0, runner.cycle(), "a cycle that could not poll reported work performed");
        assertTrue(String.join("\n", RecordingLogs.events()).contains("declaration failed"),
                "the declaration was refused and the runner said nothing, which is the shape of "
                        + "a worker that looks healthy and has introduced nothing: "
                        + RecordingLogs.events());

        // And the store comes back. THE CLAIM: the declaration is made again,
        // without the runner being re-attached or the service re-registered —
        // because a participant that had to be restarted to say what it brings
        // is one whose capability depends on how the tenant's morning went.
        unreachable.set(false);
        assertEquals(1, runner.cycle(), "the run was never taken after the store came back");
        assertEquals(List.of(BROUGHT_ID), introduced,
                "the store came back and the declaration was never made again, so the step the "
                        + "catalogue learns depends on whether the tenant was up at the instant "
                        + "this worker attached");
        runner.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a service the lane never offers work for costs the service beside it nothing, "
            + "so bringing one capability does not stop performing another")
    @Proving(DboPromises.PROC_STEP_SERVICE_EMBEDDABLE)
    void aServiceNobodyOffersWorkForCostsTheOtherNothing() {
        RecordingLogs.clear();
        ProvingLane offered = ProvingLane.offering("a.process.performed")
                .with("thing", "Basic", "{\"resourceType\":\"Basic\"}").lane();

        List<String> polledFor = new ArrayList<>();
        Lane watching = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    if ("poll".equals(method.getName())) {
                        polledFor.addAll(new java.util.TreeSet<>(
                                (java.util.Set<String>) arguments[0]));
                    }
                    if ("declare".equals(method.getName())) {
                        return null;
                    }
                    return method.invoke(offered, arguments);
                });

        StepRunner runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(1))
                .register(performing("a.process.performed"))
                .register(bringingItsOwn())
                .attach(watching);

        assertEquals(1, runner.cycle(),
                "the offered run was not performed, so registering a service the tenant has no "
                        + "work for stopped the one it does — which is a worker that goes quiet "
                        + "the moment it offers something new");
        assertTrue(polledFor.contains("brought") && polledFor.contains("performed"),
                "the poll named something other than both registered steps, so what a runner "
                        + "asks for is not what it registered: " + polledFor);
        runner.close();
    }

    /** A service that brings its own declaration, which is the participant's shape. */
    private static StepService bringingItsOwn() {
        return new StepService() {
            @Override
            public String step() {
                return BROUGHT_ID;
            }

            @Override
            public Optional<StepDeclaration> declaration() {
                return Optional.of(BROUGHT);
            }

            @Override
            public Outcome perform(Work work) {
                return Outcome.done(Map.of("did", 1L));
            }
        };
    }

    /** And one that fills a vacancy, which is the other. */
    private static StepService performing(String step) {
        return new StepService() {
            @Override
            public String step() {
                return step;
            }

            @Override
            public Outcome perform(Work work) {
                return Outcome.done(Map.of("did", 1L));
            }
        };
    }
}
