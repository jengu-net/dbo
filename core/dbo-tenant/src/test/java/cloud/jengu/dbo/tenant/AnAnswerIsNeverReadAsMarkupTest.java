package cloud.jengu.dbo.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** What a caller sent, echoed in a step surface's answer, stays text. */
class AnAnswerIsNeverReadAsMarkupTest {

    @Test
    @DisplayName("a value carrying markup, quotes and control characters is one JSON string "
            + "that reads back as it was sent, with nothing in it a browser would read as markup")
    void anEchoedValueStaysAString() {
        String sent = "a.step</script><script>alert(\"x\")</script>&\u0000\n\t\\ ";
        String quoted = JsonText.quote(sent);
        assertFalse(quoted.contains("<") || quoted.contains(">") || quoted.contains("&"),
                quoted);
        assertFalse(quoted.chars().anyMatch(c -> c < 0x20), "a control character is raw");
        assertEquals(sent, cloud.jengu.dbo.core.wire.RecordWire.read(
                "{\"detail\":" + quoted + "}") instanceof java.util.Map<?, ?> read
                ? read.get("detail") : null);
        assertEquals("\"null\"", JsonText.quote(null));
    }
}
