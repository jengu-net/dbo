package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a participant may claim is the intersection of what its credential
 * covers and what the step admits.
 *
 * <p>Two halves, enforced in two places for the same reason each is there. The
 * <b>step's</b> half lives at the primitive, where the declaration is: a step
 * that never opened itself to local execution refuses an executor at a local
 * scope, and a step cannot grant its executor more than the executor already
 * holds. The <b>credential's</b> half lives at the lane, because the
 * lane is the only door a participant has and only the host knows what the
 * credential covers.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimIsTheIntersectionIT {

    /** Not overridable: only the baseline runs it. */
    private static final StepDeclaration NATIONAL =
            StepDeclaration.of("lab.result.sign", "1.0", WorkModel.DOMAIN);

    /** Opened to organisations, deliberately. */
    private static final StepDeclaration LOCAL =
            StepDeclaration.of("lab.result.validate", "1.0", WorkModel.DOMAIN)
                    .overridableBy("organisation");

    private static final Executor BASELINE =
            new Executor("national", "1.0", "cloud.jengu.test", Scope.BASELINE);
    private static final Executor CLINIC =
            new Executor("clinic", "1.0", "cloud.jengu.test", Scope.organisation("hogwarts"));

    static PGSimpleDataSource ds;
    static PgObjectStore store;
    static Runs runs;
    static Declarations declarations;

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("ClaimIsTheIntersectionIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        store = new PgObjectStore(ds, new java.util.ArrayList<TypeRegistration>(
                WorkModel.registrations()));
        runs = new Runs(store, Steps.of(NATIONAL, LOCAL));
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
    }

    /**
     * A lane with its OWN feed consumer name, because {@code poll} acks the
     * cursor it read: two tests sharing a participant name would consume each
     * other's work and pass or fail on execution order.
     */
    private Lane lane(Executor identity, Lane.Entitlement entitlement, String participant) {
        return Lane.inProcess("t-claims", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, participant, identity, store, null, entitlement);
    }

    @Test
    @DisplayName("the step's half: a step that never opened itself to local execution "
            + "refuses a local executor, by name")
    @Proving(DboPromises.PROC_CLAIM_IS_THE_INTERSECTION)
    void theStepsHalf() {
        Run national = runs.of(NATIONAL, RunKind.PIPELINE, "step-admits");

        Runs.NotAdmitted refused = assertThrows(Runs.NotAdmitted.class,
                () -> runs.claim(national, CLINIC, Instant.now().plusSeconds(60)));
        assertTrue(refused.getMessage().contains("lab.result.sign")
                        && refused.getMessage().contains("organisation:hogwarts"),
                "the refusal names the step and the scope that was refused: "
                        + refused.getMessage());

        assertTrue(runs.claim(national, BASELINE, Instant.now().plusSeconds(60)).isPresent(),
                "the baseline always may — it is not an override, it is the rule");

        // and where the step DID open itself, the same local executor may
        Run local = runs.of(LOCAL, RunKind.PIPELINE, "step-admits-local");
        assertTrue(runs.claim(local, CLINIC, Instant.now().plusSeconds(60)).isPresent(),
                "a step open to organisations admits an organisation's executor");
    }

    @Test
    @DisplayName("the credential's half: an entitlement bounds what the lane offers and "
            + "refuses what it may take")
    @Proving({DboPromises.PROC_CLAIM_IS_THE_INTERSECTION,
            DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED})
    void theCredentialsHalf() {
        Run signable = runs.of(NATIONAL, RunKind.PIPELINE, "entitlement-sign");
        Run validatable = runs.of(LOCAL, RunKind.PIPELINE, "entitlement-validate");
        Lane bounded = lane(BASELINE, Lane.Entitlement.ofSteps("lab.result.validate"),
                "bounded-participant");

        // ONE poll, asked for both: what is held arrives and what is not does
        // not. Asserting only the absence would pass just as well if nothing
        // had been offered at all.
        List<Run> offered = bounded.poll(Set.of("sign", "validate"), 50);
        assertTrue(offered.stream().anyMatch(r -> r.key().equals(validatable.key())),
                "the entitled step's work is offered: "
                        + offered.stream().map(Run::step).toList());
        assertTrue(offered.stream().noneMatch(r -> r.key().equals(signable.key())),
                "and the unentitled step's is not — a participant sees only the work of "
                        + "the steps it holds: " + offered.stream().map(Run::step).toList());

        // and a claim outside the entitlement is refused rather than narrowed
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bounded.claim(signable, Duration.ofSeconds(60)));
        assertTrue(refused.getMessage().contains("lab.result.sign")
                        && refused.getMessage().contains("lab.result.validate"),
                "refused naming what was asked and what is held: " + refused.getMessage());
    }

    @Test
    @DisplayName("an entitlement is written in the catalogue's words and polled in the "
            + "run's — the bare-name trap does not silently empty it")
    @Proving(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED)
    void theBareNameTrapIsHandled() {
        Lane.Entitlement entitled = Lane.Entitlement.ofSteps("lab.result.validate");

        assertTrue(entitled.covers("lab.result.validate"), "the catalogue's full id");
        assertTrue(entitled.covers("validate"),
                "and the run's bare word — comparing the two literally would narrow every "
                        + "entitlement to nothing, and a lane offering no work looks exactly "
                        + "like a lane with no work");
        assertEquals(false, entitled.covers("sign"), "without covering what it does not");

        Run validatable = runs.of(LOCAL, RunKind.PIPELINE, "bare-name");
        Lane bounded = lane(BASELINE, entitled, "bare-name-participant");
        assertTrue(bounded.poll(Set.of("validate"), 50).stream()
                        .anyMatch(r -> r.key().equals(validatable.key())),
                "so the entitled work actually arrives");
    }

    @Test
    @DisplayName("everything() is a host saying it is the tenant, and it narrows nothing")
    @Proving(DboPromises.PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED)
    void everythingNarrowsNothing() {
        Run run = runs.of(LOCAL, RunKind.PIPELINE, "unbounded");
        Lane host = lane(BASELINE, Lane.Entitlement.everything(), "host-participant");

        assertTrue(host.poll(Set.of("validate"), 50).stream()
                        .anyMatch(r -> r.key().equals(run.key())));
        assertTrue(host.claim(run, Duration.ofSeconds(60)).isPresent(),
                "the host is the tenant: nothing is narrowed here");
    }
}
