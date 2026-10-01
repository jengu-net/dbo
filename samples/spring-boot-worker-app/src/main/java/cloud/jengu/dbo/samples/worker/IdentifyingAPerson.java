package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Who somebody is, as distinct from the record of them as a patient.
 *
 * <p>Given the person, carrying the number they are known by and the records
 * that are theirs, and answered with them as a record the hospital writes.
 * Whether that is one human or a second claim on somebody already held is the
 * hospital's to decide against its own identity rules: a person claiming a
 * number another person holds, or a record somebody else is already linked
 * to, is refused there, and the run ends with the reason.
 */
@Component
public final class IdentifyingAPerson implements StepService {

    /** Declared by a tenant that holds the people behind its records. */
    public static final String STEP = "care.records.identify";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        String person = new String(work.input("person").payload(), StandardCharsets.UTF_8);
        if (person.isBlank()) {
            return Outcome.failed("the person arrived empty");
        }
        return Outcome.done(Map.of("identified", 1L)).writing(Outcome.Write.create(person));
    }
}
