package cloud.jengu.dbo.spring.worker;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A credential that is obtained rather than configured.
 *
 * <p>The lane takes a {@link Supplier} and not a string, and the sample says
 * why in one line: a runner outlives an access token, and one captured at
 * construction starts failing an hour later in a way that reads like the
 * store going away. So this signs in at the tenant's own authority, keeps
 * what it got, and signs in again before it expires.
 *
 * <p><b>Early, by a margin.</b> A token renewed at the moment it expires is
 * renewed after it expired on whichever request was already in flight. The
 * margin is a minute, which is longer than any clock this has to agree with
 * is wrong by.
 *
 * <p>No library, for the reason the rest of this store has none: the whole of
 * the exchange is one form post and one field out of the answer.
 */
final class ClientCredentials implements Supplier<String> {

    private static final Duration EARLY = Duration.ofMinutes(1);

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI tokenEndpoint;
    private final String tenant;
    private final String clientId;
    private final String clientSecret;

    private volatile String token;
    private volatile Instant renewAt = Instant.MIN;

    ClientCredentials(URI tenantBase, String tenant, String clientId, String clientSecret) {
        // The tenant's own authority, beside its other doors. Derived rather
        // than configured: an application that had to be told where the token
        // endpoint is could be told a different tenant's.
        this.tokenEndpoint = tenantBase.resolve("oidc/token");
        this.tenant = tenant;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String get() {
        String held = token;
        if (held != null && Instant.now().isBefore(renewAt)) {
            return held;
        }
        return signIn();
    }

    private synchronized String signIn() {
        // Checked again under the lock: several lanes waking together would
        // otherwise each sign in, and the tenant would see a burst of
        // credentials for one worker.
        if (token != null && Instant.now().isBefore(renewAt)) {
            return token;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + encoded(clientId)
                + "&client_secret=" + encoded(clientSecret);
        HttpResponse<String> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(tokenEndpoint)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException didNotComplete) {
            if (didNotComplete instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("could not reach " + tenant + " to sign in at "
                    + tokenEndpoint, didNotComplete);
        }
        if (answer.statusCode() != 200) {
            // The status and not the body: a refusal from an authority can
            // carry the client id back, and a log line is not the place for a
            // credential even by accident.
            throw new IllegalStateException(tenant + " refused this worker's credential: "
                    + answer.statusCode() + " from " + tokenEndpoint);
        }
        token = field(answer.body(), "access_token");
        renewAt = Instant.now().plusSeconds(Math.max(0, seconds(answer.body()) - EARLY.toSeconds()));
        return token;
    }

    private static long seconds(String json) {
        int at = json.indexOf("\"expires_in\"");
        if (at < 0) {
            // An authority that did not say. A minute is short enough that
            // nothing is used past its life and long enough not to be a poll.
            return 2 * EARLY.toSeconds();
        }
        int from = json.indexOf(':', at) + 1;
        int to = from;
        while (to < json.length() && (Character.isDigit(json.charAt(to))
                || Character.isWhitespace(json.charAt(to)))) {
            to++;
        }
        try {
            return Long.parseLong(json.substring(from, to).trim());
        } catch (NumberFormatException notANumber) {
            return 2 * EARLY.toSeconds();
        }
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\"");
        if (at < 0) {
            throw new IllegalStateException("the authority answered without a " + name);
        }
        int from = json.indexOf('"', json.indexOf(':', at)) + 1;
        return json.substring(from, json.indexOf('"', from));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
