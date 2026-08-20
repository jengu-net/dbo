package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.DocumentEquivalence;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.model.JsonProperty;
import org.hl7.fhir.utilities.json.parser.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Two documents are the same object when their canonical forms are the same
 * bytes.
 *
 * <p>Over the <b>JSON tree</b> rather than the resource model, and that is the
 * whole reason this is safe: the element model writes what a version's
 * definitions describe and drops what they do not, so canonicalising through it
 * would call two documents equal because it had thrown away the field they
 * differ in. A tree canonicaliser interprets nothing — an element nobody has
 * heard of is sorted and written back like any other.
 *
 * <p>What it decides, and each of these was a real difference between two
 * documents that mean the same thing:
 * <ul>
 *   <li><b>Object keys sort.</b> A tool that writes {@code name} before
 *       {@code id} has not changed the resource, and comparing bytes said it
 *       had.</li>
 *   <li><b>Arrays do not.</b> Order is meaningful in FHIR — a name list, a
 *       coding list — and sorting them would call two different resources
 *       equal, which is the failure that cannot be noticed afterwards.</li>
 *   <li><b>Whitespace between tokens goes.</b> Pretty-printed and compact are
 *       the same document.</li>
 *   <li><b>Numbers keep their written form.</b> {@code 1.50} stays
 *       {@code 1.50}: in FHIR a decimal's trailing zero is precision, and
 *       treating it as noise would make two different measurements equal.</li>
 * </ul>
 */
final class ElementEquivalence implements DocumentEquivalence {

    static final ElementEquivalence INSTANCE = new ElementEquivalence();

    private ElementEquivalence() {
    }

    @Override
    public byte[] canonical(byte[] document) {
        JsonObject parsed;
        try {
            parsed = JsonParser.parseObject(document);
        } catch (Exception notADocument) {
            // The caller handed over something that is not a document. An
            // IllegalArgumentException is what a router turns into a 400; the
            // parser's own exception would answer 500 and blame the server.
            throw new IllegalArgumentException(
                    "not a JSON document: " + notADocument.getMessage(), notADocument);
        }
        StringBuilder out = new StringBuilder(document.length);
        write(parsed, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(JsonElement element, StringBuilder out) {
        switch (element) {
            case JsonObject object -> {
                List<JsonProperty> properties = new ArrayList<>(object.getProperties());
                properties.sort(Comparator.comparing(JsonProperty::getName));
                out.append('{');
                boolean first = true;
                for (JsonProperty property : properties) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(property.getName(), out);
                    out.append(':');
                    write(property.getValue(), out);
                }
                out.append('}');
            }
            case JsonArray array -> {
                out.append('[');
                boolean first = true;
                for (JsonElement item : array) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out);
                }
                out.append(']');
            }
            case org.hl7.fhir.utilities.json.model.JsonString text -> string(text.getValue(), out);
            // As written: a decimal's trailing zero is precision in FHIR, and
            // 1.50 is not the same measurement as 1.5.
            case org.hl7.fhir.utilities.json.model.JsonNumber number -> out.append(number.getValue());
            case org.hl7.fhir.utilities.json.model.JsonBoolean bool -> out.append(bool.isValue());
            case org.hl7.fhir.utilities.json.model.JsonNull ignored -> out.append("null");
            default -> {
                // comments and anything else the parser can carry: not part of
                // what a document says
            }
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
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
}
