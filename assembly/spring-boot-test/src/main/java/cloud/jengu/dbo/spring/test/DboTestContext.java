package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.spring.DboRegistrar;
import cloud.jengu.dbo.spring.EmbeddedRuntime;
import cloud.jengu.dbo.sync.ConfigSource;
import org.springframework.context.SmartLifecycle;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What a test holds the deployment by.
 *
 * <p>Injected like any bean. What it offers is the things a test actually does
 * — declare a tenant, retract one, wait for it to be serving — done the way a
 * host does them rather than the way a fixture would.
 *
 * <p><b>Tenants are declared in memory, and the directory is a bootstrap.</b>
 * {@code dbo.test.world} seeds the world once, so a reader can open it; after
 * that every change is a call here and nothing is written to a disk. That is
 * not a shortcut around the runtime: {@code declaredFrom} says a directory is
 * one source among several, and an application declaring its tenants where the
 * rest of its configuration lives is the case this is.
 *
 * <p><b>It registers the source, and the runtime waits for it.</b> Told to
 * expect a shared source, the tenant activator does not start until one
 * arrives — so a deployment that never registers one serves nobody and waits
 * quietly, which is the failure this bean's whole job is to avoid.
 */
public final class DboTestContext implements SmartLifecycle {

    private final TheWorldThisTestDeclares world;
    private final EmbeddedRuntime runtime;
    private final cloud.jengu.dbo.spring.server.DboTenants tenants;
    private final int port;
    private DboRegistrar.Registration registered;
    private volatile boolean running;

    DboTestContext(TheWorldThisTestDeclares world, EmbeddedRuntime runtime,
            cloud.jengu.dbo.spring.server.DboTenants tenants, int port) {
        this.world = world;
        this.runtime = runtime;
        this.tenants = tenants;
        this.port = port;
    }

    /** The world this test declares, to change while it runs. */
    public TheWorldThisTestDeclares world() {
        return world;
    }

    /** Declares a tenant. The scan applies it, on its own clock. */
    public void declare(String code, String spec) {
        world.declare(code, spec);
    }

    /** Where a tenant's doors are, on the port this application came up on. */
    public String at(String tenant) {
        return "http://127.0.0.1:" + port + "/t/" + tenant;
    }

    /**
     * What a tenant says it serves.
     *
     * <p>Fetched every time rather than held: a statement is derived from
     * what the tenant declares, and a test that changes a declaration is
     * usually asking what changed about it.
     */
    public WhatATenantSaysItServes capability(String tenant) {
        HttpResponse<String> answered = get(at(tenant) + "/fhir/metadata", null);
        if (answered.statusCode() != 200) {
            throw new IllegalStateException(tenant + " did not answer for its capability "
                    + "statement: " + answered.statusCode() + " " + answered.body());
        }
        return new WhatATenantSaysItServes(tenant, answered.body());
    }

    /** A GET, with a bearer token or without one. */
    public HttpResponse<String> get(String url, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException notAnswered) {
            if (notAnswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no answer from " + url, notAnswered);
        }
    }

    /**
     * Waits for a tenant to be serving, or to have stopped.
     *
     * <p>A liveness wait rather than a budget: how long a tenant takes to come
     * up is a fact about the machine it is on, and a tighter number here would
     * only fail on the slower ones.
     */
    public boolean until(String tenant, boolean serving, Duration give) {
        long giveUp = System.nanoTime() + give.toNanos();
        while (System.nanoTime() < giveUp) {
            if (tenants.serving().contains(tenant) == serving) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** What this deployment is serving, as it sees itself. */
    public List<String> serving() {
        return tenants.serving();
    }

    /** Withdraws a tenant by no longer declaring it. */
    public boolean retract(String code) {
        return world.retract(code);
    }

    /** The tenant codes this test is declaring. */
    public List<String> declaring() {
        return world.declaring();
    }

    /**
     * Registers the source.
     *
     * <p><b>Whenever, and not first.</b> The activator tracks the service
     * rather than looking once, so a source arriving late starts the runtime
     * late and a source arriving never does not start it at all — the second
     * is the failure worth guarding, and no phase ordering protects against
     * it. What does is a test asserting that a tenant only this source
     * declares came up.
     */
    @Override
    public void start() {
        registered = runtime.registrar().register(ConfigSource.class, world, java.util.Map.of());
        running = true;
    }

    @Override
    public void stop() {
        if (registered != null) {
            registered.close();
            registered = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    static Path worldOf(String configured) {
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
