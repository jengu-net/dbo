package cloud.jengu.dbo.sample.participant;

import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The whole of an external participant: a laboratory that is not part of the
 * hospital's deployment and joins one of its processes.
 *
 * <p>This is the same wiring the sample beside it does — a lane, a runner, a
 * step service — and that is the claim. The participant is elsewhere, holds
 * no database, and reaches the hospital over one HTTP surface, so nothing
 * here can be doing what a co-located bundle does.
 *
 * <p>What it holds of the hospital is a base address and a way to get a
 * token. Everything else it brings itself, including the step: the runner
 * introduces {@link Assay#DECLARED} the first time it declares its candidacy,
 * because a service that brings its own capability says so beside saying it
 * is available.
 */
public final class Laboratory implements AutoCloseable {

    /** How the hospital's records name this participant and what it did. */
    public static final String NAME = "meristem-lab";

    private final StepRunner runner;

    public Laboratory(URI tenantBase, String tenant, Supplier<String> bearer) {
        // The provider is the laboratory, not the tenant it works for. A run
        // this participant performed is attributed to the organisation that
        // performed it, which is the point of letting anybody join.
        //
        // The scope is the baseline because this participant is not
        // overriding anything: it brought the step, and its executor IS the
        // rule for it. An executor declared at an organisation is a local
        // variation of somebody else's step, and a step that did not open
        // itself to one refuses it by name — which is the same sentence read
        // from the other side, and what the laboratory would meet if it
        // pointed this runner at a step the hospital installed.
        Executor identity = new Executor(NAME, "1", "meristem", Scope.BASELINE);
        this.runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(2))
                .register(new Assay())
                .attach(HttpLane.to(tenantBase.resolve("work"), bearer, tenant, NAME, identity));
    }

    /** One pass: take what is offered, perform it, report it. */
    public int cycle() {
        return runner.cycle();
    }

    @Override
    public void close() {
        runner.close();
    }
}
