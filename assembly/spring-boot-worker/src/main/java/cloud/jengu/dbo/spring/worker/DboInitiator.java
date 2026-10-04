package cloud.jengu.dbo.spring.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The other half of being a participant: asking for work, not only doing it.
 *
 * <p>A participant is usually described as something that polls a lane and
 * performs what it is handed, and that is half of it. The same application, on
 * the same credential, can <b>author</b> a run — which is how work comes to
 * exist at all, since this store has no orchestrator and nothing schedules
 * anything on a tenant's behalf. A run exists because somebody asked for one.
 *
 * <p>Which makes an external participant a peer rather than a subordinate: it
 * is not told what to do by the deployment, it is one of the things that can
 * say what should be done. Whether it then performs that step itself, or
 * whether a bean somewhere in the deployment does, is not its business — the
 * run is the tenant's, and who takes it is decided by what is entitled to.
 *
 * <p><b>It asks the tenant, always.</b> A step the deployment performs for
 * every tenant is still started on the tenant's own door, because the run
 * belongs to the tenant it is about and a run authored anywhere else would be
 * a run with no owner. That is also why this needs no notion of the fleet: it
 * posts a step code to a tenant, and which level declared that step is the
 * store's business rather than this application's.
 *
 * <p><b>And it hears back.</b> The run answers the application that asked for
 * it, at the run's own address: how it stands, what it was over, and what the
 * step produced — and nothing more. The credential that asks for work holds no
 * door onto the tenant's records, and does not need one to learn how its work
 * ended.
 *
 * <p><b>And, where the step declared it, it collects.</b> A step that answers
 * gives the application that asked a window once the work is over, in which
 * it reads what the run was given and each version the run produced — as the
 * audience the step names, so as much as the tenant declared that audience
 * sees and no more. A person is revealed whole only when this application
 * states the purpose the step declared. Each collection is on the tenant's
 * trail as a reading, and the window shuts by the clock or when this
 * application says it has collected.
 *
 * <p>The Spring binding of {@link cloud.jengu.dbo.work.RunInitiator}: what a
 * plain bundle in the container starts runs with, this application starts
 * them with over the tenant's door, and the answers read the same.
 */
public final class DboInitiator implements cloud.jengu.dbo.work.RunInitiator {

    private static final Logger LOG = LoggerFactory.getLogger("dbo.worker");

    private final DboWorkerProperties properties;
    private final Map<String, Supplier<String>> tokens;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    DboInitiator(DboWorkerProperties properties, Map<String, Supplier<String>> tokens) {
        this.properties = properties;
        this.tokens = Map.copyOf(tokens);
    }

