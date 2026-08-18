package cloud.jengu.dbo.tenant.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mirrors the operator's {@code dbo-tenants} ConfigMap into a local
 * directory, so {@code TenantRuntimeManager} runs UNCHANGED
 * in-cluster: it keeps watching a spec directory; this keeps that directory
 * equal to the ConfigMap. (A ConfigMap volume mount does the same job in a
 * pod; this class is the mount for processes that don't have one — and the
 * deterministic sync for tests.)
 */
public final class SpecDirSync implements AutoCloseable {

    private final KubernetesClient k8s;
    private final String namespace;
    private final Path dir;
    private volatile Thread loop;
    private volatile boolean running;

    public SpecDirSync(KubernetesClient k8s, String namespace, Path dir) {
        this.k8s = k8s;
        this.namespace = namespace;
        this.dir = dir;
    }

    /** One sync: directory becomes exactly the ConfigMap. Returns keys present. */
    public Set<String> syncOnce() {
        ConfigMap cm = k8s.configMaps().inNamespace(namespace)
                .withName(TenantK8sContract.CONFIGMAP).get();
        Map<String, String> data = cm == null || cm.getData() == null ? Map.of() : cm.getData();
        try {
            for (Map.Entry<String, String> e : data.entrySet()) {
                Path file = dir.resolve(e.getKey());
                if (!Files.exists(file) || !Files.readString(file).equals(e.getValue())) {
                    Files.writeString(file, e.getValue());
                }
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path stale : files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .filter(f -> !data.containsKey(f.getFileName().toString()))
                        .collect(Collectors.toList())) {
                    Files.delete(stale);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return data.keySet();
    }

    public void start(long intervalMillis) {
        running = true;
        loop = Thread.ofVirtual().name("dbo-spec-sync").start(() -> {
            while (running) {
                try {
                    syncOnce();
                } catch (Exception nextSyncRetries) {
                    // poll sync is self-healing
                }
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @Override
    public void close() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
    }
}
