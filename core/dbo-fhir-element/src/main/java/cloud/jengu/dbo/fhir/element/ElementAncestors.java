package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.formats.IParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A stored payload with the engine's own facts put back, and nothing else
 * touched (REQ-DBO-CORE-READER-RECEIVES-THE-DECLARED-FORM).
 *
 * <p>Stamping is a claim about custody at a moment and is made at write; putting
 * the ancestors into a document a reader receives is the other act and belongs
 * at read, because the alternative is freezing the store's facts into bytes that
 * are never rewritten (§1).
 *
 * <p><b>Why this copies tokens instead of re-rendering.</b> Reading the payload
 * into the element model and writing it back is the obvious way to set two
 * fields, and it loses data: the element model writes what the version's
 * StructureDefinitions describe, so an element the version does not define is
 * dropped — a resource stored carrying {@code instantiatesCanonical} under R4
 * comes back without it. Nothing is lost in storage, because the payload is
 * kept as it arrived; it is lost on the way to a reader, silently, which is
 * worse than a refusal. It is also why the element model may not be declared a
 * normalized truth form (REQ-DBO-VER-NORMALISING-LOSES-NOTHING): it does not
 * pass that requirement's own round-trip test.
 *
 * <p>So the document is copied token for token — every field, in the order it
 * was written, including the ones this version has never heard of — and only
 * {@code id} and {@code meta.versionId} are replaced as they pass. Replace
 * rather than insert: a payload may already carry either, and appending a
 * second one would produce a document with two ids.
 */
final class ElementAncestors {

    private static final JsonFactory JSON = JsonFactory.builder().build();

    private ElementAncestors() {
    }

    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id,
            long versionId) {
        return rendered(context, payload, id, versionId, null);
    }

    /**
     * @param elements when given, the only elements encoded — the store's own
     *                 slots always survive, because a resource a client cannot
     *                 reference is not a smaller resource, it is a broken one
     */
    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id, long versionId,
            List<String> elements) {
        if (elements != null && !elements.isEmpty()) {
            return projected(context, payload, id, versionId, elements);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
        try (JsonParser in = JSON.createParser(payload);
                JsonGenerator gen = JSON.createGenerator(out)) {
            if (in.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("a stored payload that is not a JSON object");
            }
            gen.writeStartObject();
            boolean sawId = false;
            boolean sawMeta = false;
            while (in.nextToken() == JsonToken.FIELD_NAME) {
                String field = in.currentName();
                in.nextToken();
                switch (field) {
                    case "id" -> {
                        in.skipChildren();
                        gen.writeStringField("id", id);
                        sawId = true;
                    }
                    case "meta" -> {
                        meta(in, gen, versionId);
                        sawMeta = true;
                    }
                    default -> {
                        gen.writeFieldName(field);
                        copy(in, gen);
                    }
                }
            }
            if (!sawId) {
                gen.writeStringField("id", id);
            }
            if (!sawMeta) {
                gen.writeObjectFieldStart("meta");
                gen.writeStringField("versionId", Long.toString(versionId));
                gen.writeEndObject();
            }
            gen.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot re-emit a stored payload held in memory", e);
        }
        return out.toByteArray();
    }

    /** The stored meta, with the store's version in it rather than beside it. */
    private static void meta(JsonParser in, JsonGenerator gen, long versionId) throws IOException {
        gen.writeObjectFieldStart("meta");
        boolean sawVersion = false;
        while (in.nextToken() == JsonToken.FIELD_NAME) {
            String field = in.currentName();
            in.nextToken();
            if ("versionId".equals(field)) {
                in.skipChildren();
                gen.writeStringField("versionId", Long.toString(versionId));
                sawVersion = true;
            } else {
                gen.writeFieldName(field);
                copy(in, gen);
            }
        }
        if (!sawVersion) {
            gen.writeStringField("versionId", Long.toString(versionId));
        }
        gen.writeEndObject();
    }

    /**
     * The value under the parser's current token, written out as it was
     * written in.
     *
     * <p>Jackson's own copy re-emits a number from its parsed value, and a
     * number is not its value: {@code 1.50} comes back {@code 1.5}, and the
     * trailing zero was a laboratory saying which digit it stood behind. So a
     * number is copied as text, and everything else structurally.
     */
    private static void copy(JsonParser in, JsonGenerator gen) throws IOException {
        switch (in.currentToken()) {
            case START_OBJECT -> {
                gen.writeStartObject();
                while (in.nextToken() == JsonToken.FIELD_NAME) {
                    gen.writeFieldName(in.currentName());
                    in.nextToken();
                    copy(in, gen);
                }
                gen.writeEndObject();
            }
            case START_ARRAY -> {
                gen.writeStartArray();
                while (in.nextToken() != JsonToken.END_ARRAY) {
                    copy(in, gen);
                }
                gen.writeEndArray();
            }
            case VALUE_NUMBER_FLOAT, VALUE_NUMBER_INT -> gen.writeNumber(in.getText());
            case VALUE_STRING -> gen.writeString(in.getText());
            case VALUE_TRUE -> gen.writeBoolean(true);
            case VALUE_FALSE -> gen.writeBoolean(false);
            case VALUE_NULL -> gen.writeNull();
            default -> throw new IllegalArgumentException(
                    "a stored payload carrying " + in.currentToken() + ", which JSON has no way "
                            + "to have produced");
        }
    }

    /**
     * A projection is a different promise: the caller asked for less than was
     * stored, so it is rendered through the model rather than copied — and what
     * the model does not know about is not among the elements they named.
     */
    private static byte[] projected(SimpleWorkerContext context, byte[] payload, String id,
            long versionId, List<String> elements) {
        try {
            Element document = Manager.parseSingle(context, new ByteArrayInputStream(payload),
                    Manager.FhirFormat.JSON);
            document.setChildValue("id", id);
            Element meta = document.getNamedChild("meta");
            if (meta == null) {
                meta = document.makeElement("meta");
            }
            meta.setChildValue("versionId", Long.toString(versionId));
            Set<String> keep = new LinkedHashSet<>();
            elements.forEach(element -> keep.add(element.trim()));
            for (Element child : new java.util.ArrayList<>(document.getChildren())) {
                if (!keep.contains(child.getName()) && !"id".equals(child.getName())
                        && !"meta".equals(child.getName())) {
                    document.removeChild(child);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
            Manager.compose(context, document, out, Manager.FhirFormat.JSON,
                    IParser.OutputStyle.NORMAL, null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot render a stored payload held in memory", e);
        }
    }
}
