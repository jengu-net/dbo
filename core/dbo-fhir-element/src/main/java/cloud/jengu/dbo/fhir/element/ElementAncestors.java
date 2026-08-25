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

    /**
     * The engine's claims about a record, said in {@code meta} (#109): where a
     * streamed copy came from ({@code Meta.source}), and the handling class
     * this store enforces on it ({@code Meta.security}). Facts in, document
     * out — the face never fetches them.
     *
     * @param source   the upstream a copy was streamed from, or null for the
     *                 tenant's own records
     * @param handling the declared handling class's wire name, or null when
     *                 the type's handling is not one a spec can declare
     */
    record Stamps(String source, String handling) {
        static final Stamps NONE = new Stamps(null, null);
    }

    /** Where a streamed copy's source is a URI: the dependency, namespaced. */
    static String sourceUri(String dependency) {
        return dependency == null ? null : "urn:dbo:upstream:" + dependency;
    }

    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id,
            long versionId) {
        return rendered(context, payload, id, versionId, null, Stamps.NONE);
    }

    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id,
            long versionId, List<String> elements) {
        return rendered(context, payload, id, versionId, elements, Stamps.NONE);
    }

    /**
     * @param elements when given, the only elements encoded — the store's own
     *                 slots always survive, because a resource a client cannot
     *                 reference is not a smaller resource, it is a broken one
     */
    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id, long versionId,
            List<String> elements, Stamps stamps) {
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
                        meta(in, gen, versionId, stamps);
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
                stampsInto(gen, stamps, List.of());
                gen.writeEndObject();
            }
            gen.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot re-emit a stored payload held in memory", e);
        }
        return out.toByteArray();
    }

    /** The stored meta, with the store's version in it rather than beside it. */
    private static void meta(JsonParser in, JsonGenerator gen, long versionId, Stamps stamps)
            throws IOException {
        gen.writeObjectFieldStart("meta");
        boolean sawVersion = false;
        List<byte[]> kept = new java.util.ArrayList<>();
        while (in.nextToken() == JsonToken.FIELD_NAME) {
            String field = in.currentName();
            in.nextToken();
            if ("versionId".equals(field)) {
                in.skipChildren();
                gen.writeStringField("versionId", Long.toString(versionId));
                sawVersion = true;
            } else if ("source".equals(field) && stamps.source() != null) {
                // The engine's fact about custody wins over a claim that rode
                // in with the bytes -- same replace-not-append rule as
                // versionId, and for the same reason: a document with two
                // answers to one question answers neither.
                in.skipChildren();
            } else if ("security".equals(field) && stamps.handling() != null) {
                // Collected, not copied: the author's own security codings and
                // the engine's stamp belong in ONE array, so the array is
                // written once, below. Only codings claiming our system are
                // dropped -- they are re-stamped from the engine's current
                // fact rather than echoed from bytes that may predate a
                // handling change, and a served document that comes back in
                // does not accumulate one coding per round trip.
                kept.addAll(codingsWithoutOurs(in));
            } else {
                gen.writeFieldName(field);
                copy(in, gen);
            }
        }
        if (!sawVersion) {
            gen.writeStringField("versionId", Long.toString(versionId));
        }
        stampsInto(gen, stamps, kept);
        gen.writeEndObject();
    }

    /** urn:dbo:handling — the coding system Meta.security stamps carry. */
    static final String HANDLING_SYSTEM = "urn:dbo:handling";

    private static void stampsInto(JsonGenerator gen, Stamps stamps, List<byte[]> keptSecurity)
            throws IOException {
        if (stamps.source() != null) {
            gen.writeStringField("source", stamps.source());
        }
        if (stamps.handling() != null) {
            gen.writeArrayFieldStart("security");
            for (byte[] coding : keptSecurity) {
                gen.writeRawValue(new String(coding, java.nio.charset.StandardCharsets.UTF_8));
            }
            gen.writeStartObject();
            gen.writeStringField("system", HANDLING_SYSTEM);
            gen.writeStringField("code", stamps.handling());
            gen.writeEndObject();
            gen.writeEndArray();
        }
    }

    /**
     * The stored security codings, verbatim as bytes, minus any claiming our
     * system. Buffered rather than modelled: a coding may carry extensions
     * this copier must not reshape, so each element is token-copied whole
     * while its top-level {@code system} is noted in passing.
     */
    private static List<byte[]> codingsWithoutOurs(JsonParser in) throws IOException {
        List<byte[]> kept = new java.util.ArrayList<>();
        if (in.currentToken() != JsonToken.START_ARRAY) {
            // not an array: malformed for FHIR, preserved for us -- buffer the
            // single value and keep it, whatever it is
            kept.add(buffered(in).bytes());
            return kept;
        }
        while (in.nextToken() != JsonToken.END_ARRAY) {
            Buffered coding = buffered(in);
            if (!HANDLING_SYSTEM.equals(coding.topLevelSystem())) {
                kept.add(coding.bytes());
            }
        }
        return kept;
    }

    private record Buffered(byte[] bytes, String topLevelSystem) {}

    /** One value token-copied into its own bytes, its top-level system noted. */
    private static Buffered buffered(JsonParser in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        String system = null;
        try (JsonGenerator gen = JSON.createGenerator(buffer)) {
            if (in.currentToken() == JsonToken.START_OBJECT) {
                gen.writeStartObject();
                while (in.nextToken() == JsonToken.FIELD_NAME) {
                    String name = in.currentName();
                    gen.writeFieldName(name);
                    in.nextToken();
                    if ("system".equals(name) && in.currentToken() == JsonToken.VALUE_STRING) {
                        system = in.getText();
                    }
                    copy(in, gen);
                }
                gen.writeEndObject();
            } else {
                copy(in, gen);
            }
        }
        return new Buffered(buffer.toByteArray(), system);
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
