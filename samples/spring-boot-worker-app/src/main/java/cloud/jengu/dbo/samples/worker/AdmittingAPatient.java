package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One step, performed. This is the whole of what this application writes.
 *
 * <p>A bean, and an interface. Nothing here constructs a runner, registers
 * itself, attaches a lane or names a tenant — adding the worker dependency is
 * what makes a bean implementing this interface a step the application
 * performs, and the container's own whiteboard is what finds it.
 *
 * <p>The step code is the one the tenant's own declaration names, which is why
 * it is a string rather than a constant this application invented: the
 * catalogue is the tenant's, and a worker performs what it was declared to
 * perform.
 */
@Component
public final class AdmittingAPatient implements StepService {

    @Override
    public String step() {
        return "hogwarts.admission.admit";
    }

    @Override
    public Outcome perform(Work work) {
        // The work arrives whole — the run, and the objects it named. There is
        // nothing to fetch and nowhere to fetch it from, which is what makes
        // "no store here" a design rather than a restriction.
        byte[] patient = work.inputs().get("patient").payload();
        String admitted = new String(patient, StandardCharsets.UTF_8);

        // Progress is evidence rather than a heartbeat: it says how far this
        // got, in counts somebody can act on.
        work.progress().milestone("identified", Map.of("read", 1L));

        if (admitted.isBlank()) {
            // Returning failed and throwing are the same thing: the run is
            // released with the reason, and a later cycle may take it again.
            return Outcome.failed("the patient arrived empty");
        }
        return Outcome.done(Map.of("admitted", 1L));
    }
}
