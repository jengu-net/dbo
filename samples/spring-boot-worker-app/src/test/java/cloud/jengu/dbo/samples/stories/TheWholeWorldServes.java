package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds a story until every tenant the world declares is serving.
 *
 * <p>The world comes up once, for every story, and the scan brings its members
 * up together, each waiting only for its own upstreams. A story starting
 * before that is finished would find a member still coming up and fail on it,
 * naming a leg when the cause was the bring-up.
 *
 * <p>The first story to arrive waits out the bring-up, and the others find the
 * world already up: the check costs one look once it has passed.
 */
public final class TheWholeWorldServes implements BeforeAllCallback {

    /** A liveness bound, not a budget: a cold bring-up is minutes on a slow machine. */
    private static final Duration GIVE_UP = Duration.ofMinutes(15);

    @Override
    public void beforeAll(ExtensionContext context) throws InterruptedException {
        DboTestContext dbo = SpringExtension.getApplicationContext(context)
                .getBean(DboTestContext.class);
        long giveUp = System.nanoTime() + GIVE_UP.toNanos();
        List<String> missing = missing(dbo);
        while (!missing.isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(500);
            missing = missing(dbo);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the world never finished coming up: " + missing
                    + " declared and not serving. A tenant that is declared and not serving "
                    + "has said why in the log above this line.");
        }
    }

    private static List<String> missing(DboTestContext dbo) {
        List<String> serving = dbo.serving();
        List<String> missing = new ArrayList<>(dbo.declaring());
        missing.removeAll(serving);
        return missing;
    }
}
