package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.stream.StreamAsk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An ask on the stream is signed over the bytes that travel.
 *
 * <p>It was signed over a <b>re-rendering</b> of them. The sending lane parsed
 * the body and signed the tree's rendering; the door parsed the ask and
 * rendered the tree again to verify. Both comments said "exactly as they
 * travelled" and neither did: what was really signed was the output of this
 * store's JSON renderer, and the two sides agreed only because they ran the
 * same one.
 *
 * <p>Nothing said that renderer was a compatibility surface. It lives in
 * {@code dbo-core}, which has no dependencies and is the natural place for
 * somebody to tidy how a number is formatted — and the cost of that tidying
 * would have been a sender and a door on either side of it rejecting each
 * other's asks <b>as forgeries</b>. A version skew that presents as an
 * authentication failure is the worst available spelling of one: it points
 * the reader at the key.
 *
 * <p>The property under test is therefore not "a signature verifies" — the
 * carrier's own tests cover that. It is that the sentence being signed no
 * longer depends on how anything is rendered, and that a sender written
 * before this still agrees with a door written after it.
 */
class WhatAStreamAskIsSignedOverTest {

    /** Valid JSON, spelled the way a caller might rather than the way we would. */
    private static final String AS_WRITTEN =
            "{\"participant\" : \"analyser\",  \"n\" : 1.50, \"note\":\"a, b\"}";

    @Test
    @DisplayName("the body's own bytes survive the ask whole, so what the door verifies is "
            + "what the caller handed in rather than what we would have written")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void theBytesTravelUnchanged() {
        String message = RecordWire.write(askCarrying(new RecordWire.Raw(AS_WRITTEN)));
        String atTheDoor = StreamAsk.bytesOf(read(message).get("body"));

        assertEquals(AS_WRITTEN, atTheDoor,
                "the body was re-rendered somewhere between the two ends, so its signature "
                        + "depends on the renderer rather than on what was sent");

        // And the re-rendering really is different, or this test would pass
        // on a body for which the distinction cannot be observed.
        assertFalse(RecordWire.write(RecordWire.read(AS_WRITTEN)).equals(AS_WRITTEN),
                "the sample body renders back to itself, so it cannot show the difference "
                        + "this test is about — pick one that does not");
    }

    @Test
    @DisplayName("the two ends spell the signed sentence identically, because they read it "
            + "from one place rather than each writing it out")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void bothEndsSignTheSameSentence() {
        byte[] atTheLane = StreamAsk.signedOver("ask-1", "claim", AS_WRITTEN);

        String message = RecordWire.write(askCarrying(new RecordWire.Raw(AS_WRITTEN)));
        Map<String, Object> ask = read(message);
        byte[] atTheDoor = StreamAsk.signedOver(
                ask.get("id"), ask.get("verb"), StreamAsk.bytesOf(ask.get("body")));

        assertArrayEquals(atTheLane, atTheDoor,
                "the door verifies a different sentence from the one the lane signed");
    }

    @Test
    @DisplayName("a sender from before this still agrees with the door, because the rendering "
            + "it signed is the rendering that sits in the message it sent")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void anOlderSenderStillVerifies() {
        // As the lane used to build it: the body parsed into a tree, and the
        // tree's rendering signed.
        Object parsed = RecordWire.read(AS_WRITTEN);
        byte[] asItSigned = StreamAsk.signedOver("ask-1", "claim", RecordWire.write(parsed));
        String message = RecordWire.write(askCarrying(parsed));

        Map<String, Object> ask = read(message);
        byte[] atTheDoor = StreamAsk.signedOver(
                ask.get("id"), ask.get("verb"), StreamAsk.bytesOf(ask.get("body")));

        assertArrayEquals(asItSigned, atTheDoor,
                "an ask signed by a sender written before this reads as a forgery, which is "
                        + "the failure the change was supposed to make impossible");
    }

    @Test
    @DisplayName("a body altered on the plane changes the sentence, which is the whole point "
            + "of signing one")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void anAlteredBodyIsADifferentSentence() {
        Map<String, Object> ask = read(RecordWire.write(
                askCarrying(new RecordWire.Raw(AS_WRITTEN))));
        byte[] honest = StreamAsk.signedOver(
                ask.get("id"), ask.get("verb"), StreamAsk.bytesOf(ask.get("body")));

        Map<String, Object> tampered = read(RecordWire.write(askCarrying(
                new RecordWire.Raw(AS_WRITTEN.replace("\"analyser\"", "\"somebody-else\"")))));
        byte[] forged = StreamAsk.signedOver(
                tampered.get("id"), tampered.get("verb"),
                StreamAsk.bytesOf(tampered.get("body")));

        assertFalse(java.util.Arrays.equals(honest, forged),
                "changing who the ask says is asking left the signed sentence alone");
    }

    private static Map<String, Object> askCarrying(Object body) {
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("id", "ask-1");
        ask.put("participant", "analyser");
        ask.put("verb", "claim");
        ask.put("body", body);
        return ask;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String message) {
        return (Map<String, Object>) RecordWire.read(message, "body");
    }

    /** Guards the sample: a body whose bytes are not UTF-8 would prove nothing here. */
    @Test
    @DisplayName("the sentence is bytes, taken as UTF-8, so a body that is not ASCII signs "
            + "as what it is rather than as what a default charset made of it")
    @Proving(DboPromises.PROC_A_LANE_OVER_THE_STREAM)
    void theSentenceIsUtf8() {
        String estonian = "{\"note\":\"õun ja mõõk\"}";
        assertArrayEquals(("ask-1\nclaim\n" + estonian).getBytes(StandardCharsets.UTF_8),
                StreamAsk.signedOver("ask-1", "claim", estonian));
    }
}
