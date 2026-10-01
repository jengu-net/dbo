package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.spring.test.DboTestContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * One tenant's records door, for the requests a story makes beyond reading,
 * writing and searching.
 *
 * <p>A conditional create, an update against a version, a delete, a
 * transaction: the requests an integrator sends, with the headers they send
 * them with. Built on {@link DboTestContext#send}, so the credential is the
 * one the tenant issued and the address is the application's own port.
 */
final class ATenantsDoor {

    private static final String FHIR_JSON = "application/fhir+json";

    private final DboTestContext dbo;
    private final String tenant;

    ATenantsDoor(DboTestContext dbo, String tenant) {
        this.dbo = dbo;
        this.tenant = tenant;
    }

    /** Where the door is, with a path after it: {@code /Patient/123}, or "" for the base. */
    String at(String path) {
        return dbo.at(tenant) + "/fhir" + path;
    }

    HttpResponse<String> get(String path) {
        return dbo.send(HttpRequest.newBuilder(URI.create(at(path))).GET(), dbo.token(tenant));
    }

    HttpResponse<String> post(String path, String document, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(at(path)))
                .header("Content-Type", FHIR_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(document));
        return dbo.send(withHeaders(request, headers), dbo.token(tenant));
    }

    /** The same, carrying a credential other than the tenant's own client's. */
    HttpResponse<String> postAs(String path, String document, String bearer) {
        return dbo.send(HttpRequest.newBuilder(URI.create(at(path)))
                .header("Content-Type", FHIR_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(document)), bearer);
    }

    HttpResponse<String> put(String path, String document, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(at(path)))
                .header("Content-Type", FHIR_JSON)
                .PUT(HttpRequest.BodyPublishers.ofString(document));
        return dbo.send(withHeaders(request, headers), dbo.token(tenant));
    }

    HttpResponse<String> delete(String path) {
        return dbo.send(HttpRequest.newBuilder(URI.create(at(path))).DELETE(),
                dbo.token(tenant));
    }

    /** Header names and values, alternating: {@code "If-Match", "W/\"1\""}. */
    private static HttpRequest.Builder withHeaders(HttpRequest.Builder request,
            String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers come in name and value pairs");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return request;
    }
}
