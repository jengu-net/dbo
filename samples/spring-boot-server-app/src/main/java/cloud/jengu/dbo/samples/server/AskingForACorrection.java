package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.samples.worker.HearingBack;
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
 *
 * <p>The corrected record is read back through the run as the audience the
 * clinic declared for the step. Where that audience may see a person whole,
 * the clinic states why it reads her — the purpose the step declared — and
 * without it is shown her as anybody is: without what identifies her.
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

    /**
     * The record as the correction wrote it, collected through its run.
     *
     * @param asked   the run {@link #correct} started
     * @param settled its answer, once it completed
     * @param purpose why the clinic reads the person — the purpose the step
     *                declared, to be shown her whole — or null
     */
    public DboInitiator.Collected corrected(String tenant, DboInitiator.Started asked,
            DboInitiator.Answer settled, String purpose) {
        String written = HearingBack.produced(settled).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("nothing was corrected: "
                        + settled.body()));
        return initiator.collecting(tenant, asked.runOrFail(), written, purpose);
    }
}
// --8<-- [end:asking]
