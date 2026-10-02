package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The clinic asking for one of its records to be corrected.
 *
 * <p>Two things go with the run: the record, named by reference, which the
 * clinic hands to the step as it holds it; and the record as it should read,
 * given. A correction says which version it was decided on — in its
 * {@code meta.versionId}, as the record read when somebody decided — and is
 * refused if the record has moved on since, so one person's correction never
 * silently replaces another's.
 */
// --8<-- [start:asking]
@Component
public final class AskingForACorrection {

    /** The step each clinic correcting its records this way declares. */
    public static final String STEP = "care.records.correct";

    private final DboInitiator initiator;

    AskingForACorrection(DboInitiator initiator) {
        this.initiator = initiator;
    }

    /**
     * @param tenant    the clinic
     * @param record    the record to correct, as {@code Type/id}
     * @param corrected the record as it should read
     */
    public DboInitiator.Started correct(String tenant, String record, String corrected) {
        return initiator.starting(tenant, STEP, Map.of(
                "record", DboInitiator.Slot.reference(record),
                "corrected", DboInitiator.Slot.object(corrected)));
    }
}
// --8<-- [end:asking]
