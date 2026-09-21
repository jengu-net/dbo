package cloud.jengu.dbo.sample;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * One tenant's front door, in the words an actor would use.
 *
 * <p>A method here is something somebody DOES: sign in, write a record, read
 * one, look for some, change one, ask for one to be forgotten. There is no
 * method for anything the store does in reaction — no "write a trail entry",
 * no "derive the envelope", no "commit the change event" — because nobody
 * calls those. They happen because a write happened, and a story reads them
 * afterwards from what the store holds.
 *
 * <p>That is why this stays small while the promise catalogue grows. Most
 * promises are consequences, and a consequence is read, never invoked.
 *
 * <p><b>It speaks HTTP because an external actor does.</b> The store's own
 * API is a different thing, reachable only from inside the deployment; a
 * product integrating with a tenant it does not run has a URL and a token.
 * So this is the code somebody actually writes, and a story over it cannot
 * quietly take a shortcut no integrator has.
 */
public final class Surface {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final URI base;
    private final String tenant;
    private String bearer;

    Surface(URI base, String tenant) {
        this.base = base;
        this.tenant = tenant;
    }

    /** Which tenant this is a door to. */
    public String tenant() {
        return tenant;
    }

    /**
     * Sign in as a client this tenant holds, and keep the token for what
     * follows.
     *
     * <p>Returns this, so a scene opens on one line: the signing in and the
     * first thing done are one sentence in the story they belong to.
     */
    public Surface signIn(String client, String secret) {
        String form = "grant_type=client_credentials&client_id=" + encode(client)
                + "&client_secret=" + encode(secret);
        Answer got = send("POST", base.resolve("/t/" + tenant + "/oidc/token"),
                "application/x-www-form-urlencoded", form);
        if (got.status() != 200) {
            throw new IllegalStateException("could not sign in to " + tenant
                    + " as " + client + ": " + got.status() + " " + got.body());
        }
        this.bearer = field(got.body(), "access_token");
        return this;
    }

    /** The token this door is holding, for the parts that need to carry it. */
    public String token() {
        if (bearer == null) {
            throw new IllegalStateException("nobody has signed in to " + tenant + " yet");
        }
        return bearer;
    }

    /** Write a record, and answer where it went. */
    public Answer write(String type, String json) {
        return fhir("POST", "/" + type, json);
    }

    /** Read one back by the id the store gave it. */
    public Answer read(String type, String id) {
        return fhir("GET", "/" + type + "/" + id, null);
    }

    /** Look for some. The query is what a reader would type after the `?`. */
    public Answer search(String type, String query) {
        return fhir("GET", "/" + type + "?" + query, null);
    }

    /** Change one that is already here. */
    public Answer change(String type, String id, String json) {
        return fhir("PUT", "/" + type + "/" + id, json);
    }

    /** Ask for one to be gone. */
    public Answer forget(String type, String id) {
        return fhir("DELETE", "/" + type + "/" + id, null);
    }

    /** Anything else the surface serves, for a scene this vocabulary has not reached yet. */
    public Answer fhir(String method, String pathAndQuery, String json) {
        return send(method, base.resolve("/t/" + tenant + "/fhir" + pathAndQuery),
                "application/fhir+json", json);
    }

    private Answer send(String method, URI uri, String contentType, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", contentType);
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> answer = HTTP.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Answer(answer.statusCode(), answer.body(),
                    answer.headers().firstValue("Location").orElse(null),
                    answer.headers().firstValue("ETag").orElse(null));
        } catch (IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(method + " " + uri + " did not complete", failed);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\"");
        if (at < 0) {
            throw new IllegalStateException("no " + name + " in " + json);
        }
        int from = json.indexOf('"', json.indexOf(':', at)) + 1;
        return json.substring(from, json.indexOf('"', from));
    }
}
