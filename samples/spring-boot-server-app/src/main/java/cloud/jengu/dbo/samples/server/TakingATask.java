package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.asking.Across;
import cloud.jengu.dbo.asking.Ongoing;
import cloud.jengu.dbo.work.Awaits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * A nurse at a ward screen: the tasks waiting for a person, one taken, read
 * and finished.
 *
 * <p><b>As the nurse, on the nurse's own token.</b> The clinic signs its staff
 * in through its own identity provider, and the token it issues names the
 * practitioner and carries what their role grants. Taking a task with it is
 * the nurse taking it — the run names the role they hold, and every reading
 * they make through it names them on the trail — rather than this
 * application saying who was at the screen. So nothing here holds a
 * credential; each call carries the one the screen was opened with.
 *
 * <p>The list is the store's asking vocabulary over the tenant's door, the
 * same as the ward's counts. Taking, reading and finishing are the run's own
 * address.
 */
// --8<-- [start:taking]
@Component
public final class TakingATask {

    private final String root;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    TakingATask(@Value("${server.port:8080}") int port) {
        this.root = "http://127.0.0.1:" + port + "/t/";
    }

    /**
     * What waits for a person at one step: ready, and open to people alone —
     * what automation was never given, or gave back.
     *
     * @param step the step's code, {@code <module>.<process>.<step>}
     */
    public List<Ongoing> waiting(String tenant, String bearer, String step) {
        try (var waiting = Across.through(pathAndQuery -> send("GET",
                        root + tenant + "/fhir" + pathAndQuery, bearer).body())
                .work().ofStep(step.substring(step.lastIndexOf('.') + 1))
                .awaiting(Awaits.PERSON).stream()) {
            return waiting.toList();
        }
    }

    /** The nurse takes it: the run is theirs, as the role they hold, until they finish. */
    public HttpResponse<String> take(String tenant, String bearer, String run) {
        return send("POST", root + tenant + "/run/" + run + "/claim", bearer);
    }

    /** One of the documents the task was given, read through the task. */
    public HttpResponse<String> read(String tenant, String bearer, String run, String document) {
        return send("GET", root + tenant + "/run/" + run + "/fhir/" + document, bearer);
    }

    /** Done: the task is completed, and its documents close with it. */
    public HttpResponse<String> finish(String tenant, String bearer, String run) {
        return send("POST", root + tenant + "/run/" + run + "/done", bearer);
    }
    // --8<-- [end:taking]

    private HttpResponse<String> send(String method, String url, String bearer) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/fhir+json")
                .header("Authorization", "Bearer " + bearer)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException unanswered) {
            if (unanswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no answer from " + url, unanswered);
        }
    }
}
