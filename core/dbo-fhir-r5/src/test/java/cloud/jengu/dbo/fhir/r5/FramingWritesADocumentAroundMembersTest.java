package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.face.PayloadFraming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document is written around members the caller already holds, rather than
 * assembled from them.
 *
 * <p>The caller owns the loop: it writes the prologue, a separator before every
 * member but the first, and the epilogue. Nothing here holds a page.
 */
class FramingWritesADocumentAroundMembersTest {

    private static final byte[] PATIENT =
            "{\"resourceType\":\"Patient\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

    private static String framed(int members) throws Exception {
        PayloadFraming framing = R5Version.framing();
        PayloadFraming.Frame frame = framing.frame("searchset",
                new PayloadFraming.Facts(null, "http://x.test/fhir/Patient", null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(frame.prologue());
        for (int i = 0; i < members; i++) {
            if (i > 0) {
                out.write(frame.separator());
            }
            framing.member(new PayloadFraming.Member("Patient", "id" + i, 1, PATIENT,
                    "http://x.test/fhir/Patient/id" + i, PayloadFraming.Member.MATCHED), out);
        }
        out.write(frame.epilogue());
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an empty document is still a document")
    void noMembersStillFrames() throws Exception {
        String json = framed(0);
        assertEquals("{\"resourceType\":\"Bundle\",\"type\":\"searchset\","
                + "\"link\":[{\"relation\":\"self\",\"url\":\"http://x.test/fhir/Patient\"}],"
                + "\"entry\":[]}", json);
    }

    @Test
    @DisplayName("members are separated by what the face said, not by what the caller guessed")
    void membersAreSeparated() throws Exception {
        String json = framed(3);
        assertEquals(3, json.split("\"fullUrl\"", -1).length - 1, json);
        // parses as the document it claims to be
        assertTrue(R5Version.parse(json) instanceof org.hl7.fhir.r5.model.Bundle,
                "the frame did not produce a Bundle: " + json);
    }

    @Test
    @DisplayName("a member says why it is in the document")
    void aMemberCarriesItsRole() throws Exception {
        PayloadFraming framing = R5Version.framing();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        framing.member(new PayloadFraming.Member("Patient", "x", 1, PATIENT,
                "http://x.test/fhir/Patient/x", PayloadFraming.Member.INCLUDED), out);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"mode\":\"include\""),
                out.toString(StandardCharsets.UTF_8));
    }
}
