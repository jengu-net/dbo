package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What was measured at a visit, written down as one.
 *
 * <p>Given the observations, in the order they were taken, and answered with
 * all of them as one result — which the clinic commits whole or not at all,
 * so a visit is never half recorded. One observation may gather others by
 * the {@code urn:uuid} it was given them under, and those references resolve
 * inside that one commit; one may name its patient by what the sender knows
 * of them, {@code Patient?identifier=…}, and the clinic resolves that against
 * its own records as it writes.
 *
 * <p>An observation is named for the others by its {@code id}, which is the
 * sender's name for it and not a record's: there is no record yet, and the
 * clinic gives each one an id of its own when it writes it.
 */
@Component
public final class RecordingAVisit implements StepService {

    /** Declared by a clinic that records its visits this way. */
    public static final String STEP = "care.visit.record";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        List<StoredObject> observations = work.all("observations");
        if (observations.isEmpty()) {
            return Outcome.failed("the visit arrived with nothing measured");
        }
        List<Outcome.Write> writes = new ArrayList<>();
        for (StoredObject observation : observations) {
            Map<String, Object> said = new LinkedHashMap<>(asMap(observation));
            Object named = said.remove("id");
            String json = RecordWire.write(said);
            writes.add(named == null ? Outcome.Write.create(json)
                    : Outcome.Write.create("urn:uuid:" + named, json));
        }
        return Outcome.done(Map.of("recorded", (long) writes.size()))
                .writing(writes.toArray(Outcome.Write[]::new));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(StoredObject given) {
        return (Map<String, Object>) RecordWire.read(
                new String(given.payload(), StandardCharsets.UTF_8));
    }
}
