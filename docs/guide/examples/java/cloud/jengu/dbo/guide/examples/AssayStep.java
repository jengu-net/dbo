package cloud.jengu.dbo.guide.examples;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One step, performed. This is the whole of what an integrator writes. */
public final class AssayStep implements StepService {

    @Override
    public String step() {
        return "hogwarts.admission.admit";
    }

    @Override
    public Outcome perform(Work work) {
        // The work arrives whole: the run, and the objects it named. There is
        // nothing to fetch and nowhere to fetch it from.
        byte[] patient = work.inputs().get("patient").payload();
        String admitted = new String(patient, StandardCharsets.UTF_8);

        // Long work says how far it has got. The counts extend the claim —
        // they are evidence of progress, not a heartbeat.
        work.progress().milestone("measured", Map.of("read", 1L));

        if (admitted.isBlank()) {
            // Returning failed and throwing are the same thing: the run is
            // released with the reason, and a later cycle may take it again.
            return Outcome.failed("the patient arrived empty");
        }
        // Done means done. The store closes the run on this and has no view
        // below it, so a service with durable execution underneath waits for
        // its workflow rather than returning its handle.
        return Outcome.done(Map.of("admitted", 1L));
    }
}
