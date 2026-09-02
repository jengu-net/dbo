package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.work.Executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A tenant's lane, held by a host that reaches the store over HTTP.
 *
 * <p>The answer to "where does a cloud get a {@code Lane}". The verbs are
 * {@link WireLane}'s; this is the carrier — one POST per verb against
 * {@link LaneHandler} on the tenant's private surface — and a runner cannot
 * tell it from the in-process one, which is the contract {@code Lane} states.
 *
 * <p><b>The token is fetched per call, never held.</b> A lane outlives an
 * access token, and a host that captured one would start failing after an
 * hour in a way that looks like the store going away.
 */
public final class HttpLane extends WireLane {

    public static HttpLane to(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity) {
        return new HttpLane(base, bearer, tenant, participant, identity, null,
                HttpClient.newHttpClient());
    }

    /** A lane bounded at construction to the steps named, whatever the credential could reach. */
    public static HttpLane boundedTo(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity, Set<String> steps) {
        return new HttpLane(base, bearer, tenant, participant, identity, Set.copyOf(steps),
                HttpClient.newHttpClient());
    }

    /**
     * A lane for a participant that offered its keys at enrolment and holds
     * the private halves here. Its inputs arrive sealed, are opened on this
     * side, and each opening is signed and reported home before the document
     * is handed on — the two are one act, and neither is optional.
     */
    public static HttpLane holding(URI base, Supplier<String> bearer, String tenant,
            String participant, Executor identity, java.security.PrivateKey privateKey,
            java.security.PrivateKey signingKey) {
        return new HttpLane(base, bearer, tenant, participant, identity, null,
                HttpClient.newHttpClient(), privateKey, signingKey);
    }

    public HttpLane(URI base, Supplier<String> bearer, String tenant, String participant,
            Executor identity, Set<String> boundTo, HttpClient http) {
        this(base, bearer, tenant, participant, identity, boundTo, http, null, null);
    }

    public HttpLane(URI base, Supplier<String> bearer, String tenant, String participant,
            Executor identity, Set<String> boundTo, HttpClient http,
            java.security.PrivateKey holding, java.security.PrivateKey signing) {
        super(new Http(base, bearer, tenant, http), tenant, participant, identity, boundTo,
                holding, signing);
    }

    /** One POST per verb. A link that is simply down is not a refusal, and says so differently. */
    private record Http(URI base, Supplier<String> bearer, String tenant, HttpClient http)
            implements Transport {

        @Override
        public Reply post(LaneVerbs verb, String body) {
            URI target = base.resolve(base.getPath().endsWith("/")
                    ? verb.path() : base.getPath() + "/" + verb.path());
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String token = bearer.get();
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            try {
                HttpResponse<String> response =
                        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Reply(response.statusCode(), response.body());
            } catch (java.io.IOException unreachable) {
                // Not a decision about the caller: the far side never said
                // anything, so this is retryable and must not read as a
                // refusal (§7.9).
                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                        tenant + ": the lane surface did not answer '" + verb.path() + "'",
                        unreachable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new cloud.jengu.dbo.core.api.StoreUnreachableException(
                        tenant + ": interrupted on '" + verb.path() + "'", interrupted);
            }
        }
    }
}
