package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writing down that the clinic heard a worker, or stopped hearing it.
 *
 * <p>The store keeps no account of contact: a node hears its workers in
 * memory and tells whoever listens. A clinic that wants a record of it asks
 * for this step, giving the note it wants kept, and the note is written as
 * any result is — validated by the tenant and carrying the run that wrote it.
 */
// --8<-- [start:step]
@Component
public final class RecordingContact implements StepService {

    /** Declared by a clinic that keeps a record of its workers' comings and goings. */
    public static final String STEP = "care.contact.record";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        StoredObject note = work.input("note");
        return Outcome.done(Map.of("noted", 1L)).writing(Outcome.Write.create(
                new String(note.payload(), StandardCharsets.UTF_8)));
    }
}
// --8<-- [end:step]
