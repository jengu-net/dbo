package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Audience;
import cloud.jengu.dbo.core.api.BlobStore;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.core.api.Reach;
import cloud.jengu.dbo.rest.RequestAuthenticator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a credential bound is gone before the thread serves anybody else.
 *
 * <p>A request's guard does not only decide yes or no. It binds who is
 * asking, what may be disclosed and under what purpose, which organisations
 * the answer may come from, and which audience it is answered as — four
 * thread-locals the store reads for the rest of the request. A door that
 * binds them and does not unbind them hands them to whoever the same thread
 * serves next.
 *
 * <p><b>Nothing had ever gone wrong, and that is the point.</b> Every surface
 * in the serving distribution runs on
 * {@code newVirtualThreadPerTaskExecutor}: a thread per request, dead
 * afterwards, taking anything left on it along. A door that clears nothing is
 * indistinguishable from one that clears everything, and the content door
 * cleared nothing. It was correct because of the executor it happened to be
 * wired to, which is a property of the wiring rather than of the door.
 *
 * <p>So this test supplies the other executor. One thread, two requests, and
 * the second asks what it found already bound. <b>The same thread serving
 * both is asserted</b>, because a pool that handed the second request a
 * different thread would answer "nothing was bound" for a reason that has
 * nothing to do with the door.
 */
class ACredentialDoesNotOutliveItsRequestTest {

    private HttpServer server;
    private ExecutorService oneThread;
    private final Watching guard = new Watching();

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The whole apparatus of this test, in one line: a pool of one, which
        // is what a servlet container is and what the distribution is not.
        oneThread = Executors.newSingleThreadExecutor();
        server.setExecutor(oneThread);
        server.createContext("/blob", new BlobHandler(guard, new HoldsNothing(), "/blob"));
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
        oneThread.shutdownNow();
    }

    @Test
    @DisplayName("a second request on the same thread finds nothing the first request's "
            + "credential bound — who it was, what it could disclose, its reach, its audience")
    void aCredentialDoesNotOutliveItsRequest() throws Exception {
        ask("/blob/first");
        ask("/blob/second");

        assertEquals(2, guard.seen.size(), "both requests should have reached the guard");
        Bound second = guard.seen.get(1);

        assertEquals(guard.seen.get(0).thread(), second.thread(),
                "the two requests were served by different threads, so this test could not "
                        + "have seen a credential outlive one — the executor is not a pool of "
                        + "one and the assertions below prove nothing");

        // "system" rather than null: an unauthenticated thread has an actor
        // and it is that one. Asserting null here would have failed whatever
        // the door did, which is a test that cannot pass rather than one that
        // cannot fail — the other half of the same mistake.
        assertEquals("system", second.caller(),
                "the second request opened holding the first request's caller, so the trail "
                        + "would attribute its writes to somebody who never made them");
        assertEquals(Disclosure.Mode.OMIT, second.mode(),
                "the second request opened able to disclose identity, because the first "
                        + "request's mode was still bound to the thread");
        assertNull(second.purpose(),
                "the second request opened under the first request's purpose of use, which is "
                        + "the reason the trail would record for disclosing somebody");
        assertNull(second.reach(),
                "the second request opened bound to the organisations the FIRST credential "
                        + "was entitled to read from");
        assertNull(second.audience(),
                "the second request opened answering as the audience the first was answered "
                        + "as, so a partner's declared view would be applied to somebody else");
    }

    private void ask(String path) throws Exception {
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.getAddress().getPort() + path))
                        .header("Authorization", "Bearer anything")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        // Not an assertion about the answer: this door holds nothing, so a
        // read is a 404 and that is fine. What matters is that the handler
        // ran, which a connection refused or a 500 would not tell us.
        assertTrue(answer.statusCode() == 404 || answer.statusCode() == 200,
                "the content door did not serve the request at all: " + answer.statusCode());
    }

    /** What was already on the thread when a request arrived. */
    private record Bound(String thread, String caller, Disclosure.Mode mode, String purpose,
            Set<String> reach, String audience) {}

    /**
     * A guard that binds what the real one binds, and says what it found.
     *
     * <p>The bindings are the four {@code AuthorityAuthenticator} makes on a
     * token carrying a purpose, a reach and an audience. Copied rather than
     * reached for, because this test is about the door's obligation to unbind
     * them and not about the authority's decision to bind them — and a test
     * that needed a real tenant to say this would be a test nobody runs.
     */
    private static final class Watching implements RequestAuthenticator {

        private final List<Bound> seen = new java.util.ArrayList<>();

        @Override
        public Denial check(String authorizationHeader, boolean mutation, String resourceType) {
            seen.add(new Bound(Thread.currentThread().getName(), Caller.current(),
                    Disclosure.mode(), Disclosure.purpose(), Reach.organisations(),
                    Audience.named()));
            Caller.set("Practitioner/one");
            Disclosure.set(Disclosure.Mode.INCLUDE, "TREAT");
            Reach.bind(Set.of("Organization/ward"));
            Audience.serving("a-partner");
            return null;
        }
    }

    /** A door has to be given a store; this one is never asked for content. */
    private static final class HoldsNothing implements BlobStore {

        @Override
        public String put(byte[] content, String media) {
            throw new UnsupportedOperationException("nothing is written in this test");
        }

        @Override
        public void restore(String key, byte[] content, String media) {
            throw new UnsupportedOperationException("nothing is restored in this test");
        }

        @Override
        public Optional<Blob> get(String key) {
            return Optional.empty();
        }

        @Override
        public boolean drop(String key) {
            return false;
        }

        @Override
        public long count() {
            return 0;
        }
    }
}
