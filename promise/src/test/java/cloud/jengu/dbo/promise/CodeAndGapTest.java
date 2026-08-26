package cloud.jengu.dbo.promise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAndGapTest {

    @Test
    @DisplayName("the constant's name is the code, prefixed by the catalogue's namespace")
    void nameIsTheCode() {
        assertEquals("REQ-TEST-PLAIN-PROMISE", TestPromises.PLAIN_PROMISE.code());
        assertEquals("US-TEST-FIRST-CASE", TestStories.FIRST_CASE.code());
    }

    /**
     * An enum constant WITH A BODY is an anonymous subclass of its enum;
     * a lookup via {@code getClass()} would miss {@code @Catalogue} there.
     */
    @Test
    @DisplayName("a constant with a body reports the same, correct code")
    void bodiedConstantReportsTheSameCode() {
        assertEquals("REQ-TEST-BODIED-PROMISE", TestPromises.BODIED_PROMISE.code());
        assertEquals("a promise whose constant has a body", TestPromises.BODIED_PROMISE.text());
    }

    @Test
    @DisplayName("a gap's synthetic code is stable across runs and distinct across texts")
    void gapCodesAreStableAndDistinct() {
        Promise first = Promise.gap("what about the second try?");
        Promise again = Promise.gap("what about the second try?");
        Promise other = Promise.gap("and the third?");
        assertEquals(first.code(), again.code(), "same text, same code — reports must diff cleanly");
        assertNotEquals(first.code(), other.code(), "different text, different code");
        assertTrue(first.code().startsWith("GAP-"), first.code());
        assertTrue(first.gap());
    }

    @Test
    @DisplayName("a gap without text is refused — an unnamed unknown is the silence this type ends")
    void aGapNeedsItsText() {
        assertThrows(IllegalArgumentException.class, () -> Promise.gap("  "));
    }
}
