package cloud.jengu.dbo.spring.test;

import cloud.jengu.dbo.asking.Questions;
import cloud.jengu.dbo.auth.TenantAuthority;
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
    private final org.springframework.beans.factory.ListableBeanFactory beans;
    private final java.util.Map<String, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();
    private DboRegistrar.Registration registered;
    private volatile boolean running;

    DboTestContext(TheWorldThisTestDeclares world, EmbeddedRuntime runtime,
            cloud.jengu.dbo.spring.server.DboTenants tenants, int port,
            org.springframework.beans.factory.ListableBeanFactory beans) {
        this.world = world;
        this.runtime = runtime;
        this.tenants = tenants;
        this.port = port;
        this.beans = beans;
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

    /**
     * The questions this tenant answers, handed back rather than wrapped.
     *
     * <p>{@code Questions} is already the vocabulary a product asks in — work
     * that is open, records of a type, the trail — so a second one over it
     * would be a second way to say the same thing, and the two would drift.
     * What a test gains here is not an API but the tenant: reaching this
     * otherwise means a registry lookup with a filter.
     */
    public Questions asking(String tenant) {
        return tenants.asking(tenant).orElseThrow(
                () -> new IllegalStateException("nothing answers questions for " + tenant
                        + ", which is either a tenant that is not serving or one this "
                        + "deployment does not have: " + tenants.serving()));
    }

    /**
     * A credential this tenant issued, obtained the way an integrator would.
     *
     * <p>Minted through the tenant's own authority rather than signed here: a
     * test carrying a token the tenant would not have issued proves the door
     * accepts something, and says nothing about what the door is for. The
     * client is the same one the worker lane carries, so a test and a worker
     * in one context are the same participant.
     *
     * <p>Cached per tenant, because {@code ensureClient} is idempotent but a
     * token is not free and a test asking per request would spend most of its
     * time at the authority.
     */
    public String token(String tenant) {
        return tokens.computeIfAbsent(tenant, code -> {
            TenantAuthority authority = tenants.authority(code).orElseThrow(
                    () -> new IllegalStateException(code + " has no authority, so nothing can "
                            + "issue a credential for it. It is either not serving yet or not "
                            + "declared: " + tenants.serving()));
            authority.ensureClient(TheTenantIsServing.CLIENT, TheTenantIsServing.SECRET,
                    List.of("system/*.read", "system/*.write"));
            TenantAuthority.TokenResult issued = authority.token(
                    TheTenantIsServing.CLIENT, TheTenantIsServing.SECRET, null);
            if (issued instanceof TenantAuthority.TokenResult.Issued minted) {
                return minted.accessToken();
            }
            throw new IllegalStateException(code + " would not issue a token to a client it had "
                    + "just registered: " + issued);
        });
    }

    /**
     * Writes a document to a tenant, carrying a credential it issued.
     *
     * <p>What comes back knows its own id, because a test that writes a record
     * almost always reads it back or searches for it — and the id is on the
     * {@code Location} header the create answered with rather than in a body
     * that may be a shell.
     */
    public WhatTheStoreStored write(String tenant, String typeName, String document) {
        return new WhatTheStoreStored(
                send(HttpRequest.newBuilder(URI.create(at(tenant) + "/fhir/" + typeName))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(document)), token(tenant)));
    }

    /** Reads one back. */
    public HttpResponse<String> read(String tenant, String typeName, String id) {
        return send(HttpRequest.newBuilder(URI.create(
                at(tenant) + "/fhir/" + typeName + "/" + id)).GET(), token(tenant));
    }

    /**
     * Searches, with the query as it would be typed.
     *
     * @param query as it would be typed — {@code identifier=urn:rl:nid|RL-1}.
     *              The pipe is how FHIR separates a system from a value and is
     *              illegal in a URI, so the query is escaped HERE rather than
     *              by every caller: a helper that made the caller pre-escape
     *              would be a helper nobody could use for the search this
     *              store is most often asked to run
     */
    public HttpResponse<String> search(String tenant, String typeName, String query) {
        return search(tenant, typeName, query, null);
    }

    /**
     * The same, saying what the access is for.
     *
     * <p><b>An identifying search needs a purpose and is refused without
     * one.</b> Searching by identifier is an access to a person, and this
     * store will not match on it unnamed — nor return an empty page, which
     * would read as an answer. So a test searching a tenant that holds people
     * states an HL7 PurposeOfUse code the way a caller does, in the header,
     * and a test that forgets gets a 403 saying so rather than nothing found.
     *
     * @param purpose {@code TREAT}, {@code PATRQT} and the rest, or null where
     *                the search reaches no identifying element
     */
    public HttpResponse<String> search(String tenant, String typeName, String query,
            String purpose) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                uri("/t/" + tenant + "/fhir/" + typeName, query)).GET();
        if (purpose != null) {
            request.header("Purpose-Of-Use", purpose);
        }
        return send(request, token(tenant));
    }

    /**
     * A URI with its query escaped where it has to be.
     *
     * <p>The multi-argument constructor quotes what is illegal and leaves what
     * is reserved, so {@code |} becomes {@code %7C} while {@code =} and
     * {@code &} go through as themselves — which is what separates escaping a
     * query from rewriting one.
     */
    private URI uri(String path, String query) {
        try {
            return new URI("http", null, "127.0.0.1", port, path, query, null);
        } catch (java.net.URISyntaxException notAUri) {
            throw new IllegalArgumentException("not a query this can be asked with: " + query,
                    notAUri);
        }
    }

    /** A GET, with a bearer token or without one. */
    public HttpResponse<String> get(String url, String token) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpRequest built = request.build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(built, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException notAnswered) {
            if (notAnswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no answer from " + built.uri(), notAnswered);
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


    // ------------------------------------------------------------ the worker

    /**
     * The worker in this context, where the application has one.
     *
     * <p>Looked up by name rather than by type, because this module does not
     * depend on the worker assembly: a serving application that never performs
     * work should not be made to carry it, and an application that does has it
     * on its own classpath. Absent, every method below says so rather than
     * pretending there is nothing to perform.
     */
    private SmartLifecycle theWorker() {
        return beans.getBeansOfType(SmartLifecycle.class).values().stream()
                .filter(bean -> bean.getClass().getName()
                        .equals("cloud.jengu.dbo.spring.worker.DboWorker"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("this application performs no "
                        + "work: nothing on its classpath is a DBO worker, so there is no "
                        + "runner to start, stop or ask. Adding dbo-spring-boot-worker and a "
                        + "bean implementing StepService is what makes one"));
    }

    /**
     * Starts the runner asking its lanes for work.
     *
     * <p>A test declares its step services as beans, the way an application
     * does, and the container's whiteboard finds them. What is left for a test
     * to decide is WHEN the asking begins — which matters because a runner
     * polling a lane before its tenant is serving fails every cycle into a log
     * nobody is reading.
     */
    public void startWorking() {
        theWorker().start();
    }

    /** Stops it asking. Work already taken is finished, not abandoned. */
    public void stopWorking() {
        theWorker().stop();
    }

    public boolean working() {
        return theWorker().isRunning();
    }

    /**
     * What this application performs, as the worker sees it.
     *
     * <p>The assertion a test about a step service actually wants: not that a
     * bean exists — it wrote the bean — but that the container found it and
     * something will hand it work.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, String> performing() {
        return (java.util.Map<String, String>) call(theWorker(), "performing");
    }

    /** The tenants it performs for. */
    @SuppressWarnings("unchecked")
    public List<String> workingFor() {
        return (List<String>) call(theWorker(), "lanes");
    }

    /**
     * Asked by name, because the worker's type is not on this module's
     * classpath and putting it there would make every serving application
     * carry a runner it does not have.
     */
    private static Object call(Object worker, String method) {
        try {
            return worker.getClass().getMethod(method).invoke(worker);
        } catch (ReflectiveOperationException notThere) {
            throw new IllegalStateException("the worker in this application does not answer "
                    + method + "(), so it is not the one this was written against", notThere);
        }
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
