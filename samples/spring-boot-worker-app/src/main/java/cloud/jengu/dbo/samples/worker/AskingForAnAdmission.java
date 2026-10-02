package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Asking the hospital to admit somebody it already holds.
 *
 * <p>The participant as an <b>initiator</b>, asking for the step it also
 * performs. Nothing here decides who admits them: the run is the hospital's,
 * authored on its own door, and whichever entitled performer takes it does —
 * in this application that is {@link AdmittingAPatient}, but this class does
 * not know that and does not need to.
 *
 * <p>The patient is named by reference, because the hospital already holds
 * them: the run records which record it was over, and the step is handed that
 * record without this application ever having read it.
 *
 * <p><b>And it hears back.</b> The run answers the application that asked for
 * it, on the credential it asked with, and nobody else — so what the admission
 * came to is learnt without a credential onto the hospital's records.
 */
// --8<-- [start:asking]
@Component
public final class AskingForAnAdmission {

    /** The step the hospital declares in its own spec. */
    public static final String STEP = "hogwarts.admission.admit";

    /** And the one that registers somebody arriving, performed by {@link RegisteringAPatient}. */
    public static final String REGISTERING = "hogwarts.admission.register";

    private final DboInitiator initiator;

    AskingForAnAdmission(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * Asks a tenant to admit a patient it holds.
     *
     * @param tenant  the hospital, which must be one this application holds an
     *                HTTP lane into
     * @param patient the patient as {@code Patient/<id>}
     */
    public DboInitiator.Started admit(String tenant, String patient) {
        return initiator.start(tenant, STEP, Map.of("patient", patient));
    }

    /**
     * Asks a tenant to register somebody it does not hold yet, and the stay
     * they arrived for.
     *
     * <p>Given rather than referred: the person is a number and a name this
     * application has, and there is no record for a reference to point at.
     * The step answers with the person and the stay, and the hospital writes
     * both or neither — or refuses, if it already holds somebody by that
     * number, and the run ends with its reason.
     *
     * @param patient the person, as the {@code Patient} this application has
     */
    public DboInitiator.Started register(String tenant, String patient) {
        return initiator.starting(tenant, REGISTERING,
                Map.of("patient", DboInitiator.Slot.object(patient)));
    }
}
// --8<-- [end:asking]
