package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The clinic asking for a visit to be recorded, whole.
 *
 * <p>A visit is what was measured, in the order it was measured, and it is
 * recorded as one or not at all. The observations are given — they are not
 * records yet — and may name each other by the {@code id} they are given
 * under, and their patient by the number the clinic knows them by. The step
 * answers with all of them, and the clinic commits all of them or none.
 */
@Component
public final class AskingForAVisit {

    /** The step a clinic recording its visits this way declares. */
    public static final String STEP = "care.visit.record";

    private final DboInitiator initiator;

    AskingForAVisit(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * @param tenant       the clinic
     * @param observations each {@code Observation}, as JSON, in the order taken
     */
    public DboInitiator.Started record(String tenant, List<String> observations) {
        return initiator.starting(tenant, STEP,
                Map.of("observations", DboInitiator.Slot.objects(observations)));
    }
}
