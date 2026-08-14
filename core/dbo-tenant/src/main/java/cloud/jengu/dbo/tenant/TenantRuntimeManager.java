package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.fhir.r4.R4Store;
import cloud.jengu.dbo.fhir.r5.R5Personality;
import cloud.jengu.dbo.fhir.r5.R5Store;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.rest.FhirHttpServer;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Turns tenant spec files into live tenant service sets (dbo#17):
 * spec appears → provision (via the mandatory {@link TenantDatabaseProvisioner})
 * → personality + engine + facade + feed → FHIR endpoint at
 * {@code /t/<code>/fhir} on ONE shared port (the interim until the routing
 * layer) → listener callback (the OSGi activator registers services there).
 *
 * <p>Spec removal RETRACTS (endpoint down, pool released — data untouched);
 * erasure is only ever the explicit provisioner {@code deprovision}.
 */
public final class TenantRuntimeManager implements AutoCloseable {

    public record TenantRuntime(
            TenantSpec spec,
            ObjectStore engine,
            FhirStoreFacade store,
            ChangeFeed feed,
            FhirHttpServer endpoint) {}

    public interface Listener {
        void tenantUp(TenantRuntime runtime);

        void tenantDown(String code);
    }

    private final Path directory;
    private final TenantDatabaseProvisioner provisioner;
    private final Listener listener;
    private final HttpServer sharedServer;
    private final String host;
    private final Map<String, TenantRuntime> runtimes = new ConcurrentHashMap<>();
    private volatile Thread scanner;
    private volatile boolean running;

    public TenantRuntimeManager(Path directory, TenantDatabaseProvisioner provisioner,
            String host, int port, Listener listener) {
        this.directory = directory;
        this.provisioner = provisioner;
        this.host = host;
        this.listener = listener != null ? listener : new Listener() {
            @Override
            public void tenantUp(TenantRuntime runtime) {
            }

            @Override
            public void tenantDown(String code) {
            }
        };
        try {
            this.sharedServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sharedServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        sharedServer.start();
    }

    public int port() {
        return sharedServer.getAddress().getPort();
    }

    public String baseUrl(String code) {
        return "http://" + host + ":" + port() + "/t/" + code + "/fhir";
    }

    public Set<String> codes() {
        return Set.copyOf(runtimes.keySet());
    }

    public Optional<TenantRuntime> runtime(String code) {
        return Optional.ofNullable(runtimes.get(code));
    }

    /** One deterministic reconciliation round. Returns codes currently served. */
    public synchronized Set<String> scanOnce() {
        Set<String> declared = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            TenantSpec spec = TenantSpec.parse(Files.readString(f));
                            declared.add(spec.code());
                            if (!runtimes.containsKey(spec.code())) {
                                bringUp(spec);
                            }
                        } catch (Exception e) {
                            // a malformed spec provisions nothing — but the
                            // failure must be diagnosable from the process
                            // output, not only via absence
                            System.err.println("dbo-tenant: bring-up failed for " + f.getFileName());
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String code : Set.copyOf(runtimes.keySet())) {
            if (!declared.contains(code)) {
                takeDown(code);
            }
        }
        return codes();
    }

    private void bringUp(TenantSpec spec) {
        TenantDatabaseProvisioner.TenantDatabase db = provisioner.provision(spec);
        String base = baseUrl(spec.code());
        TenantRuntime runtime;
        if ("r4".equals(spec.fhirVersion())) {
            R4Personality personality = new R4Personality(spec.types());
            PgObjectStore engine = new PgObjectStore(db.dataSource(), personality.registrations());
            FhirStoreFacade store = new R4Store(engine, personality, base);
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R4Personality.DOMAIN),
                    new FhirHttpServer(sharedServer, store, null, "/t/" + spec.code() + "/fhir"));
        } else {
            R5Personality personality = new R5Personality(spec.types());
            PgObjectStore engine = new PgObjectStore(db.dataSource(), personality.registrations());
            FhirStoreFacade store = new R5Store(engine, personality, base);
            runtime = new TenantRuntime(spec, engine, store,
                    new PgChangeFeed(db.dataSource(), R5Personality.DOMAIN),
                    new FhirHttpServer(sharedServer, store, null, "/t/" + spec.code() + "/fhir"));
        }
        runtimes.put(spec.code(), runtime);
        listener.tenantUp(runtime);
    }

    private void takeDown(String code) {
        TenantRuntime runtime = runtimes.remove(code);
        if (runtime == null) {
            return;
        }
        listener.tenantDown(code);
        runtime.endpoint().close();
        provisioner.release(code);
    }

    /** Background reconciliation on a virtual thread. */
    public synchronized void start(long pollMillis) {
        if (running) {
            return;
        }
        running = true;
        scanner = Thread.ofVirtual().name("dbo-tenant-scanner").start(() -> {
            while (running) {
                try {
                    scanOnce();
                    Thread.sleep(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // keep reconciling
                }
            }
        });
    }

    @Override
    public synchronized void close() {
        running = false;
        if (scanner != null) {
            scanner.interrupt();
        }
        for (String code : Set.copyOf(runtimes.keySet())) {
            takeDown(code);
        }
        sharedServer.stop(0);
    }
}
