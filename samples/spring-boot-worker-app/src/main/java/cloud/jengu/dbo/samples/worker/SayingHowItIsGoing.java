package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.HeartbeatStatistics;
import cloud.jengu.dbo.runner.StepService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this worker adds to every heartbeat, beside the runner's own counts.
 *
 * <p>Under a namespace of its own, nested as deep as it likes: the store reads
 * none of it and hands it to whoever listens for this worker. Nothing here is
 * about a person, because a heartbeat travels outside any sealed work.
 */
// --8<-- [start:statistics]
@Component
public final class SayingHowItIsGoing implements HeartbeatStatistics {

    private final Instant since = Instant.now();
    private final ObjectProvider<StepService> steps;

    SayingHowItIsGoing(ObjectProvider<StepService> steps) {
        this.steps = steps;
    }

    @Override
    public String namespace() {
        return "sample.worker";
    }

    @Override
    public Map<String, Object> statistics() {
        Map<String, Object> said = new LinkedHashMap<>();
        said.put("since", since.toString());
        said.put("performing", steps.orderedStream().map(StepService::step).toList());
        said.put("jvm", Map.of(
                "processors", (long) Runtime.getRuntime().availableProcessors(),
                "freeMemory", Runtime.getRuntime().freeMemory()));
        return said;
    }
}
// --8<-- [end:statistics]
