package cloud.jengu.dbo.stream;

import cloud.jengu.dbo.core.wire.RecordWire;

import java.nio.charset.StandardCharsets;

/**
 * What an ask on the stream is signed over, said once for both ends of it.
 *
 * <p>It used to be said twice — the sending lane built the string and the
 * receiving door built it again — and both comments claimed it was the body
 * "exactly as it travelled", which was not quite true of either. Each side
 * signed and verified a <b>re-rendering</b> of the parsed body, so the
 * signature was really over this store's JSON renderer's output. They agreed
 * only because they ran the same renderer, and nothing anywhere said that
 * renderer was a compatibility surface: a change to how it escapes, spaces or
 * formats a number would have made a sender and a door on either side of it
 * reject each other's asks <b>as forgeries</b>, which is the worst available
 * spelling of a version skew.
 *
 * <p>So the body's own bytes are what is signed and what travels, and the two
 * sides read that sentence from one place.
 */
public final class StreamAsk {

    private StreamAsk() {
    }

    /** The ask's id, its verb, and the body's own bytes. */
    public static byte[] signedOver(Object id, Object verb, String body) {
        return (id + "\n" + verb + "\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The body as the bytes it arrived as.
     *
     * <p>A {@link RecordWire.Raw} is a body read without being parsed, which
     * is how a door reads one now. Anything else is a body that arrived as a
     * tree — an older sender, which rendered it into the message and signed
     * that rendering — so rendering it again reproduces what it signed.
     */
    public static String bytesOf(Object carried) {
        return carried instanceof RecordWire.Raw raw ? raw.text() : RecordWire.write(carried);
    }
}
