package cloud.jengu.dbo.spring.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One request, as the runtime's surfaces already expect to receive it.
 *
 * <p>Every surface a tenant offers is a {@code com.sun.net.httpserver}
 * handler: the records door, the tenant's own authority, provisioning, steps,
 * maintenance, erasure, content, the ops readouts. This is the whole of what
 * stands between them and a servlet container, which is why it exists at all
 * — the alternative was writing each of those surfaces a second time against
 * the servlet API, where the second one drifts from the first and the first
 * is not ours to freeze.
 *
 * <p><b>Nothing is buffered.</b> The records surface writes its response
 * headers on the first byte of the body, so that a search answering with a
 * large bundle does not hold it in memory to learn its length. An adapter
 * that collected the body to set a Content-Length would undo that
 * deliberately, for every read the store serves.
 */
final class ServletHttpExchange extends HttpExchange {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final HttpContext context;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();

    private InputStream body;
    private OutputStream out;
    private int status;

    ServletHttpExchange(HttpServletRequest request, HttpServletResponse response,
            HttpContext context) {
        this.request = request;
        this.response = response;
        this.context = context;
        for (String name : Collections.list(request.getHeaderNames())) {
            requestHeaders.put(name, Collections.list(request.getHeaders(name)));
        }
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * The path and query as the surfaces read them.
     *
     * <p>The request URI and not the servlet path: a surface is mounted at
     * {@code /t/{code}/fhir} and routes on what follows, so a path with the
     * application's context stripped out would have the tenant stripped out
     * with it.
     */
    @Override
    public URI getRequestURI() {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        try {
            return new URI(null, null, path, query, null);
        } catch (URISyntaxException notAnAddress) {
            // The container built this from bytes on a socket; if it will not
            // parse, answering 400 is the surfaces' business and not this
            // adapter's, so it travels as what it is.
            throw new IllegalArgumentException(path + " is not an address", notAnAddress);
        }
    }

    @Override
    public String getRequestMethod() {
        return request.getMethod();
    }

    @Override
    public HttpContext getHttpContext() {
        return context;
    }

    @Override
    public void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException alreadyGone) {
            // A client that hung up mid-answer. Nothing to do and nothing to
            // say: a teardown that logs like a crash is where a real crash
            // goes to hide.
        }
    }

    @Override
    public InputStream getRequestBody() {
        if (body == null) {
            try {
                body = request.getInputStream();
            } catch (IOException unreadable) {
                throw new java.io.UncheckedIOException("the request body could not be read",
                        unreadable);
            }
        }
        return body;
    }

    @Override
    public OutputStream getResponseBody() {
        if (out == null) {
            try {
                out = response.getOutputStream();
            } catch (IOException noStream) {
                throw new java.io.UncheckedIOException("the response could not be written",
                        noStream);
            }
        }
        return out;
    }

    /**
     * Commits the answer.
     *
     * @param length the JDK server's three cases, kept: a positive number is
     *               a fixed length, {@code -1} is no body at all, and
     *               {@code 0} means <i>as much as is written</i> — which is
     *               what a streamed bundle uses and what a Content-Length
     *               here would break
     */
    @Override
    public void sendResponseHeaders(int code, long length) {
        this.status = code;
        responseHeaders.forEach((name, values) -> values.forEach(
                value -> response.addHeader(name, value)));
        response.setStatus(code);
        if (length > 0) {
            response.setContentLengthLong(length);
        }
        // -1 is no body and 0 is a body of unknown length. Neither sets a
        // length, and neither is an error: the difference shows in whether
        // anything is written afterwards.
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return InetSocketAddress.createUnresolved(request.getRemoteAddr(),
                request.getRemotePort());
    }

    @Override
    public int getResponseCode() {
        return status;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return InetSocketAddress.createUnresolved(request.getLocalAddr(), request.getLocalPort());
    }

    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    /**
     * Not supported, and it is not a gap.
     *
     * <p>The JDK server offers this so a filter can wrap the streams. The
     * surfaces do not use it, and the filters an application has are its
     * container's — which run before any of this and are the right place for
     * them.
     */
    @Override
    public void setStreams(InputStream in, OutputStream outputStream) {
        throw new UnsupportedOperationException("a request served through a servlet container "
                + "is filtered by that container, before it reaches here");
    }

    /**
     * Nobody, always.
     *
     * <p>A {@code HttpPrincipal} is what the JDK server's own authenticator
     * would have set, and nothing here uses one: a tenant decides who is
     * asking from the bearer token, through its own authority, and a
     * principal the servlet container happened to establish is a second
     * answer to that question.
     */
    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    /** The header values the surfaces would have been given, for a reader. */
    List<String> requestHeader(String name) {
        return requestHeaders.getOrDefault(name, List.of());
    }
}
