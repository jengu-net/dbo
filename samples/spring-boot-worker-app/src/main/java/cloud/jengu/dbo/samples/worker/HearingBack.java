package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.spring.worker.DboInitiator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a run this application asked for came to.
 *
 * <p>The store tells nobody when a run ends: a run is a record, and its end is
 * a version of it. So the application that asked, asks again, at the run's own
 * address and on the credential it asked with — and is answered with the run as
 * a {@code Task}: how it stands, what it was over, what the step counted and
 * what the hospital wrote for it.
 *
 * <p>Read here rather than by whoever called, because the answer's shape is the
 * store's and the sentences an application wants from it are few: did it
 * finish, how many, and which records did it leave behind.
 */
@Component
public final class HearingBack {

    private final DboInitiator initiator;

    HearingBack(DboInitiator initiator) {
        this.initiator = initiator;
    }

    // --8<-- [start:hearing]
    /** How the run stands now. */
    public DboInitiator.Answer now(String tenant, String run) {
        return initiator.answer(tenant, run);
    }

    /**
     * How the run came to rest, waiting for it as long as this application is
     * willing to; "still in progress" is an answer too, and the caller decides
     * what to do about it.
     */
    public DboInitiator.Answer settled(String tenant, DboInitiator.Started started,
            Duration patience) {
        return initiator.awaiting(tenant, started.runOrFail(), patience);
    }
    // --8<-- [end:hearing]

    // --8<-- [start:reading]
    /** What the step counted under this name, if it counted it. */
    public static Optional<Long> counted(DboInitiator.Answer answer, String name) {
        for (Map<?, ?> output : outputs(answer)) {
            if (coded(output, "urn:dbo:run:tally", name)
                    && output.get("valueInteger") instanceof Number count) {
                return Optional.of(count.longValue());
            }
        }
        return Optional.empty();
    }

    /**
     * The records the hospital wrote for the run's result, as
     * {@code Type/id/_history/version}, in the order the step gave them.
     */
    public static List<String> produced(DboInitiator.Answer answer) {
        return outputs(answer).stream()
                .filter(output -> coded(output, null, "produced"))
                .map(output -> output.get("valueReference") instanceof Map<?, ?> reference
                        ? String.valueOf(reference.get("reference")) : null)
                .filter(reference -> reference != null)
                .toList();
    }
    // --8<-- [end:reading]

    /** The run itself, as the Task it answered with. */
    public static Map<?, ?> task(DboInitiator.Answer answer) {
        return answer.answered() ? (Map<?, ?>) RecordWire.read(answer.body()) : Map.of();
    }

    private static List<Map<?, ?>> outputs(DboInitiator.Answer answer) {
        if (!(task(answer).get("output") instanceof List<?> outputs)) {
            return List.of();
        }
        return outputs.stream().filter(Map.class::isInstance).<Map<?, ?>>map(o -> (Map<?, ?>) o)
                .toList();
    }

    private static boolean coded(Map<?, ?> output, String system, String code) {
        if (!(output.get("type") instanceof Map<?, ?> type)
                || !(type.get("coding") instanceof List<?> codings)) {
            return false;
        }
        return codings.stream().filter(Map.class::isInstance).map(c -> (Map<?, ?>) c)
                .anyMatch(coding -> code.equals(coding.get("code"))
                        && (system == null || system.equals(coding.get("system"))));
    }
}
