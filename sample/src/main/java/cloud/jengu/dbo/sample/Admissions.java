package cloud.jengu.dbo.sample;

import cloud.jengu.dbo.runner.StepRunner;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The whole of what an integrator wires: a lane to one tenant, a runner, and
 * the step services it performs.
 *
 * <p>There is no store here and no way to get one. What this holds is a lane
 * — the work vocabulary and nothing else — so this same class runs beside the
 * store, in another process, or on an appliance behind a firewall, and the
 * step it carries cannot tell which.
 *
 * <p>The token is a supplier rather than a string. A runner outlives an access
 * token, and one captured at construction starts failing an hour later in a
 * way that reads like the store going away.
 */
public final class Admissions implements AutoCloseable {

    private final StepRunner runner;

    public Admissions(URI tenantBase, String tenant, Supplier<String> bearer) {
        // Named, versioned, provided and scoped: an executor that cannot be
        // reproduced cannot be held to what it did.
        Executor identity = new Executor("ward-runner", "1", tenant, Scope.organisation(tenant));
        this.runner = new StepRunner(Duration.ofMinutes(1), Duration.ofSeconds(2))
                .register(new AssayStep())
                .attach(HttpLane.to(tenantBase.resolve("work"), bearer, tenant,
                        "ward-runner", identity));
    }

    /**
     * One pass: take what is offered, perform it, report it.
     *
     * <p>{@code start()} runs this on a loop instead, which is what a
     * long-lived process does. A test asks for one pass so it can say what
     * happened in it.
     */
    public int cycle() {
        return runner.cycle();
    }

    @Override
    public void close() {
        runner.close();
    }
}
