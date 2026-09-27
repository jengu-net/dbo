package cloud.jengu.dbo.spring.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 */
public final class DboInitiator {

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

    /** What the tenant answered, and the run it named if it started one. */
    public record Started(int status, String run, String key, String body) {

        /** 201: asking for a run creates one. */
        public boolean accepted() {
            return status == 201;
        }

        public String runOrFail() {
            if (!accepted()) {
                throw new IllegalStateException("no run was started: " + status + " " + body);
            }
            return run;
        }
    }

    /**
     * Asks a tenant to start a run of a step, over the documents named.
     *
     * @param tenant the tenant the work is about, which must be one this
     *               application holds an HTTP lane into
     * @param step   the step code, whether the tenant declared it or the
     *               deployment did
     * @param inputs a reference per slot the step declares, as
     *               {@code slot -> "Type/id"}
     */
    public Started start(String tenant, String step, Map<String, String> inputs) {
        DboWorkerProperties.Lane lane = properties.getLanes().stream()
                .filter(declared -> declared.getTenant().equals(tenant))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("this application holds no lane "
                        + "into '" + tenant + "', so it has no address to ask and no credential "
                        + "to ask with; declare the lane under dbo.worker.lanes"));
        if (lane.overTheSubstrate()) {
            // The substrate carries claims and reports, not this. A lane over
            // it is held by an enrolled participant with no route into the
            // tenant at all, which is the whole point of that plane — so the
            // honest answer is that there is nowhere to post, rather than a
            // request built against a base URI that lane never had.
            throw new IllegalArgumentException("the lane into '" + tenant + "' is over the "
                    + "substrate, which carries work already authored and offers no way to "
                    + "author any: a participant that starts runs holds an HTTP lane");
        }
        Supplier<String> token = tokens.get(tenant);
        StringBuilder named = new StringBuilder();
        inputs.forEach((slot, reference) -> {
            if (named.length() > 0) {
                named.append(',');
            }
            named.append(quote(slot)).append(':').append(quote(reference));
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(lane.getBase().toString().replaceAll("/+$", "")
                                + "/step/" + step))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{" + named + "}}"));
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
                    valueOf(body, "run"), valueOf(body, "key"), body);
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

    /** The one field, without a JSON parser this module does not have. */
    private static String valueOf(String body, String field) {
        String at = "\"" + field + "\":\"";
        int start = body == null ? -1 : body.indexOf(at);
        if (start < 0) {
            return null;
        }
        int from = start + at.length();
        int end = body.indexOf('"', from);
        return end < 0 ? null : body.substring(from, end);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
