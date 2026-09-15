package cloud.jengu.dbo.runner.http;

import cloud.jengu.dbo.core.wire.RecordWire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value this reader passes on rather than reads used to cost a tree, a
 * builder and a second copy of itself — to establish that it was JSON, which
 * the parse establishes on its own. Over a large enough set that is a heap
 * failure, and the bytes it ran out of memory producing were in the argument
 * all along.
 */
class APayloadIsPassedOnAsItArrivedTest {

    @Test
    @DisplayName("a kept member arrives as the text it was written as, and is written back "
            + "byte for byte")
    void aKeptMemberIsTheTextItArrivedAs() {
        String payload = "{\"resourceType\":\"ValueSet\",  \"status\" : \"draft\","
                + "\"name\":\"kept\",\"count\":3,\"deep\":{\"a\":[1,2,{\"b\":null}]}}";
        String body = "{\"declarations\":[{\"type\":\"ValueSet\",\"payload\":" + payload + "}]}";

        Object read = RecordWire.read(body, "payload");
        Object one = ((List<?>) ((Map<?, ?>) read).get("declarations")).get(0);
        Object kept = ((Map<?, ?>) one).get("payload");

        RecordWire.Raw raw = assertInstanceOf(RecordWire.Raw.class, kept,
                "the payload was parsed into a tree, which is the copy this exists to avoid");
        // Byte for byte, spacing and key order included: nothing re-renders it,
        // so there is nothing for a renderer to normalise or to disagree about.
        assertEquals(payload, raw.text());
        assertEquals(payload, RecordWire.write(kept));

        // The rest of the body is read as usual — only the named member is kept.
        assertEquals("ValueSet", ((Map<?, ?>) one).get("type"));
    }

    @Test
    @DisplayName("a string carrying braces or quotes does not end the span early")
    void bracesInsideAStringAreText() {
        String payload = "{\"text\":\"} ] \\\" not structure {\",\"n\":1}";
        String body = "{\"payload\":" + payload + ",\"after\":\"still read\"}";

        Object read = RecordWire.read(body, "payload");
        assertEquals(payload, ((RecordWire.Raw) ((Map<?, ?>) read).get("payload")).text());
        assertEquals("still read", ((Map<?, ?>) read).get("after"),
                "the span ran past its own end, so everything after it was lost");
    }

    @Test
    @DisplayName("a kept member may be any JSON value, not only an object")
    void anyValueCanBeKept() {
        assertEquals("[1,2,3]", ((RecordWire.Raw) ((Map<?, ?>)
                RecordWire.read("{\"payload\":[1,2,3]}", "payload")).get("payload")).text());
        assertEquals("\"plain\"", ((RecordWire.Raw) ((Map<?, ?>)
                RecordWire.read("{\"payload\":\"plain\"}", "payload")).get("payload")).text());
        assertEquals("null", ((RecordWire.Raw) ((Map<?, ?>)
                RecordWire.read("{\"payload\":null}", "payload")).get("payload")).text());
    }

    @Test
    @DisplayName("reading without naming a member is what it always was")
    void nothingIsKeptUnlessItIsAsked() {
        Object read = RecordWire.read("{\"payload\":{\"a\":1}}");
        assertTrue(((Map<?, ?>) read).get("payload") instanceof Map,
                "a reader that did not ask for a span got one anyway");
    }
}
