package cloud.jengu.dbo.samples.server;

import cloud.jengu.dbo.asking.Across;
import cloud.jengu.dbo.asking.Questions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * The questions the clinic's screens ask, in the store's asking vocabulary.
 *
 * <p>Each is a sentence a ward clerk says — how many of these, what is still
 * open on this case, who touched this record — and a method rather than a
 * search this code composes. A vocabulary that answered them by handing out a
 * query builder would be the store's engine with a longer name.
 *
 * <p><b>Asked over the tenant's door, as whoever is looking.</b> This
 * application holds the store in-process and does not ask it there: inside,
 * a question is asked as the system, and what the tenant's authority would
 * have said about the credential, its scopes and why it asked would never be
 * heard. So the vocabulary is bound to the tenant's own surface, and each
 * question carries the credential of the person or process the screen is
 * for — which is also why nothing here holds one.
 */
@Component
public final class CountingTheWard {

    private final String root;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    CountingTheWard(@Value("${server.port:8080}") int port) {
        // This application's own port, which is where its tenants' doors are.
        this.root = "http://127.0.0.1:" + port + "/t/";
    }

    // --8<-- [start:questions]
    /** How many records of a type carry this code. A number, not the rows behind it. */
    public long howMany(String tenant, String bearer, String type, String system, String code) {
        return asking(tenant, bearer).records(type).whereCoded("code", system, code).count();
    }

    /** How much of one case is still open: taken and not finished, or waiting for somebody. */
    public long stillOpen(String tenant, String bearer, String correlation) {
        return asking(tenant, bearer).work().correlated(correlation).open().count();
    }

    /** Who has touched a record, as the trail says it, one entry each. */
    public List<String> whoTouched(String tenant, String bearer, String type, String id) {
        try (var entries = asking(tenant, bearer).trail().about(type, id).stream()) {
            return entries.map(entry -> new String(entry.payload(), StandardCharsets.UTF_8))
                    .toList();
        }
    }

    /**
     * The vocabulary, bound to one tenant's door and one credential.
     *
     * <p>The door is one line: a GET this application already knows how to
     * make, handed to the vocabulary.
     */
    private Questions asking(String tenant, String bearer) {
        return Across.through(pathAndQuery -> get(root + tenant + "/fhir" + pathAndQuery,
                bearer));
    }
    // --8<-- [end:questions]

    private String get(String url, String bearer) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/fhir+json")
                .header("Authorization", "Bearer " + bearer).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException unanswered) {
            if (unanswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no answer from " + url, unanswered);
        }
    }
}
