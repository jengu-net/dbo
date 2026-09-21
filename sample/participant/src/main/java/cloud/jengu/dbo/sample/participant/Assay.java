package cloud.jengu.dbo.sample.participant;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * A step the hospital never installed, performed by somebody who is not the
 * hospital.
 *
 * <p>The difference from a step service beside the store is the one method
 * below it: this one carries its own {@linkplain #declaration() declaration},
 * so joining is bringing a capability rather than filling a vacancy. The
 * declaration is the API this participant agrees to — the slots it will be
 * given and nothing else — and it binds the participant that brought it
 * exactly as it binds anybody.
 */
public final class Assay implements StepService {

    /**
     * What the laboratory can do, said before it has done anything.
     *
     * <p>The version is the declaration's own, and a run records it beside
     * the executor's: reproducing a decision needs the definition as well as
     * the process that ran it.
     */
    public static final StepDeclaration DECLARED =
            StepDeclaration.of("hogwarts.admission.assay", "1", "work")
                    .taking("specimen", "Observation");

    @Override
    public String step() {
        return DECLARED.id().toString();
    }

    @Override
    public Optional<StepDeclaration> declaration() {
        return Optional.of(DECLARED);
    }

    @Override
    public Outcome perform(Work work) {
        // The slot is the one the declaration named. Nothing else arrives,
        // and there is no verb here that takes a reference — so a step
        // cannot reach past what the run filled for it.
        byte[] specimen = work.inputs().get("specimen").payload();
        if (new String(specimen, StandardCharsets.UTF_8).isBlank()) {
            return Outcome.failed("the specimen arrived empty");
        }

        work.progress().milestone("measured", Map.of("read", 1L));
        return Outcome.done(Map.of("assayed", 1L));
    }
}
