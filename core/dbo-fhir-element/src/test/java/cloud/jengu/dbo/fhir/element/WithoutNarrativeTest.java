package cloud.jengu.dbo.fhir.element;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The byte-level div strip (#111): exact about escapes, and about touching
 * nothing that is not a Narrative's div.
 */
class WithoutNarrativeTest {

    private static final String STUB = "\"<div xmlns=\\\"http://www.w3.org/1999/xhtml\\\"/>\"";

    private static String stripped(String json) {
        return new String(WithoutNarrative.withoutDivBytes(
                json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void aDivStringBecomesTheStub() {
        assertEquals("{\"text\":{\"status\":\"generated\",\"div\":" + STUB + "}}",
                stripped("{\"text\":{\"status\":\"generated\",\"div\":\"<div>lots of html</div>\"}}"));
    }

    @Test
    void escapedQuotesInsideTheDivDoNotEndTheSkipEarly() {
        assertEquals("{\"div\":" + STUB + ",\"after\":1}",
                stripped("{\"div\":\"<a href=\\\"x\\\">y<\\\\/a>\",\"after\":1}"));
    }

    @Test
    void otherKeysAreUntouchedIncludingOnesWhoseValueMentionsDiv() {
        String json = "{\"text\":\"plain\",\"display\":\"a div elsewhere\",\"divided\":\"x\"}";
        assertEquals(json, stripped(json));
    }

    @Test
    void aDivKeyWithANonStringValueIsLeftAlone() {
        String json = "{\"div\":{\"nested\":true}}";
        assertEquals(json, stripped(json));
    }

    @Test
    void whitespaceBetweenKeyAndValueIsPreserved() {
        assertEquals("{\"div\" :  " + STUB + "}",
                stripped("{\"div\" :  \"<p>x</p>\"}"));
    }
}
