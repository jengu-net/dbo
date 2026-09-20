package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.RecordingLogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A verb that could not complete answers 500 and says "the verb did not
 * complete", which is all the far side may be told: the caller is another
 * organisation and the cause is this deployment's business.
 *
 * <p>So the cause has to be somewhere, and it was nowhere. The handler
 * swallowed the exception, the runner on the other side logged its own cycle
 * failure at WARN quoting that same sentence, and finding out what actually
 * happened cost bringing the world up twice with a probe wired into a test.
 */
class AFailedVerbSaysWhyItFailedTest {

    @Test
    @DisplayName("a verb that throws answers 500, and the cause is in this side's log")
    @Proving(DboPromises.PROC_A_FAULT_THE_CALLER_IS_NOT_TOLD_IS_STILL_RECORDED)
    void aVerbThatThrowsLeavesItsCauseInTheLog() throws Exception {
        RecordingLogs.EVENTS.clear();

        // A lane that fails the way the ones that matter fail: not a refusal
        // and not a malformed ask, which have their own answers, but
        // something nobody anticipated.
        Lane broken = (Lane) Proxy.newProxyInstance(Lane.class.getClassLoader(),
                new Class<?>[] {Lane.class}, (proxy, method, arguments) -> {
                    if ("tenant".equals(method.getName())) {
                        return "hogwarts";
                    }
                    // Neither of the two the handler answers for by name: an
                    // IllegalStateException is a refusal and an
                    // IllegalArgumentException is a malformed ask, and both
                    // travel to the caller with their reason.
                    throw new NullPointerException("unreachable");
                });

        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/work", new LaneHandler("/work",
                authorization -> new LaneHandler.Grant("a-participant",
                        Lane.Entitlement.everything(), true),
                (participant, identity, entitlement) -> broken));
        server.start();
        try {
            HttpResponse<String> answered = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:"
                                    + server.getAddress().getPort() + "/work/poll"))
                            .header("Authorization", "Bearer anything")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"participant\":\"a-participant\",\"identity\":"
                                            + "{\"name\":\"a-participant\",\"version\":\"1\","
                                            + "\"provider\":\"somebody\",\"scope\":"
                                            + "{\"at\":\"BASELINE\",\"of\":null}},"
                                            + "\"steps\":[],\"limit\":10}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(500, answered.statusCode(), answered.body());
            assertTrue(answered.body().contains("the verb did not complete"),
                    "the far side was told something other than what it is told: "
                            + answered.body());
        } finally {
            server.stop(0);
        }

        String logged = String.join("\n", RecordingLogs.EVENTS);
        assertTrue(logged.contains("ERROR"), "a 500 was answered and nothing was logged");
        assertTrue(logged.contains("unreachable"),
                "the log holds the answer the caller already had, and not the cause: " + logged);
    }
}
