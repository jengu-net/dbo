package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Introductions;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run is offered whichever door declared its step.
 *
 * <p>Written to isolate one difference, in seconds. A worker performs the step
 * its tenant declared and never performs the step it introduced itself, in the
 * same process, over the same lane, with the same credential — and the
 * end-to-end run that shows it costs five minutes to answer yes or no.
 *
 * <p><b>The one input that differs is the declaration a run was minted
 * from.</b> A tenant's own step reaches the store through a surface that
 * declares it in the FACE's domain — {@code StepDeclaration.of(code, "1",
 * "r5")}, hardcoded — while a step a participant brings carries whatever
 * domain it declared, which for the sample is work. Both are then minted by
 * the same call. So two runs are made here that differ in that and nothing
 * else, and the lane is asked what it offers.
 *
 * <p>Everything else the end-to-end run could have blamed is already
 * eliminated: the run is created and correctly keyed, the service is wired,
 * the credential narrows to everything, and the lane reads the feed runs are
 * written to.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARunIsOfferedWhicheverDoorDeclaredItsStepIT {

    /** As a tenant's own surface declares one: the face's domain, hardcoded. */
    private static final StepDeclaration AS_A_TENANT_DECLARES =
            StepDeclaration.of("lab.result.validate", "1", "r5");

    /** As a participant brings one: the domain the declaration itself names. */
    private static final StepDeclaration AS_A_PARTICIPANT_BRINGS =
            StepDeclaration.of("ee-lab.result.sign", "1", WorkModel.DOMAIN);

    static PGSimpleDataSource ds;
    static Runs runs;
    static Declarations declarations;
    static Introductions introductions;
    static PgObjectStore store;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ARunIsOfferedWhicheverDoorDeclaredItsStepIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, new java.util.ArrayList<>(WorkModel.registrations()));
        introductions = new Introductions(store, Steps.of(AS_A_TENANT_DECLARES));
        introductions.introduce(AS_A_PARTICIPANT_BRINGS, "a-participant");
        runs = new Runs(store, introductions.composedWith());
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a run of an introduced step is offered on the lane exactly as a run of an "
            + "installed one is, so the declaration's own domain decides nothing here")
    void bothRunsAreOffered() {
        Run declared = runs.of(AS_A_TENANT_DECLARES, RunKind.PIPELINE, "declared-one");
        Run brought = runs.of(AS_A_PARTICIPANT_BRINGS, RunKind.PIPELINE, "brought-one");

        // What the store holds, before asking what the lane offers: if these
        // disagree the fault is in minting rather than in polling.
        assertTrue(declared.open() && brought.open(),
                "a freshly minted run is open, and one of these is not: declared="
                        + declared.holder() + " brought=" + brought.holder());
        assertTrue(!declared.claimed(java.time.Instant.now())
                        && !brought.claimed(java.time.Instant.now()),
                "a freshly minted run is unclaimed, and one of these is not");

        List<Run> offered = lane("a-runner").poll(
                Set.of(bare(AS_A_TENANT_DECLARES), bare(AS_A_PARTICIPANT_BRINGS)), 50);
        List<String> steps = offered.stream().map(Run::step).sorted().toList();

        assertEquals(List.of(bare(AS_A_TENANT_DECLARES), bare(AS_A_PARTICIPANT_BRINGS))
                        .stream().sorted().toList(), steps,
                "the lane offered " + steps + ". A run of a step a participant introduced is "
                        + "work its tenant authored and its catalogue admits, so a lane that "
                        + "offers one and not the other is why a bean that brings its own "
                        + "capability performs nothing — see item 029");
    }

    /** The word a run records, which is the step alone and not its whole id. */
    private static String bare(StepDeclaration step) {
        String id = step.id().toString();
        return id.substring(id.lastIndexOf('.') + 1);
    }

    private Lane lane(String name) {
        return Lane.inProcess("t-offered", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, name,
                new Executor(name, "1", "ee.lab", Scope.BASELINE), store, introductions);
    }
}
