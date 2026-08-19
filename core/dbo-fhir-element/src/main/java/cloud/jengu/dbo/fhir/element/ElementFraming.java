package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.PayloadFraming;
import org.hl7.fhir.r5.context.SimpleWorkerContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Many objects as one document — a page, a history, an export.
 *
 * <p>The face says what goes around the members and how one member is spelled;
 * the caller writes prologue, separators and epilogue. Memory is one member
 * rather than one page, and nothing above learns that this format wants commas
 * (§1).
 *
 * <p>A member's payload is rendered rather than passed through, because the
 * ancestors have to be put back and nothing can do that without reading the
 * resource. The Bundle shape itself is written directly: it is the same in
 * every FHIR version this face serves, and building one through the element
 * model would mean holding a page.
 */
final class ElementFraming implements PayloadFraming {

    private final SimpleWorkerContext context;

    ElementFraming(SimpleWorkerContext context) {
        this.context = context;
    }

    @Override
    public Frame frame(String frameType, Facts facts) {
        StringBuilder head = new StringBuilder(128)
                .append("{\"resourceType\":\"Bundle\",\"type\":\"").append(frameType).append('"');
        if (facts.total() != null) {
            head.append(",\"total\":").append(facts.total());
        }
        StringBuilder links = new StringBuilder();
        if (facts.selfUrl() != null) {
            links.append("{\"relation\":\"self\",\"url\":").append(quoted(facts.selfUrl())).append('}');
        }
        if (facts.nextUrl() != null) {
            links.append(links.isEmpty() ? "" : ",")
                    .append("{\"relation\":\"next\",\"url\":").append(quoted(facts.nextUrl())).append('}');
        }
        if (!links.isEmpty()) {
            head.append(",\"link\":[").append(links).append(']');
        }
        head.append(",\"entry\":[");
        return new Frame(bytes(head.toString()), bytes(","), bytes("]}"));
    }

    @Override
    public void member(Member member, OutputStream out) throws IOException {
        out.write(bytes("{\"fullUrl\":" + quoted(member.url()) + ",\"resource\":"));
        out.write(ElementAncestors.rendered(context, member.payload(), member.id(),
                member.versionId(), member.elements()));
        out.write(bytes(",\"search\":{\"mode\":\""
                + (Member.INCLUDED.equals(member.role()) ? "include" : "match") + "\"}}"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** JSON string literal — the only escaping a frame does, since payloads pass whole. */
    private static String quoted(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
