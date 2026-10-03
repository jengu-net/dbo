package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The clinic asking for a result to be reviewed.
 *
 * <p>Asked the same way whatever the result says. The step decides who may
 * take it: a result its condition admits is reviewed by automation, and any
 * other waits on the people's list for a nurse — decided by the clinic as the
 * task is authored, from the result itself, and never by this application.
 */
// --8<-- [start:asking]
@Component
public final class AskingForAReview {

    /** The step a clinic reviewing its results this way declares. */
    public static final String STEP = "care.results.review";

    private final DboInitiator initiator;

    AskingForAReview(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * @param tenant the clinic
     * @param result the result to review, as {@code Observation/<id>}
     */
    public DboInitiator.Started review(String tenant, String result) {
        return initiator.starting(tenant, STEP,
                Map.of("result", DboInitiator.Slot.reference(result)));
    }
}
// --8<-- [end:asking]
