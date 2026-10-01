package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.auth.TenantAuthority;
import cloud.jengu.dbo.spring.test.DboTestContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The doors a tenant opens for acts done for a person rather than to its
 * records: identifying somebody, deriving and resolving a pseudonym, erasing
 * somebody, and keeping content sealed to them.
 *
 * <p>Each door has a scope of its own, which a grant over the records does not
 * reach, so each is asked with a credential of its own. The credentials are
 * clients this story registers with the tenant's authority under its own
 * names, and the tokens come from the tenant's token endpoint the way any
 * client gets one.
 */
final class APersonsDoors {

    private final DboTestContext dbo;
    private final TenantAuthority authority;
    private final String tenant;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    APersonsDoors(DboTestContext dbo, TenantAuthority authority, String tenant) {
        this.dbo = dbo;
        this.authority = authority;
        this.tenant = tenant;
    }

    /** A credential for a client this story names, holding exactly these scopes. */
    String tokenFor(String client, String... scopes) {
        return tokens.computeIfAbsent(client + " " + String.join(" ", scopes), key -> {
            authority.ensureClient(client, secretOf(client), List.of(scopes));
            HttpResponse<String> issued = form("/oidc/token",
                    "grant_type=client_credentials&client_id=" + encoded(client)
                            + "&client_secret=" + encoded(secretOf(client)));
            assertEquals(200, issued.statusCode(), issued.body());
            return dbo.says(issued).one("access_token").orElseThrow(
                    () -> new AssertionError("no token in " + issued.body()));
        });
    }

    /** A JSON request to one of the tenant's doors, by its path under the tenant. */
    HttpResponse<String> post(String path, String json, String bearer) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), bearer);
    }

    /** A form post to the tenant's authority, carrying no bearer. */
    HttpResponse<String> form(String path, String form) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), null);
    }

    /** A form post carrying a bearer. */
    HttpResponse<String> form(String path, String form, String bearer) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), bearer);
    }

    /** A records read stating why it is made. */
    HttpResponse<String> readFor(String purpose, String typeAndId) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + "/fhir/" + typeAndId))
                .header("Purpose-Of-Use", purpose).GET(), dbo.token(tenant));
    }

    /** Content kept for a person; answers where the store put it. */
    HttpResponse<String> keep(byte[] content, String media, String person) {
        return dbo.send(HttpRequest.newBuilder(URI.create(dbo.at(tenant) + "/blob?person="
                        + encoded(person)))
                .header("Content-Type", media)
                .POST(HttpRequest.BodyPublishers.ofByteArray(content)), dbo.token(tenant));
    }

    /** Content read back as bytes, from the absolute path the store answered with. */
    HttpResponse<byte[]> fetch(String location) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(root() + location))
                .header("Authorization", "Bearer " + dbo.token(tenant)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException | InterruptedException notAnswered) {
            if (notAnswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("no answer from " + request.uri(), notAnswered);
        }
    }

    /** The same, as text, for an answer that is a refusal. */
    HttpResponse<String> fetchText(String location) {
        return dbo.get(root() + location, dbo.token(tenant));
    }

    /**
     * The server root. A location the store answers is an absolute path, and
     * joining it to the tenant's base would name the tenant twice.
     */
    private String root() {
        String base = dbo.at(tenant);
        return base.substring(0, base.indexOf("/t/"));
    }

    private static String secretOf(String client) {
        return client + "-secret";
    }

    static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
