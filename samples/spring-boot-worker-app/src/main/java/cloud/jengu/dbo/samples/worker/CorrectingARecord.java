package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Changing a record somebody else might also be changing.
 *
 * <p>Handed two things: the record as the clinic holds it, referred, so it
 * arrives at the version it is at; and the record as it should read, given.
 * It answers with the second written over the first <b>at the version the
 * correction was decided on</b> — the one the corrected record says it was
 * read at, or the one handed where it says none. If somebody moved the record
 * on in between, the clinic refuses the write rather than let a change decided
 * on one version silently replace a change made to the next, and the run ends
 * saying so, while whoever asked still has both the change and the reason.
 */
@Component
public final class CorrectingARecord implements StepService {

    /** Declared by each clinic whose records are corrected this way. */
    public static final String STEP = "care.records.correct";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        StoredObject held = work.input("record");
        Map<String, Object> corrected = new LinkedHashMap<>(asMap(work.input("corrected")));

        long decidedOn = held.versionId();
        if (corrected.remove("meta") instanceof Map<?, ?> meta
                && meta.get("versionId") != null) {
            decidedOn = Long.parseLong(String.valueOf(meta.get("versionId")));
        }
        // The record it corrects, by the id the clinic gave it: the sender's
        // copy may carry none, and may not name a different one.
        corrected.put("id", held.id());

        return Outcome.done(Map.of("corrected", 1L)).writing(Outcome.Write.update(
                held.typeName() + "/" + held.id(), decidedOn, RecordWire.write(corrected)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(StoredObject given) {
        return (Map<String, Object>) RecordWire.read(
                new String(given.payload(), StandardCharsets.UTF_8));
    }
}
