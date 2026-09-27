package cloud.jengu.dbo.spring.server;

import cloud.jengu.dbo.embedded.DboRegistrar;
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
 * tenant's stream as a named durable consumer. Both are selected on
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
            List<cloud.jengu.dbo.work.FleetWork.Performer> performers) {
        List<String> wrong = new ArrayList<>();
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
        // The fleet's half. A tenant-level step is polled for by a runner; a
        // fleet step is offered by the deployment's own joiner and performed
        // once for every tenant. Both arrive here as a bean and neither names
        // anything of the runtime's.
        Map<cloud.jengu.dbo.work.FleetWork.Performer, Map<String, String>> performing =
                new LinkedHashMap<>();
        for (cloud.jengu.dbo.work.FleetWork.Performer performer : performers) {
            DboFleetStep said = AnnotationUtils.findAnnotation(performer.getClass(),
                    DboFleetStep.class);
            if (said == null) {
                wrong.add(performer.getClass().getName() + " performs a fleet step and carries "
                        + "no @" + DboFleetStep.class.getSimpleName() + ", so every run it "
                        + "closed across the fleet would name no executor, no behaviour and "
                        + "no provider");
                continue;
            }
            Map<String, String> on = new LinkedHashMap<>();
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
                cloud.jengu.dbo.work.FleetWork.Performer.class, performer, on)));
        if (!registered.isEmpty()) {
            LOG.info("extension points: listeners={} observers={} fleet-steps={}",
                    listening.size(), watching.size(), performing.size());
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
