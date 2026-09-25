package cloud.jengu.dbo.spring.worker;

import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.StepService;
import cloud.jengu.dbo.runner.http.HttpLane;
import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the sample's {@code Admissions} was, minus the writing of it.
 *
 * <p>That class constructs an executor identity, a runner with a hold and a
 * poll, registers a step and attaches a lane built from a base URI, a tenant
 * code, a name and a bearer supplier. It is forty lines of wiring an
 * application should not have to write twice, and every one of them is either
 * a property here or a bean the application already has.
 *
 * <p><b>Nothing here constructs a runner.</b> The runner's own activator is
 * the whiteboard: any bundle registering a {@code StepService} contributes a
 * step, and a {@code Lane} gives it somewhere to poll. This puts the
 * application's beans and its configured lanes where that whiteboard looks
 * and then gets out of the way. A starter that drove the runner itself would
 * be a second implementation of it, drifting from the first.
 */
public final class DboWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.worker");

    private final EmbeddedRuntime runtime;
    private final DboWorkerProperties properties;
    private final List<StepService> steps;
    private final Map<String, Supplier<String>> tokens;

    private final List<DboRegistrar.Registration> lanes = new ArrayList<>();
    private final List<DboRegistrar.Registration> performing = new ArrayList<>();
    private volatile boolean running;

    DboWorker(EmbeddedRuntime runtime, DboWorkerProperties properties, List<StepService> steps,
            Map<String, Supplier<String>> tokens) {
        this.runtime = runtime;
        this.properties = properties;
        this.steps = List.copyOf(steps);
        this.tokens = Map.copyOf(tokens);
    }

    /**
     * The steps this application performs, by the code each declared.
     *
     * <p>What an application asks when it wants to know whether its bean was
     * taken up — which is a fair question, because the alternative answer is
     * silence that looks exactly like having nothing to do.
     */
    public Map<String, String> performing() {
        Map<String, String> said = new LinkedHashMap<>();
        steps.forEach(step -> said.put(step.step(), step.getClass().getName()));
        return said;
    }

    /** The tenants this worker is offered work by. */
    public List<String> lanes() {
        return properties.getLanes().stream().map(DboWorkerProperties.Lane::getTenant).toList();
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        DboRegistrar registrar = runtime.registrar();
        // The steps first. A lane registered before the services would have
        // the runner poll for work it cannot yet perform, take some, and
        // release it — a burst of refusals at every startup, for as long as
        // the gap lasts.
        for (StepService step : steps) {
            performing.add(registrar.register(StepService.class, step, Map.of()));
        }
        for (DboWorkerProperties.Lane declared : properties.getLanes()) {
            if (declared.overTheSubstrate()) {
                // The container builds this one. The stream bundle reads the
                // tenants it is a host for and opens a door on the substrate
                // for each, registering the lane on the same whiteboard this
                // registers an HTTP one on — so the runner is handed it without
                // this knowing, which is the whole point of a lane having
                // carriers a runner cannot tell apart.
                continue;
            }
            // The baseline unless this application said otherwise. A step is
            // not overridable by default and the baseline is the only scope
            // every step admits, so a worker scoped to an organisation by
            // default is one whose claims are refused by every step that never
            // opened itself to being varied.
            Executor identity = new Executor(properties.getIdentity().getName(),
                    properties.getIdentity().getVersion(), declared.getTenant(),
                    properties.getIdentity().getScope() == DboWorkerProperties.Scope.ORGANISATION
                            ? Scope.organisation(declared.getTenant())
                            : Scope.BASELINE);
            Lane lane = HttpLane.to(declared.getBase().resolve("work"),
                    tokens.get(declared.getTenant()), declared.getTenant(),
                    properties.getIdentity().getName(), identity);
            lanes.add(registrar.register(Lane.class, lane, Map.of()));
        }
        running = true;
        LOG.info("starting: component=dbo-worker steps={} lanes={} poll={}ms",
                steps.size(), properties.getLanes().size(), properties.getPoll().toMillis());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        LOG.info("shutdown requested: component=dbo-worker");
        // The lanes first, so the runner stops being OFFERED work before it
        // stops being able to perform it. The other order takes work it can
        // no longer do anything with, and the run is released with a reason
        // nobody wrote.
        lanes.forEach(DboRegistrar.Registration::close);
        lanes.clear();
        performing.forEach(DboRegistrar.Registration::close);
        performing.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * After the container, before anything that would offer this work.
     *
     * <p>{@code DEFAULT_PHASE - 1}: the container's own lifecycle sits at the
     * default, and a worker registering its steps into a framework that has
     * not booted registers them into nothing.
     */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 1;
    }

    /** The suppliers, one per lane, built from what each lane was configured with. */
    static Map<String, Supplier<String>> tokensFor(DboWorkerProperties properties,
            Map<String, Supplier<String>> supplied) {
        Map<String, Supplier<String>> byTenant = new HashMap<>();
        for (DboWorkerProperties.Lane lane : properties.getLanes()) {
            Supplier<String> own = supplied.get(lane.getTenant());
            if (own != null) {
                byTenant.put(lane.getTenant(), own);
                continue;
            }
            DboWorkerProperties.Token token = lane.getToken();
            if (token != null && token.getValue() != null && !token.getValue().isBlank()) {
                String fixed = token.getValue();
                byTenant.put(lane.getTenant(), () -> fixed);
                continue;
            }
            byTenant.put(lane.getTenant(), new ClientCredentials(lane.getBase(), lane.getTenant(),
                    token.getClientId(), token.getClientSecret()));
        }
        return byTenant;
    }
}
