package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * A step the tenant never declared, brought by the bean that performs it.
 *
 * <p>{@link AdmittingAPatient} fills a vacancy: {@code hogwarts} declares the
 * step in its own spec and this application arrives able to perform it. This
 * one is the other direction — the tenant's catalogue has never heard of
 * {@code hogwarts.admission.assay}, and learns it because a participant
 * showed up with it.
 *
 * <p><b>The difference is the one extra method.</b> Everything else is the
 * same bean and the same interface; {@link #declaration()} is what turns
 * joining from filling a vacancy into bringing a capability. The runner
 * introduces it beside this service's candidacy, so the catalogue learns the
 * step at the moment presence can be derived rather than at some later sweep.
 *
 * <p><b>Bringing it grants nothing</b>, and that is not a footnote. The
 * tenant's own step door is built from its spec and stays so: it refuses this
 * step by name, however long this application has been introducing it. A run
 * of a brought step is authored by the TENANT, through the face's own door, as
 * a Task naming the process, the step and a reference per slot — and the face
 * takes it because the catalogue it checks a run against now holds this
 * declaration. That is the whole of what introduction buys.
 *
 * <p>So the slots below are the only things that arrive, and what this
 * participant may claim stays the intersection of its own credential and what
 * the step admits. A step cannot hand its executor more than the executor
 * already holds.
 */
@Component
public final class MeasuringASpecimen implements StepService {

    /**
     * What this application can do, said before it has done anything.
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
        // The slot the declaration named, and nothing else. There is no verb
        // here that takes a reference, so a step cannot reach past what the
        // run filled for it — which is what makes a brought step safe to
        // accept from somebody who is not the tenant.
        byte[] specimen = work.inputs().get("specimen").payload();
        if (new String(specimen, StandardCharsets.UTF_8).isBlank()) {
            return Outcome.failed("the specimen arrived empty");
        }

        work.progress().milestone("measured", Map.of("read", 1L));
        return Outcome.done(Map.of("assayed", 1L));
    }
}
