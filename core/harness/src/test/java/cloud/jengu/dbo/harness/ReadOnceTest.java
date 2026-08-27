package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.ReadOnce;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A payload is read once by the face that reads it and the engine that stores
 * it, and read again by anyone handed different bytes
 * (REQ-DBO-VER-ONE-READ-PER-REQUEST).
 *
 * <p>The second half is the one worth a test. The document does not travel with
 * the write — it is remembered against the array it came from — precisely so
 * that a decorator rewriting a payload gets an envelope over what it is
 * actually storing rather than over what arrived.
 */
class ReadOnceTest {

    /** A face whose document is the text, and which says how often it read. */
    private static final class Counting implements Payloads<String> {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public String read(String typeName, byte[] payload) {
            reads.incrementAndGet();
            return new String(payload, StandardCharsets.UTF_8);
        }

        @Override
        public String typeOf(String document) {
            return "Gadget";
        }

        @Override
        public List<String> validate(String typeName, String document) {
            return List.of();
        }

        @Override
        public byte[] write(String document) {
            return document.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the engine asking about the bytes the face read gets the document it read")
    @Proving(DboPromises.VER_ONE_READ_PER_REQUEST)
    void oneReadForOneWrite() {
        Counting counting = new Counting();
        Payloads<String> payloads = new ReadOnce<>(counting);
        byte[] payload = bytes("{\"gadget\":1}");

        String read = payloads.read(null, payload);
        String extracted = payloads.read("Gadget", payload);

        assertEquals(1, counting.reads.get(), "the same array was read twice");
        assertSame(read, extracted, "the engine got a second document for one payload");
    }

    @Test
    @DisplayName("bytes that were rewritten on the way in are read as they now stand")
    @Proving(DboPromises.VER_ONE_READ_PER_REQUEST)
    void rewrittenBytesAreReadAgain() {
        Counting counting = new Counting();
        Payloads<String> payloads = new ReadOnce<>(counting);

        payloads.read(null, bytes("{\"name\":\"Ada\"}"));
        String stored = payloads.read("Gadget", bytes("{\"__pdiEnc\":\"…\"}"));

        assertEquals(2, counting.reads.get(),
                "a document was handed back for bytes it did not come from");
        assertEquals("{\"__pdiEnc\":\"…\"}", stored);
    }

    @Test
    @DisplayName("and equal bytes are not the same bytes")
    void equalBytesAreNotTheSameBytes() {
        Counting counting = new Counting();
        Payloads<String> payloads = new ReadOnce<>(counting);

        payloads.read(null, bytes("{\"gadget\":1}"));
        payloads.read(null, bytes("{\"gadget\":1}"));

        // Identity rather than content: what makes this safe is that a rewrite
        // cannot hit, and content-equality would have to trust that nothing
        // mutates an array it was handed.
        assertEquals(2, counting.reads.get());
    }

    @Test
    @DisplayName("a document is handed on once, so a completed write leaves nothing held")
    void nothingIsHeldPastTheHandover() {
        Counting counting = new Counting();
        Payloads<String> payloads = new ReadOnce<>(counting);
        byte[] payload = bytes("{\"gadget\":1}");

        payloads.read(null, payload);
        payloads.read("Gadget", payload);
        payloads.read("Gadget", payload);

        assertEquals(2, counting.reads.get(),
                "the document outlived the write it was read for");
    }

    @Test
    @DisplayName("a document read under one type does not answer for another")
    void aHintIsPartOfWhatWasRead() {
        Counting counting = new Counting();
        Payloads<String> payloads = new ReadOnce<>(counting);
        byte[] payload = bytes("gadget,1");

        payloads.read("Gadget", payload);
        payloads.read("Widget", payload);

        assertEquals(2, counting.reads.get(),
                "a face told what to read the bytes as was handed something else");
    }
}