    /**
     * Asks a tenant to start a run of a step, over its own door, on the work
     * credential this application holds for that tenant.
     *
     * <p>What each slot may hold is the step's to declare and this does not
     * check it. The tenant's door does, against the declaration, and answers
     * 400 naming the slot and what it takes — which is a better place for the
     * rule than a copy of it here that could disagree.
     *
     * @param inputs a {@link Slot} per slot the step declares
     * @param key    the run's key within its step, which finds a run already
     *               started under it rather than starting another; null for
     *               one of its own
     */
    @Override
    public Started starting(String tenant, String step, Map<String, Slot> inputs,
            String key) {
        DboWorkerProperties.Lane lane = askable(tenant);
        Supplier<String> token = tokens.get(tenant);
        StringBuilder named = new StringBuilder();
        inputs.forEach((slot, filled) -> {
            if (named.length() > 0) {
                named.append(',');
            }
            named.append(quote(slot)).append(':').append(filled.rendered());
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(lane.getBase().toString().replaceAll("/+$", "")
                                + "/step/" + step))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{" + named + "}"
                        + (key == null ? "" : ",\"scope\":" + quote(key)) + "}"));
        if (token != null) {
            // THE WORK CREDENTIAL, the same one the lane polls with. A
            // participant that may take work of a step may ask for work of it;
            // there is no second credential and no second enrolment.
            request.header("Authorization", "Bearer " + token.get());
        }
        try {
            HttpResponse<String> answered =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String body = answered.body();
            Started started = new Started(answered.statusCode(),
                    cloud.jengu.dbo.work.RunInitiator.field(body, "run"),
                    cloud.jengu.dbo.work.RunInitiator.field(body, "key"), body);
            if (!started.accepted()) {
                // Said once, by name. A run that was not started is the
                // difference between "nothing to do" and "we were refused",
                // and those look identical from everywhere else.
                LOG.warn("a run was not started: tenant={} step={} status={}",
                        tenant, step, answered.statusCode());
            }
            return started;
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("asking '" + tenant + "' for a run of '" + step
                    + "' did not complete", failed);
        }
    }

    /**
     * Asks a run how it stands.
     *
     * <p>Only the run this application asked for answers it, and on the
     * credential it asked with. A run somebody else started answers 404, the
     * same as one that does not exist — so this cannot be used to find out
     * what work a tenant has.
     *
     * @param tenant the tenant the run was started on
     * @param run    the run's id, as {@link Started#run()} gave it
     */
    @Override
    public Answer answer(String tenant, String run) {
        DboWorkerProperties.Lane lane = askable(tenant);
        Supplier<String> token = tokens.get(tenant);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(lane.getBase().toString().replaceAll("/+$", "")
                                + "/run/" + run))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token.get());
        }
        try {
            HttpResponse<String> answered =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(answered.statusCode(), answered.body());
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("asking '" + tenant + "' about run '" + run
                    + "' did not complete", failed);
        }
    }

    /**
     * One record, collected through a run.
     *
     * @param status the HTTP status: 200 with the record as the step's
     *               audience sees it, 410 for a version that removed it, and
     *               404 for anything the run did not give or produce, a run
     *               whose window has shut, and a run this application did not
     *               ask for — the last three deliberately the same answer
     * @param body   the record on a 200, the refusal otherwise
     */
    public record Collected(int status, String body) {

        /** Whether the record was collected. */
        public boolean found() {
            return status == 200;
        }

        /** The record, or an exception saying why there is none. */
        public String recordOrFail() {
            if (!found()) {
                throw new IllegalStateException("nothing was collected: " + status + " " + body);
            }
            return body;
        }
    }

    /**
     * Collects one record through a run this application asked for, once its
     * work is over and while its window is open.
     *
     * <p>What the run was given is named {@code Type/id}; what it produced is
     * named as the version it produced, {@code Type/id/_history/n}, exactly as
     * the run's answer lists it — a later version of the record is not what
     * the run did. What comes back is the record as the audience the step
     * names sees it: for a person, the strict mode, which is everything but
     * what identifies her.
     *
     * @param tenant    the tenant the run was started on
     * @param run       the run's id, as {@link Started#run()} gave it
     * @param reference the record, as the run names it
     */
    public Collected collect(String tenant, String run, String reference) {
        return collecting(tenant, run, reference, null);
    }

    /**
     * The same, stating why this application reads a person.
     *
     * <p>The second key. A step whose audience may see a person whole
     * declared the purpose it reads her for, and she is revealed only when
     * the collecting request states that same code. Another code, or none,
     * is answered in the strict mode rather than refused — so stating a
     * purpose cannot be used to learn whether a record would have been worth
     * refusing.
     *
     * @param purpose the PurposeOfUse code — {@code TREAT}, say — or null
     */
    public Collected collecting(String tenant, String run, String reference, String purpose) {
        HttpRequest.Builder request = toTheRun(tenant, run,
                "/fhir/" + reference.replaceAll("^/+", "")).GET();
        if (purpose != null) {
            request.header("Purpose-Of-Use", purpose);
        }
        try {
            HttpResponse<String> answered =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Collected(answered.statusCode(), answered.body());
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("collecting '" + reference + "' through run '" + run
                    + "' on '" + tenant + "' did not complete", failed);
        }
    }

    /**
     * Says this application has collected what it wanted, which shuts its
     * window now rather than when the step said it would.
     *
     * <p>Said once the run's answer has settled. It is the run's one
     * {@code done}, and on a run this application started and is still
     * performing itself, it ends the work rather than the window.
     *
     * @return whether a window was open to shut — false for one already shut,
     *         or a run this application cannot collect from, which are the
     *         same answer
     */
    public boolean collected(String tenant, String run) {
        HttpRequest request = toTheRun(tenant, run, "/done")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (java.io.IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("saying run '" + run + "' on '" + tenant
                    + "' was collected did not complete", failed);
        }
    }

    /** A request to a run's own address, on the credential that asked for it. */
    private HttpRequest.Builder toTheRun(String tenant, String run, String path) {
        DboWorkerProperties.Lane lane = askable(tenant);
        Supplier<String> token = tokens.get(tenant);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(lane.getBase().toString().replaceAll("/+$", "") + "/run/" + run
                        + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token.get());
        }
        return request;
    }

    /** The HTTP lane into a tenant, which is where work is asked for and answered. */
    private DboWorkerProperties.Lane askable(String tenant) {
        DboWorkerProperties.Lane lane = properties.getLanes().stream()
                .filter(declared -> declared.getTenant().equals(tenant))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("this application holds no lane "
                        + "into '" + tenant + "', so it has no address to ask and no credential "
                        + "to ask with; declare the lane under dbo.worker.lanes"));
        if (lane.getBase() == null) {
            // The substrate carries claims and reports, not this. A lane over
            // it is held by an enrolled participant with no route into the
            // tenant at all, which is the whole point of that plane — so the
            // honest answer is that there is nowhere to post, rather than a
            // request built against a base URI that lane never had.
            throw new IllegalArgumentException("the lane into '" + tenant + "' is over the "
                    + "substrate, which carries work already authored and offers no way to "
                    + "author any or to ask after it: a participant that starts runs names the "
                    + "tenant's base and a client to ask with, and says carrier: substrate");
        }
        return lane;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
