package cloud.jengu.dbo.work;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which executor runs a step, and who is allowed to decide (#72, ADR 0059).
 *
 * <p>No store and no container: resolution is a decision over declarations, and
 * the whole point of deciding the rule rather than discovering it is that the
 * same inputs give the same answer every time.
 */
class ExecutorResolutionTest {

    private static final String PROCESS = "dbo.claims.adjudication";
    private static final String STEP = "adjudicate";

    private static final Scope EE = Scope.zone("ee");
    private static final Scope HOGWARTS = Scope.organisation("hogwarts");
    private static final List<Scope> CHAIN = List.of(Scope.BASELINE, EE, HOGWARTS);

    private record Candidate(Executor executor, boolean willing) implements ExecutorCandidate {
        @Override
        public boolean willTake(Work work) {
            return willing;
        }
    }

    private static ExecutorCandidate at(Scope scope, String name, boolean willing) {
        return new Candidate(new Executor(name, "1.0", "cloud.jengu.dbo", scope), willing);
    }

    private static Work work() {
        return Work.of(PROCESS, STEP, "Claim/1");
    }

    private static ExecutorResolution over(ExecutorCandidate... candidates) {
        return new ExecutorResolution(() -> List.of(candidates));
    }

    @Test
    @DisplayName("the most local willing and permitted candidate runs")
    @Proving(DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC)
    void theMostLocalPermittedCandidateRuns() {
        Resolution resolution = over(
                at(Scope.BASELINE, "national", true),
                at(EE, "zone", true),
                at(HOGWARTS, "local", true))
                .resolve(StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ORGANISATION),
                        CHAIN, List.of(), work());

        assertEquals("local", resolution.executor().name());
        assertEquals(HOGWARTS, resolution.executor().scope());
    }

    @Test
    @DisplayName("a step that is not overridable is not shadowed by a narrower scope, "
            + "and the refusal names who tried")
    @Proving(DboPromises.PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE)
    void aNarrowerScopeCannotShadowAStepThatForbidsIt() {
        Resolution resolution = over(
                at(HOGWARTS, "local", true),
                at(Scope.BASELINE, "national", true))
                .resolve(StepGrant.of(PROCESS, STEP), CHAIN, List.of(), work());

        assertEquals("national", resolution.executor().name(),
                "the step's own rule runs — being narrow is not a way to acquire authority");
        assertTrue(resolution.refusedOverride().orElse("").contains(HOGWARTS.wire()),
                "and that somebody tried is recorded: " + resolution);
    }

    @Test
    @DisplayName("a grant naming a local class admits the wider ones too")
    @Proving(DboPromises.PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE)
    void aGrantAdmitsWiderScopesThanTheOneItNames() {
        StepGrant openToOrganisations =
                StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ORGANISATION);
        assertEquals("zone", over(at(EE, "zone", true))
                        .resolve(openToOrganisations, CHAIN, List.of(), work()).executor().name(),
                "a step that lets an organisation vary it has already accepted that a zone may");

        StepGrant openToZones = StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ZONE);
        assertFalse(over(at(HOGWARTS, "local", true))
                        .resolve(openToZones, CHAIN, List.of(), work()).automated(),
                "and one that lets a zone vary it has not accepted that every organisation may");
    }

    @Test
    @DisplayName("an unwilling candidate is passed over, and the next in order runs")
    @Proving(DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC)
    void anUnwillingCandidateIsPassedOver() {
        Resolution resolution = over(
                at(HOGWARTS, "local", false),
                at(EE, "zone", true))
                .resolve(StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ORGANISATION),
                        CHAIN, List.of(), work());

        assertEquals("zone", resolution.executor().name());
    }

    @Test
    @DisplayName("nothing races: one candidate is asked at a time, and only until one takes it")
    @Proving(DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC)
    void candidatesAreTriedOneAtATime() {
        AtomicInteger asked = new AtomicInteger();
        List<String> order = new ArrayList<>();
        ExecutorCandidate local = new ExecutorCandidate() {
            @Override
            public Executor executor() {
                return new Executor("local", "1.0", "p", HOGWARTS);
            }

            @Override
            public boolean willTake(Work work) {
                asked.incrementAndGet();
                order.add("local");
                return true;
            }
        };
        ExecutorCandidate national = new ExecutorCandidate() {
            @Override
            public Executor executor() {
                return new Executor("national", "1.0", "p", Scope.BASELINE);
            }

            @Override
            public boolean willTake(Work work) {
                asked.incrementAndGet();
                order.add("national");
                return true;
            }
        };
        new ExecutorResolution(() -> List.of(national, local))
                .resolve(StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ORGANISATION),
                        CHAIN, List.of(), work());

        assertEquals(List.of("local"), order,
                "the wider candidate was asked as well, so both could have run over one item");
        assertEquals(1, asked.get());
    }

    @Test
    @DisplayName("automation switched off in a zone falls through, and the reason names the zone")
    @Proving(DboPromises.PROC_FALL_THROUGH_IS_COUNTABLE)
    void automationSwitchedOffFallsThrough() {
        Resolution resolution = over(at(Scope.BASELINE, "national", true))
                .resolve(StepGrant.of(PROCESS, STEP), CHAIN,
                        List.of(Automation.off(PROCESS, STEP, EE)), work());

        assertFalse(resolution.automated());
        assertTrue(resolution.reason().contains(EE.wire()),
                "a zone switching automation off is a decision somebody made: " + resolution);
    }

    @Test
    @DisplayName("the most local switch wins, so an organisation can turn back on "
            + "what its zone turned off")
    @Proving(DboPromises.PROC_AUTOMATION_IS_DECLARED)
    void theMostLocalSwitchWins() {
        Resolution resolution = over(at(Scope.BASELINE, "national", true))
                .resolve(StepGrant.of(PROCESS, STEP), CHAIN,
                        List.of(Automation.off(PROCESS, STEP, EE),
                                Automation.on(PROCESS, STEP, HOGWARTS)), work());

        assertTrue(resolution.automated(), "the organisation's declaration is the local one");
    }

    @Test
    @DisplayName("a withdrawn provider stops being selected, because candidates are asked for "
            + "rather than held")
    @Proving(DboPromises.PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC)
    void aWithdrawnProviderStopsBeingSelected() {
        List<ExecutorCandidate> installed = new ArrayList<>();
        installed.add(at(Scope.BASELINE, "national", true));
        ExecutorResolution resolution = new ExecutorResolution(() -> List.copyOf(installed));

        assertTrue(resolution.resolve(StepGrant.of(PROCESS, STEP), CHAIN, List.of(), work())
                .automated());

        installed.clear();
        Resolution after = resolution.resolve(StepGrant.of(PROCESS, STEP), CHAIN, List.of(), work());
        assertFalse(after.automated(),
                "a held list would keep selecting an executor that is no longer installed");
        assertTrue(after.reason().contains("no executor"), after.reason());
    }

    @Test
    @DisplayName("a candidate declared off this chain is not a candidate here")
    @Proving(DboPromises.PROC_AUTOMATION_IS_DECLARED)
    void aCandidateFromAnotherChainIsNotConsidered() {
        Resolution resolution = over(at(Scope.zone("lv"), "elsewhere", true))
                .resolve(StepGrant.of(PROCESS, STEP).overridableBy(ScopeClass.ORGANISATION),
                        CHAIN, List.of(), work());

        assertFalse(resolution.automated(), "another zone's executor is not this zone's");
    }
}
