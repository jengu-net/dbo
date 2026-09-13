package cloud.jengu.dbo.bench;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.RunSpans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a tenant costs to bring up, on this board.
 *
 * <p>The one figure an edge deployment is actually asked for. A tenant is
 * not one act: a database is created, the face's definitions are
 * synchronised, the zone's are, and a vocabulary is loaded — and on a board
 * with four slow cores those are not remotely the same size. Reporting only
 * the total tells an operator that provisioning takes half a minute and
 * nothing about which half a minute to attack.
 *
 * <p><b>Read from the runs, not timed from outside.</b> A deployment already
 * records bring-up as a run with its phases beneath it, so this measures
 * what the deployment itself says happened rather than a second opinion
 * formed by a stopwatch in the bench — and the numbers here are the numbers
 * a supervisor reads from the same records in production.
 *
 * <p><b>A real manager over the real provisioner.</b> The point is the cost
 * of the actual act: a database created, a face loaded through the same
 * chain a deployment uses. Anything cheaper would be measuring a fixture.
 */
final class ProvisioningPhases {

    private final Map<String, Latency> byPhase = new LinkedHashMap<>();
    private final Latency toServing = new Latency();
    private boolean complete = true;

    /**
     * Brings up {@code count} tenants and keeps what each phase cost.
     *
     * @param patience how long one tenant may take before this gives up on it
     */
    void drive(Config where, int count, Duration patience) throws Exception {
        Path directory = Files.createTempDirectory("dbo-bench-tenants");
        LocalDatabasePerTenantProvisioner provisioner = new LocalDatabasePerTenantProvisioner(
                where.jdbcUrl(), where.user(), where.password());
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        // Port 0, because the bench may be sharing this board with an
        // appliance that is already serving and a fixed port would measure
        // whichever started first.
        TenantRuntimeManager manager = new TenantRuntimeManager(directory, provisioner,
                "127.0.0.1", 0, null, new TenantRuntimeManager.AuthorityConfig(kek, null));
        try {
            Path managementSpec = Files.createTempDirectory("dbo-bench-management")
                    .resolve("registry.json");
            String management = "benchmgmt" + Instant.now().toEpochMilli();
            Files.writeString(managementSpec, spec(management));
            // The managing tenant comes up first and is not measured: it is
            // this deployment's own history, and its bring-up is the one
            // nobody waits for.
            manager.manages(managementSpec);
            Instant from = Instant.now();

            for (int i = 0; i < count; i++) {
                String code = "benchprov" + Instant.now().toEpochMilli() + i;
                Files.writeString(directory.resolve(code + ".json"), spec(code));
                long began = System.nanoTime();
                manager.scanOnce();
                Instant until = Instant.now().plus(patience);
                while (manager.runtime(code).isEmpty() && Instant.now().isBefore(until)) {
                    Thread.sleep(200);
                    manager.scanOnce();
                }
                if (manager.runtime(code).isEmpty()) {
                    complete = false;
                    System.out.println("    " + code + " did not come up in "
                            + patience.toSeconds() + "s");
                    continue;
                }
                toServing.record(System.nanoTime() - began);
            }

            // What the deployment says it did, in its own records. Empty is
            // possible and is not zero: a manager with nobody managing
            // records nothing, and a phase nobody ran has no samples rather
            // than a sample of nought.
            manager.runtime(management).ifPresent(runtime -> {
                List<RunSpans.Span> spans = new RunSpans(runtime.engine()).since(from, management);
                for (RunSpans.Span span : spans) {
                    if (!span.name().startsWith("dbo.tenant.bringup/")) {
                        continue;
                    }
                    String phase = span.name().substring("dbo.tenant.bringup/".length());
                    byPhase.computeIfAbsent(phase, ignored -> new Latency())
                            .record(Duration.between(span.began(), span.ended()).toNanos());
                }
            });
        } finally {
            manager.close();
        }
    }

    boolean complete() {
        return complete;
    }

    private static String spec(String code) {
        return """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[{"name":"Observation","identity":"internal",
                           "handling":"operational"}]}""".formatted(code);
    }

    String json() {
        StringBuilder out = new StringBuilder("{\"toServingMs\":")
                .append(toServing.json(false));
        byPhase.forEach((phase, took) -> out.append(",\"").append(phase).append("\":")
                .append(took.json(false)));
        return out.append('}').toString();
    }

    String summary() {
        StringBuilder out = new StringBuilder(line("to serving", toServing));
        byPhase.forEach((phase, took) -> out.append(line(phase, took)));
        return out.toString();
    }

    private static String line(String name, Latency of) {
        return String.format("    %-12s %5d tenants  p50 %.0fms  max %.0fms%n",
                name, of.count(), of.millis(0.5), of.maxMillis());
    }

    /** Where the databases are made, which is the bench's own connection. */
    record Config(String jdbcUrl, String user, String password) {}
}
