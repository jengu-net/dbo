package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * This application asking for work it does not perform.
 *
 * <p>Every other class in this sample is a participant as a <b>performer</b>:
 * a bean, an interface, and work arriving over a lane. This is the same
 * participant as an <b>initiator</b> — same application, same credential, same
 * enrolment — asking the hospital to start a run.
 *
 * <p>And the step it asks for is one this application cannot perform.
 * {@code fleet.directory.check} is declared by the deployment in
 * {@code mom.json}, performed by a bean inside the serving application, and
 * answered for every tenant at once. Nothing here knows any of that: it posts
 * a step code to a tenant's own door and the store decides who takes it.
 * Which is the point — an external participant is a peer that can say what
 * should happen, not only a pair of hands for what somebody else decided.
 *
 * <p><b>Why the tenant's door and not the deployment's.</b> The run is about
 * the hospital's data, so it belongs to the hospital: it is authored on the
 * hospital's surface, carried on the hospital's work stream, reported back
 * through the hospital's own lane, and refused by the hospital's own rules if
 * it breaks one. A door of the deployment's would have produced a run with no
 * owner, and there is no orchestrator here for it to belong to instead.
 */
@Component
public final class AskingForADirectoryCheck {

    /** The step the DEPLOYMENT declares. This application performs none of it. */
    public static final String STEP = "fleet.directory.check";

    private final DboInitiator initiator;

    AskingForADirectoryCheck(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * Asks a tenant to have one of its organisations checked.
     *
     * @param tenant         whose directory it is
     * @param organisationId the Organization this run is about, which the step
     *                       declared as its one slot
     */
    public DboInitiator.Started about(String tenant, String organisationId) {
        return initiator.start(tenant, STEP, Map.of("org", "Organization/" + organisationId));
    }
}
