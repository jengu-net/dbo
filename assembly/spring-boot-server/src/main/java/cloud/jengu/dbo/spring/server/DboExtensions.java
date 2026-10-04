package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.embedded.DboRegistrar;
import cloud.jengu.dbo.runner.FleetStep;
import cloud.jengu.dbo.embedded.EmbeddedRuntime;
import cloud.jengu.dbo.tenant.api.TenantDomain;
import cloud.jengu.dbo.tenant.api.TenantLifecycleListener;
import cloud.jengu.dbo.tenant.api.TenantObserver;
import cloud.jengu.dbo.tenant.api.TenantPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The application's beans, put where the tenant runtime's whiteboards look.
 *
 * <p>A bean implementing {@code TenantLifecycleListener} is told when a
 * tenant reaches a point; one implementing {@code TenantObserver} reads a
 * tenant's stream as a named durable consumer; one implementing
 * {@code ContactListener} is told when this node hears a worker of its step
 * and when it stops hearing it. The first two are selected on
 * properties, and the runtime refuses a registration missing what it needs —
 * by name, into a log nobody is reading at four in the morning.
 *
 * <p><b>Every refusal here is made at context refresh instead.</b> Spring
 * knows each bean and its annotation before anything is registered, so the
 * author hears it rather than a log file does. That is most of what this
 * class is for; the registration itself is two lines.
 *
 * <p>Registered before the tenants come up, which is not decoration: a
 * listener that arrives after a tenant has reached a point is never told
 * about that tenant, and the silence is indistinguishable from working.
 */
public final class DboExtensions implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.server");

    private final List<DboRegistrar.Registration> registered = new ArrayList<>();

    DboExtensions(EmbeddedRuntime runtime, List<TenantLifecycleListener> listeners,
            List<TenantObserver> observers,
            List<cloud.jengu.dbo.runner.StepService> performers,
            List<cloud.jengu.dbo.work.ContactListener> contact) {
        List<String> wrong = new ArrayList<>();
        // A contact listener says how long a worker may be silent, and there
        // is no default: refused here, by name, rather than by the runtime
        // into its log once the application is already serving.
        List<cloud.jengu.dbo.work.ContactListener> heard = new ArrayList<>();
        for (cloud.jengu.dbo.work.ContactListener listener : contact) {
            try {
                cloud.jengu.dbo.work.Contacts.refuseWithoutASilence(listener);
                heard.add(listener);
            } catch (IllegalArgumentException refused) {
                wrong.add(refused.getMessage());
            }
        }
        Map<TenantLifecycleListener, Map<String, String>> listening = new LinkedHashMap<>();
        for (TenantLifecycleListener listener : listeners) {
            DboTenantListener said = AnnotationUtils.findAnnotation(listener.getClass(),
                    DboTenantListener.class);
            if (said == null) {
                wrong.add(listener.getClass().getName() + " implements "
                        + TenantLifecycleListener.class.getSimpleName() + " and carries no @"
                        + DboTenantListener.class.getSimpleName() + ", so there is no point for "
                        + "it to be told about and it would never be called");
                continue;
            }
            listening.put(listener, properties(TenantPoint.POINT, said.point().spelling(),
                    said.target()));
        }
        Map<TenantObserver, Map<String, String>> watching = new LinkedHashMap<>();
        for (TenantObserver observer : observers) {
            DboObserver said = AnnotationUtils.findAnnotation(observer.getClass(),
                    DboObserver.class);
            if (said == null) {
                wrong.add(observer.getClass().getName() + " implements "
                        + TenantObserver.class.getSimpleName() + " and carries no @"
                        + DboObserver.class.getSimpleName() + ", so there is no stream for it to "
                        + "read and no consumer for it to read as");
                continue;
            }
            if (said.consumer().isBlank()) {
                wrong.add(observer.getClass().getName() + " names no consumer. An observer "
                        + "resumes where it left off, and what it resumes is a name");
                continue;
            }
            Map<String, String> on = properties(TenantDomain.DOMAIN, said.domain().spelling(),
                    said.target());
            on.put(TenantDomain.CONSUMER, said.consumer());
            watching.put(observer, on);
        }
        // A step this deployment performs, for every tenant. The SAME
        // interface a tenant-level step is written as — what differs is which
        // level declared the code, and a step code belongs to one level, so
        // the store knows which side offers the work without being told.
        //
        // @DboFleetStep is what says a bean is the DEPLOYMENT's. An
        // unannotated StepService is a tenant's and is left to the runner,
        // because one application can be both — the worker sample's own test
        // boots this application beside a worker's tenant-level steps.
        // Where it IS annotated, it carries the pair a run must record and
        // the code cannot supply: in a worker that comes from
        // dbo.worker.identity.*, and a serving application has no such
        // property because the identity is the step's rather than the
        // application's.
        Map<cloud.jengu.dbo.runner.StepService, Map<String, String>> performing =
                new LinkedHashMap<>();
        for (cloud.jengu.dbo.runner.StepService performer : performers) {
            FleetStep said = AnnotationUtils.findAnnotation(performer.getClass(),
                    FleetStep.class);
            if (said == null) {
                // NOT AN ERROR, and this is the correction to a rule that was
                // wrong. A StepService is how a step is written at EITHER
                // level, so an unannotated one is a tenant's — the runner's to
                // poll for, not the fleet's to perform — and an application
                // can be both a server and a worker. This class saw the two
                // kinds and refused the context over the wrong one.
                //
                // The annotation is therefore what MARKS a bean as the
                // deployment's, rather than something demanded of every step.
                continue;
            }
            Map<String, String> on = new LinkedHashMap<>();
            // MARKED AS THE DEPLOYMENT'S, so the runner's own whiteboard leaves
            // it alone. This class knows which level a bean is for — the
            // annotation said so — and it is the only party that does.
            on.put(cloud.jengu.dbo.runner.StepService.FOR_THE_FLEET, "true");
            // WHO PERFORMED IT. Not the step code — a run already records the
            // step, and an executor repeating it would answer "what ran"
            // twice and "who ran it" never, which is the question a fleet
            // step makes worth asking since one bean answers for every tenant.
            on.put("dbo.executor.name", said.name());
            on.put("dbo.executor.version", said.version());
            on.put("dbo.executor.provider", said.provider());
            performing.put(performer, on);
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("a bean cannot be taken up as an extension point: "
                    + String.join("; ", wrong));
        }
        DboRegistrar registrar = runtime.registrar();
        listening.forEach((listener, on) -> registered.add(registrar.register(
                TenantLifecycleListener.class, listener, on)));
        watching.forEach((observer, on) -> registered.add(registrar.register(
                TenantObserver.class, observer, on)));
        performing.forEach((performer, on) -> registered.add(registrar.register(
                cloud.jengu.dbo.runner.StepService.class, performer, on)));
        heard.forEach(listener -> registered.add(registrar.register(
                cloud.jengu.dbo.work.ContactListener.class, listener, Map.of())));
        if (!registered.isEmpty()) {
            LOG.info("extension points: listeners={} observers={} fleet-steps={} contact={}",
                    listening.size(), watching.size(), performing.size(), heard.size());
        }
    }

    private static Map<String, String> properties(String name, String value, String target) {
        Map<String, String> on = new LinkedHashMap<>();
        on.put(name, value);
        if (!target.isBlank()) {
            on.put(TenantPoint.TARGET, target);
        }
        return on;
    }

    @Override
    public void close() {
        registered.forEach(DboRegistrar.Registration::close);
        registered.clear();
    }
}
