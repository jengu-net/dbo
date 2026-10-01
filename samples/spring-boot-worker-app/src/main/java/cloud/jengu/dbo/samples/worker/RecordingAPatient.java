package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Somebody the clinic is to hold a record of, written down.
 *
 * <p>Given the person, because the clinic does not hold them yet and there is
 * nothing for a reference to point at; and it answers with them as a record
 * the clinic writes. The clinic decides whether it can: it validates the
 * record, holds its identity rules, and refuses a second record for somebody
 * it already holds by the number they carry — which is where "two people
 * with one name are two people" is decided, and not here.
 *
 * <p>The step code is the clinic's process rather than one tenant's, so every
 * tenant that declares it is offered this bean's work.
 */
@Component
public final class RecordingAPatient implements StepService {

    /** Declared by each clinic that keeps its patients' records this way. */
    public static final String STEP = "care.records.register";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        String patient = new String(work.input("patient").payload(), StandardCharsets.UTF_8);
        if (patient.isBlank()) {
            return Outcome.failed("the patient arrived empty");
        }
        return Outcome.done(Map.of("recorded", 1L)).writing(Outcome.Write.create(patient));
    }
}
