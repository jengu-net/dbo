package cloud.jengu.dbo.fhir.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference that is a question, answered over the bytes.
 *
 * <p>This was the last piece of work the write path's parse did, and the parse
 * is what holds a version's whole definition corpus. Everything else it fed
 * turned out to be a reading.
 */
class AQuestionIsAnsweredWithoutTheModelTest {

    private static final ElementReferences.Resolver ANSWERS =
            (type, query) -> "Patient".equals(type) && query.contains("RL-0042")
                    ? Optional.of("abc-123") : Optional.empty();

    private static String resolved(String json) {
        return new String(JsonReferences.resolve(json.getBytes(StandardCharsets.UTF_8), ANSWERS),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a question is answered where it sits, at any depth, and the rest of the "
            + "document is the bytes that arrived")
    void aQuestionIsAnswered() {
        String out = resolved("""
                {"resourceType":"Observation","status":"final",
                 "code":{"text":"height"},
                 "subject":{"reference":"Patient?identifier=urn:rl:nid|RL-0042"},
                 "note":[{"text":"why? because."}]}""");

        assertTrue(out.contains("\"reference\":\"Patient/abc-123\""),
                "the question was not answered: " + out);
        assertTrue(out.contains("\"text\":\"why? because.\""),
                "a question mark in free text was treated as a reference: " + out);
        assertTrue(out.contains("\"status\":\"final\"") && out.contains("\"text\":\"height\""),
                "the document lost something that was not a reference: " + out);
    }

    @Test
    @DisplayName("a document with nothing to answer is handed back as the same bytes")
    void nothingToAnswerCopiesNothing() {
        byte[] payload = """
                {"resourceType":"Observation","subject":{"reference":"Patient/abc-123"}}"""
                .getBytes(StandardCharsets.UTF_8);
        assertSame(payload, JsonReferences.resolve(payload, ANSWERS),
                "a document carrying no question was copied anyway");
    }

    @Test
    @DisplayName("and a question nothing answers is refused rather than stored pointing at "
            + "nothing")
    void anUnansweredQuestionIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> resolved("""
                        {"resourceType":"Observation",
                         "subject":{"reference":"Patient?identifier=urn:rl:nid|NOBODY"}}"""));
        assertTrue(refused.getMessage().contains("matches nothing"),
                refused.getMessage());
    }

    @Test
    @DisplayName("an array of references is answered member by member")
    void everyMemberIsAnswered() {
        String out = resolved("""
                {"resourceType":"Observation","performer":[
                   {"reference":"Patient?identifier=urn:rl:nid|RL-0042"},
                   {"reference":"Practitioner/known"}]}""");
        assertEquals(1, out.split("Patient/abc-123", -1).length - 1, out);
        assertTrue(out.contains("Practitioner/known"), out);
    }

    @Test
    @DisplayName("the engine's own stamp does not ride back in as content, and a claimed "
            + "profile beside it survives")
    void ourStampIsDropped() {
        String out = resolved("""
                {"resourceType":"Observation","meta":{"profile":["https://ee.ee/sd/x"],
                 "extension":[{"url":"urn:dbo:shape","extension":[{"url":"version",
                 "valueString":"3.0.0"}]},{"url":"https://ee.ee/ext/mine",
                 "valueString":"keep me"}]}}""");

        assertTrue(!out.contains("urn:dbo:shape"),
                "the engine re-states its stamp on every serve, so a copy handed back is a "
                        + "second answer to a question the store answers: " + out);
        assertTrue(out.contains("keep me"),
                "somebody else's extension went with it: " + out);
        assertTrue(out.contains("https://ee.ee/sd/x"),
                "the claimed profile was lost: " + out);
    }
}
