package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.face.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request reads a payload once, however many questions it asks of it.
 *
 * <p>The write path used to ask the personality for the type and then for the
 * verdict, each from the bytes, and the engine's envelope extraction read them
 * again — three reads of one document to accept one write. Counting is the only
 * way to see that: the output of two reads and of one is identical, which is
 * why it went unnoticed.
 */
class OneReadPerRequestTest {

    private static final byte[] PATIENT =
            "{\"resourceType\":\"Patient\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

    @SuppressWarnings("unchecked")
    private static Payloads<Object> payloads() {
        return (Payloads<Object>) R5Version.face().require(Payloads.class);
    }

    @Test
    @DisplayName("the type and the verdict come from one read")
    void theTypeAndTheVerdictShareOneRead() {
        Payloads<Object> payloads = payloads();
        long before = R5Version.READS.get();

        Object document = payloads.read(null, PATIENT);
        String type = payloads.typeOf(document);
        List<String> issues = payloads.validate(type, document);

        assertEquals(1, R5Version.READS.get() - before,
                "the write path is reading the same bytes more than once again");
        assertEquals("Patient", type);
        assertEquals(List.of(), issues);
    }

    @Test
    @DisplayName("and asking the same document twice does not read it again")
    void askingTwiceDoesNotReadTwice() {
        Payloads<Object> payloads = payloads();
        Object document = payloads.read(null, PATIENT);
        long after = R5Version.READS.get();

        payloads.typeOf(document);
        payloads.validate(payloads.typeOf(document), document);

        assertEquals(after, R5Version.READS.get(),
                "a question about a document read the bytes behind it");
    }
}
