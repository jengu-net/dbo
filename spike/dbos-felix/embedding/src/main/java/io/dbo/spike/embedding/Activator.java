package io.dbo.spike.embedding;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.config.DBOSConfig;
import io.dbo.spike.api.DurableService;
import io.dbo.spike.api.SpikeWorkflows;
import io.dbo.spike.api.SpikeWorkflowsFactory;
import io.dbo.spike.api.StepRunner;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.osgi.framework.ServiceReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Tracks SpikeWorkflowsFactory contributions (whiteboard) and, per configured
 * database url, launches an independent DBOS runtime and registers a
 * DurableService for it.
 *
 * Framework properties consumed:
 *   dbo.spike.db.url.N / dbo.spike.db.user / dbo.spike.db.password
 *   dbo.spike.evidence.dir
 */
public class Activator implements BundleActivator {

    private ServiceTracker<SpikeWorkflowsFactory, SpikeWorkflowsFactory> tracker;
    private final List<Runtime> runtimes = new ArrayList<>();
    private final List<ServiceRegistration<DurableService>> registrations = new ArrayList<>();

    @Override
    public void start(BundleContext ctx) {
        tracker = new ServiceTracker<>(ctx, SpikeWorkflowsFactory.class,
                new ServiceTrackerCustomizer<>() {
                    @Override
                    public SpikeWorkflowsFactory addingService(ServiceReference<SpikeWorkflowsFactory> ref) {
                        SpikeWorkflowsFactory factory = ctx.getService(ref);
                        launchAll(ctx, factory);
                        return factory;
                    }

                    @Override
                    public void modifiedService(ServiceReference<SpikeWorkflowsFactory> ref, SpikeWorkflowsFactory s) {}

                    @Override
                    public void removedService(ServiceReference<SpikeWorkflowsFactory> ref, SpikeWorkflowsFactory s) {}
                });
        tracker.open();
    }

    private void launchAll(BundleContext ctx, SpikeWorkflowsFactory factory) {
        Path evidence = Path.of(ctx.getProperty("dbo.spike.evidence.dir"));
        String user = ctx.getProperty("dbo.spike.db.user");
        String password = ctx.getProperty("dbo.spike.db.password");
        for (int i = 1; ; i++) {
            String url = ctx.getProperty("dbo.spike.db.url." + i);
            if (url == null) break;
            Runtime rt = new Runtime("rt" + i, url, user, password, factory, evidence);
            rt.launch();
            runtimes.add(rt);
            Hashtable<String, Object> props = new Hashtable<>();
            props.put("dbo.runtime", rt.id);
            registrations.add(ctx.registerService(DurableService.class, rt.service(), props));
        }
    }

    @Override
    public void stop(BundleContext ctx) {
        registrations.forEach(ServiceRegistration::unregister);
        registrations.clear();
        runtimes.forEach(Runtime::shutdown);
        runtimes.clear();
        if (tracker != null) tracker.close();
    }

    /** One independent DBOS runtime over one system database. */
    static final class Runtime {
        final String id;
        private final String url, user, password;
        private final SpikeWorkflowsFactory factory;
        private final Path evidence;
        private volatile DBOS dbos;
        private volatile SpikeWorkflows proxy;

        Runtime(String id, String url, String user, String password,
                SpikeWorkflowsFactory factory, Path evidence) {
            this.id = id;
            this.url = url;
            this.user = user;
            this.password = password;
            this.factory = factory;
            this.evidence = evidence;
        }

        void launch() {
            // Landmine #1: JDBC DriverManager does not discover drivers on a
            // bundle classpath. Load the embedded driver explicitly so its
            // static initializer registers it; DriverManager's caller-visibility
            // check then passes because Hikari lives in the same classloader.
            try {
                Class.forName("org.postgresql.Driver", true, Activator.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("embedded postgres driver missing", e);
            }
            DBOSConfig cfg = DBOSConfig.defaults("dbo-spike-" + id)
                    .withDatabaseUrl(url)
                    .withDbUser(user)
                    .withDbPassword(password)
                    .withMigrate(true);
            DBOS d = new DBOS(cfg);
            StepRunner steps = new StepRunner() {
                @Override
                public <T> T run(String stepName, java.util.concurrent.Callable<T> body) {
                    return currentDbos().runStep(() -> {
                        try {
                            return body.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, stepName);
                }
            };
            SpikeWorkflows impl = factory.create(steps, evidence);
            this.proxy = d.registerProxy(SpikeWorkflows.class, impl);
            d.launch();
            this.dbos = d;
        }

        DBOS currentDbos() {
            return dbos;
        }

        void shutdown() {
            DBOS d = dbos;
            if (d != null) d.shutdown();
        }

        DurableService service() {
            return new DurableService() {
                @Override
                public String runtimeId() {
                    return id;
                }

                @Override
                public String greetAndWait(String workflowId, String name) {
                    var handle = dbos.startWorkflow(() -> proxy.greet(name),
                            new StartWorkflowOptions(workflowId));
                    try {
                        return handle.getResult();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void startGated(String workflowId, String name) {
                    dbos.startWorkflow(() -> proxy.gatedGreet(name),
                            new StartWorkflowOptions(workflowId));
                }

                @Override
                public String statusOf(String workflowId) {
                    return dbos.getWorkflowStatus(workflowId)
                            .map(s -> String.valueOf(s.status()))
                            .orElse("UNKNOWN");
                }

                @Override
                public void crash() {
                    dbos.shutdown();
                }

                @Override
                public void relaunch() {
                    launch();
                }

                @Override
                public String resume(String workflowId) {
                    try {
                        var handle = dbos.<String, Exception>resumeWorkflow(workflowId);
                        return handle.getResult();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
    }
}
