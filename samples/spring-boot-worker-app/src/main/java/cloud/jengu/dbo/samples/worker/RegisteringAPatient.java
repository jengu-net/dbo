package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody arrives, and the hospital comes to hold them and the stay they
 * arrived for.
 *
 * <p>The step is GIVEN the person — {@code hogwarts.json} declares its slot as
 * {@code Patient}, not {@code Reference(Patient)} — because a person arriving
 * is somebody the hospital does not hold yet, and there is nothing for a
 * reference to point at. And it answers with two records: the person, and the
 * encounter that says they are here.
 *
 * <p><b>This application writes neither.</b> It holds no records credential
 * and is not given one. It says what should be written, and the hospital
 * writes it under the run — validating it, holding its identity rules, sealing
 * what identifies the person — or refuses it, saying why. The declaration says
 * which types this step may ask for ({@code "writes"}); anything else is
 * refused before a record is read.
 */
// --8<-- [start:step]
@Component
public final class RegisteringAPatient implements StepService {

    @Override
    public String step() {
        return "hogwarts.admission.register";
    }

    @Override
    public Outcome perform(Work work) {
        String patient = new String(work.input("patient").payload(), StandardCharsets.UTF_8);
        if (patient.isBlank()) {
            return Outcome.failed("the patient arrived empty");
        }

        // The encounter names the person by the urn the person is written
        // under, because neither has an id yet. The two are one result, so the
        // hospital writes both or neither: an encounter about nobody is never
        // left behind by a person it refused.
        //
        // And it is identified by the run that admitted it, which is the
        // honest name for a stay that began because somebody asked for it.
        String person = "urn:uuid:" + UUID.randomUUID();
        String stay = """
                {"resourceType":"Encounter","status":"in-progress",
                 "identifier":[{"system":"urn:dbo:run","value":"%s"}],
                 "subject":{"reference":"%s"}}""".formatted(work.run().key(), person);

        return Outcome.done(Map.of("registered", 1L))
                .writing(Outcome.Write.create(person, patient),
                        Outcome.Write.create(stay));
    }
}
// --8<-- [end:step]
