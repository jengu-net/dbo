package cloud.jengu.dbo.fhir.validate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A document as maps, lists and strings.
 *
 * <p>A tree, where the index deliberately is not one: a document is one
 * resource and a definition corpus is ninety thousand elements, so the form
 * that is wrong for the second is the obvious one for the first.
 *
 * <p><b>Why this exists rather than a library.</b> The property this module
 * is for is that a node holding it carries nothing — no toolchain, no driver,
 * and nothing that would have to be embedded privately because another bundle
 * already embeds it. A checker needs to know which keys a document has and
 * how often, and that is a scan, not a parser: numbers, booleans and null
 * arrive as their written text and are never interpreted, because nothing
 * here asks what a value MEANS. What a value must be is a different check
 * against different rows, and it will read the text as written — which is
 * also what keeps a decimal's precision, the thing a parser reading doubles
 * destroys in silence.
 *
 * <p><b>A literal is not a string</b>, and that distinction is kept even
 * though no check needed it. A checker never asks whether {@code "1.5"} was
 * written quoted; a reader that has to give the document BACK does, because
 * writing a number as a quoted string corrupts every document it touches. So
 * a number, a boolean and null arrive as a {@link Literal} carrying the text
 * exactly as it was written — which is also what keeps a decimal's precision,
 * the thing a parser reading doubles destroys in silence.
 */
final class JsonDocument {

    /**
     * A number, a boolean or null, as the text that was written.
     *
     * <p>Distinct from a string so that composing gives back what arrived: in
     * jsonb as on the wire, {@code "1"} and {@code 1} are different values.
     */
    record Literal(String text) implements Documents.Literal {
        @Override
        public String toString() {
            return text;
        }
    }

    private final byte[] bytes;
    private int at;

    private JsonDocument(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * The document, or null where it is not an object — which is not a
     * cardinality question and is left to whoever parses for meaning.
     */
    static Map<String, Object> of(byte[] document) {
        JsonDocument scan = new JsonDocument(document);
        scan.whitespace();
        if (scan.at >= document.length || document[scan.at] != '{') {
            return null;
        }
        Object read = scan.value();
        return read instanceof Map ? asObject(read) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object read) {
        return (Map<String, Object>) read;
    }

    /**
     * Any JSON value, which is what a profile's fixed or pattern is: an
     * object, an array of codings, or a bare value alike.
     */
    static Object value(String json) {
        return new JsonDocument(json.getBytes(StandardCharsets.UTF_8)).value();
    }

    private Object value() {
        whitespace();
        byte c = bytes[at];
        if (c == '{') {
            at++;
            Map<String, Object> object = new LinkedHashMap<>();
            whitespace();
            if (bytes[at] == '}') {
                at++;
                return object;
            }
            while (true) {
                whitespace();
                String field = string();
                whitespace();
                at++; // the colon
                object.put(field, value());
                whitespace();
                byte next = bytes[at++];
                if (next == '}') {
                    return object;
                }
            }
        }
        if (c == '[') {
            at++;
            List<Object> many = new ArrayList<>();
            whitespace();
            if (bytes[at] == ']') {
                at++;
                return many;
            }
            while (true) {
                many.add(value());
                whitespace();
                byte next = bytes[at++];
                if (next == ']') {
                    return many;
                }
            }
        }
        if (c == '"') {
            return string();
        }
        return literal();
    }

    /**
     * A string, with the escapes a key can carry resolved.
     *
     * <p>Only the escapes: a value's text is what the wire carried, and
     * nothing here compares one to anything a definition states.
     */
    private String string() {
        at++; // the opening quote
        int from = at;
        boolean escapes = false;
        while (bytes[at] != '"') {
            if (bytes[at] == '\\') {
                escapes = true;
                at++;
            }
            at++;
        }
        // Decoded as UTF-8 over the whole span rather than byte by byte: a
        // name written in anything but ASCII is one character in several
        // bytes, and a scan that appended each byte as a char would report a
        // key the document does not have.
        String raw = new String(bytes, from, at++ - from, StandardCharsets.UTF_8);
        return escapes ? unescaped(raw) : raw;
    }

    private static String unescaped(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = raw.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    /** A number, a boolean or null, kept as the text that was written. */
    private Literal literal() {
        int from = at;
        while (at < bytes.length) {
            byte c = bytes[at];
            if (c == ',' || c == '}' || c == ']' || c <= ' ') {
                break;
            }
            at++;
        }
        return new Literal(new String(bytes, from, at - from, StandardCharsets.UTF_8));
    }

    /**
     * The document as it would be written.
     *
     * <p>A string is quoted and escaped; a literal is emitted as the text that
     * arrived, so a decimal keeps the precision its author gave it. Object
     * members keep the order they were read in, because a reader that
     * reordered them would hand back a document nobody sent.
     */
    static byte[] compose(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Object value, StringBuilder out) {
        if (value instanceof Literal literal) {
            out.append(literal.text());
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Map<?, ?> object) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> many) {
            out.append('[');
            for (int i = 0; i < many.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(many.get(i), out);
            }
            out.append(']');
        } else {
            out.append("null");
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void whitespace() {
        while (at < bytes.length && bytes[at] <= ' ') {
            at++;
        }
    }
}
