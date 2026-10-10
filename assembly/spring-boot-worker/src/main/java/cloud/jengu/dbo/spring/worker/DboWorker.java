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
    private final List<cloud.jengu.dbo.runner.HeartbeatStatistics> statistics;

    private final List<Attached> attachedLanes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<DboRegistrar.Registration> performing = new ArrayList<>();
    private volatile boolean running;

    DboWorker(EmbeddedRuntime runtime, DboWorkerProperties properties, List<StepService> steps,
            Map<String, Supplier<String>> tokens) {
        this(runtime, properties, steps, tokens, List.of());
    }

    DboWorker(EmbeddedRuntime runtime, DboWorkerProperties properties, List<StepService> steps,
            Map<String, Supplier<String>> tokens,
            List<cloud.jengu.dbo.runner.HeartbeatStatistics> statistics) {
        this.runtime = runtime;
        this.properties = properties;
        this.steps = List.copyOf(steps);
        this.tokens = Map.copyOf(tokens);
        this.statistics = List.copyOf(statistics);
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

    /** The tenants this worker is offered work by, configured or attached since. */
    public List<String> lanes() {
        List<String> tenants = new ArrayList<>(properties.getLanes().stream()
                .filter(DboWorkerProperties.Lane::overTheSubstrate)
                .map(DboWorkerProperties.Lane::getTenant).toList());
        attachedLanes.forEach(attached -> tenants.add(attached.tenant()));
        return List.copyOf(tenants);
    }

    /**
     * Holds a lane to a tenant from now on: its work is offered to this
     * worker's steps, and the lane can be told to keep a place of the tenant
     * up to date as well.
     *
     * <p><b>For a credential that arrives while the application runs.</b> A
     * site is enrolled by somebody acting in the cloud, so its lane exists
     * only once that happened; the lanes configured in properties are this
     * same call made at start. The credential is asked for on every call
     * rather than held, so one that is rotated needs no second attach — and
     * it is never written anywhere by this: a host that wants the lane back
     * after a restart attaches it again, from wherever it keeps secrets.
     *
     * @param base  where the tenant answers, for example {@code https://host/t/code/}
     * @param token the credential, asked for on every call
     */
    public synchronized Attached attach(String tenant, java.net.URI base,
            Supplier<String> token) {
        // The baseline unless this application said otherwise. A step is
        // not overridable by default and the baseline is the only scope
        // every step admits, so a worker scoped to an organisation by
        // default is one whose claims are refused by every step that never
        // opened itself to being varied.
        Executor identity = new Executor(properties.getIdentity().getName(),
                properties.getIdentity().getVersion(), tenant,
                properties.getIdentity().getScope() == DboWorkerProperties.Scope.ORGANISATION
                        ? Scope.organisation(tenant)
                        : Scope.BASELINE);
        HttpLane lane = HttpLane.to(base.resolve("work"), token, tenant,
                properties.getIdentity().getName(), identity);
        // Said on the registration, so whatever reads the whiteboard can tell
        // a lane to a tenant elsewhere from one to a tenant served here under
        // the same code — which is exactly what a site holds.
        Map<String, String> remote = Map.of("dbo.lane.tenant", tenant,
                "dbo.lane.base", base.toString());
        Attached attached = new Attached(tenant, lane, remote,
                runtime.registrar().register(Lane.class, lane, remote));
        attachedLanes.add(attached);
        return attached;
    }

    /**
     * A lane this worker holds, and whether the tenant it reaches is also
     * served here as a place of it.
     */
    public final class Attached {

        private final String tenant;
        private final HttpLane lane;
        private final Map<String, String> remote;
        private final DboRegistrar.Registration work;
        private DboRegistrar.Registration origin;
        private boolean detached;

        private Attached(String tenant, HttpLane lane, Map<String, String> remote,
                DboRegistrar.Registration work) {
            this.tenant = tenant;
            this.lane = lane;
            this.remote = remote;
            this.work = work;
        }

        public String tenant() {
            return tenant;
        }

        /**
         * Turns keeping the place up to date on or off.
         *
         * <p>Off is a pause. The place goes on serving what it holds, its
         * position stays where the tenant keeps it, and on again carries on
         * from there; nothing a place serves is withdrawn by this. Work is not
         * touched either way.
         */
        public synchronized void sync(boolean on) {
            if (detached || on == (origin != null)) {
                return;
            }
            if (on) {
                origin = runtime.registrar().register(
                        cloud.jengu.dbo.runner.transport.Origin.class, lane.origin(), remote);
            } else {
                origin.close();
                origin = null;
            }
            LOG.info("place of {} is {}kept up to date over its lane", tenant, on ? "" : "not ");
        }

        public synchronized boolean syncing() {
            return origin != null;
        }

        /** Lets the lane go: no more work from it, and the place stops being kept up to date. */
        public synchronized void detach() {
            if (detached) {
                return;
            }
            sync(false);
            work.close();
            detached = true;
            attachedLanes.remove(this);
        }
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
        // What this application adds to its heartbeats, before any lane, so
        // the first heartbeat on each already says it.
        for (cloud.jengu.dbo.runner.HeartbeatStatistics contributor : statistics) {
            performing.add(registrar.register(cloud.jengu.dbo.runner.HeartbeatStatistics.class,
                    contributor, Map.of()));
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
            Attached attached = attach(declared.getTenant(), declared.getBase(),
                    tokens.get(declared.getTenant()));
            attached.sync(declared.isSync());
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
        List.copyOf(attachedLanes).forEach(Attached::detach);
        performing.forEach(DboRegistrar.Registration::close);
        performing.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Whether the context starts this worker, which is what
     * {@code dbo.worker.auto-start} says.
     *
     * <p>Left still, it registers nothing and is offered no work until it is
     * started by name — which is how an application keeps its own copy of the
     * steps quiet while a worker in another JVM performs them.
     */
    @Override
    public boolean isAutoStartup() {
        return properties.isAutoStart();
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
            if (lane.getBase() == null) {
                // Nothing to sign in with and nowhere to do it: that plane
                // carries no token, and an ask on it is signed with the
                // enrolment key instead. A credential built here would be one
                // obtained from a door this lane never speaks to. A lane over
                // the substrate that names a base keeps its credential, for
                // the application to ask the tenant for work with.
                continue;
            }
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
