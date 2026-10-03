package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.samples.worker.HearingBack;
import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The clinic asking for somebody to be recorded as its patient.
 *
 * <p>The clinic's application does not write the record. It asks for the
 * step that does, giving the person as it has them — a number and a name, and
 * no id, because no record exists yet — and the clinic writes what the step
 * answers with, under the run, or refuses it and says why. What came of it is
 * heard back on the run, and who was written is read back through it: as the
 * clinic's desk is shown somebody, which is what the clinic declared the
 * step's asker sees, for as long as the step said it may collect.
 */
// --8<-- [start:asking]
@Component
public final class AskingForARegistration {

    /** The clinic's step, which every tenant keeping its patients this way declares. */
    public static final String STEP = "care.records.register";

    private final DboInitiator initiator;

    AskingForARegistration(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * @param tenant  the clinic
     * @param patient the person, as the {@code Patient} this application has
     */
    public DboInitiator.Started register(String tenant, String patient) {
        return initiator.starting(tenant, STEP,
                Map.of("patient", DboInitiator.Slot.object(patient)));
    }

    /**
     * Who was registered, collected through the run that registered them: the
     * version the clinic wrote, as the desk is shown it.
     *
     * @param asked   the run {@link #register} started
     * @param settled its answer, once it completed
     */
    public DboInitiator.Collected registered(String tenant, DboInitiator.Started asked,
            DboInitiator.Answer settled) {
        String written = HearingBack.produced(settled).stream()
                .filter(version -> version.startsWith("Patient/")).findFirst()
                .orElseThrow(() -> new IllegalStateException("nobody was registered: "
                        + settled.body()));
        return initiator.collect(tenant, asked.runOrFail(), written);
    }
}
// --8<-- [end:asking]
