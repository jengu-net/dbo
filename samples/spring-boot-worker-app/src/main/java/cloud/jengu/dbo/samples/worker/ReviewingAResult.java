package cloud.jengu.dbo.samples.worker;

import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.Work;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A result reviewed by automation: the ones the clinic's step lets it take.
 *
 * <p>Nothing here decides which those are. The clinic's step says when
 * automation may take a review — a normal result — and the store decides it
 * as the task is authored, so this service is offered only those and a nurse
 * takes the rest. Asked to judge a result itself, it would first have to read
 * one it may have no business reading.
 */
// --8<-- [start:step]
@Component
public final class ReviewingAResult implements StepService {

    /** Declared by a clinic that reviews its results this way. */
    public static final String STEP = "care.results.review";

    @Override
    public String step() {
        return STEP;
    }

    @Override
    public Outcome perform(Work work) {
        work.input("result");
        return Outcome.done(Map.of("reviewed", 1L));
    }
}
// --8<-- [end:step]
