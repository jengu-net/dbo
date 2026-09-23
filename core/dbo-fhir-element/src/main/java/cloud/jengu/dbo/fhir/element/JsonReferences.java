package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A reference that is a question, answered without the element model.
 *
 * <p>The same rule {@link ElementReferences} applies — a field named {@code
 * reference} whose value carries a {@code ?} is a query, answered once when
 * the document is written — over the bytes rather than over a parsed tree.
 *
 * <p><b>Why it is here rather than there.</b> Parsing a document into an
 * {@code Element} is the last thing on the write path that needs a worker
 * context, and a worker context is a version's whole definition corpus: 225 MB
 * for a face's first tenant. Everything else the parse fed has turned out to
 * be a reading — the type, the claimed profiles, the engine's own stamp — and
 * this was the one piece of work. Done here, a tenant that has declared its
 * types decided and extracted by the database never builds one.
 *
 * <p>Copied token by token, so what is stored is what arrived except for the
 * references that were questions. The element model would have recomposed the
 * document in its own order and dropped what it did not recognise, which is a
 * second opinion about bytes their author already chose.
 */
final class JsonReferences {

    private static final JsonFactory JSON = JsonFactory.builder().build();

    private JsonReferences() {
    }

    /**
     * @return the document with every question answered, or the same bytes
     *         where there was nothing to answer
     */
    static byte[] resolve(byte[] payload, ElementReferences.Resolver resolver) {
        if (!carriesAQuestion(payload) && !carriesOurStamp(payload)) {
            return payload;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
        try (JsonParser in = JSON.createParser(payload);
                JsonGenerator gen = JSON.createGenerator(out)) {
            copy(in, gen, resolver, false);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite a document held in memory", e);
        }
        return out.toByteArray();
    }

    /**
     * Whether it is worth copying at all.
     *
     * <p>A question mark anywhere in the bytes, which is a cheap and generous
     * test: most documents have none and are handed straight back, and one
     * that has a {@code ?} in a free-text field costs a copy that changes
     * nothing rather than a wrong answer.
     */
    private static boolean carriesAQuestion(byte[] payload) {
        for (byte b : payload) {
            if (b == '?') {
                return true;
            }
        }
        return false;
    }

    private static void copy(JsonParser in, JsonGenerator gen,
            ElementReferences.Resolver resolver, boolean inReference) throws IOException {
        JsonToken token = in.nextToken();
        while (token != null) {
            switch (token) {
                case START_OBJECT -> {
                    gen.writeStartObject();
                    object(in, gen, resolver);
                }
                case START_ARRAY -> {
                    gen.writeStartArray();
                    array(in, gen, resolver);
                }
                default -> gen.copyCurrentEvent(in);
            }
            if (in.getParsingContext().inRoot()) {
                return;
            }
            token = in.nextToken();
        }
    }

    /**
     * Whether the engine's own stamp rode in as content.
     *
     * <p>{@code urn:dbo:shape} is re-stated from the store's own fact on every
     * serve, so a copy handed back — a converted form offered for
     * re-acceptance, a round trip through somebody's client — would be a
     * second answer to a question the store answers. It is dropped on the way
     * in rather than litigated.
     */
    private static boolean carriesOurStamp(byte[] payload) {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8)
                .contains(ElementAncestors.SHAPE_URL);
    }

    private static void object(JsonParser in, JsonGenerator gen,
            ElementReferences.Resolver resolver) throws IOException {
        while (in.nextToken() == JsonToken.FIELD_NAME) {
            String field = in.currentName();
            if ("extension".equals(field) && in.getParsingContext().getParent() != null
                    && "meta".equals(in.getParsingContext().getParent().getCurrentName())) {
                in.nextToken();
                java.util.List<String> kept = ourStampRemoved(in);
                if (!kept.isEmpty()) {
                    gen.writeFieldName(field);
                    gen.writeStartArray();
                    for (String one : kept) {
                        gen.writeRawValue(one);
                    }
                    gen.writeEndArray();
                }
                continue;
            }
            gen.writeFieldName(field);
            JsonToken value = in.nextToken();
            switch (value) {
                case START_OBJECT -> {
                    gen.writeStartObject();
                    object(in, gen, resolver);
                }
                case START_ARRAY -> {
                    gen.writeStartArray();
                    array(in, gen, resolver);
                }
                case VALUE_STRING -> {
                    String text = in.getText();
                    gen.writeString("reference".equals(field) ? answered(text, resolver) : text);
                }
                default -> gen.copyCurrentEvent(in);
            }
        }
        gen.writeEndObject();
    }

    private static void array(JsonParser in, JsonGenerator gen,
            ElementReferences.Resolver resolver) throws IOException {
        JsonToken token = in.nextToken();
        while (token != JsonToken.END_ARRAY) {
            switch (token) {
                case START_OBJECT -> {
                    gen.writeStartObject();
                    object(in, gen, resolver);
                }
                case START_ARRAY -> {
                    gen.writeStartArray();
                    array(in, gen, resolver);
                }
                default -> gen.copyCurrentEvent(in);
            }
            token = in.nextToken();
        }
        gen.writeEndArray();
    }

    /**
     * A meta.extension array with the engine's own stamp taken out, each
     * survivor as the bytes it arrived as.
     */
    private static java.util.List<String> ourStampRemoved(JsonParser in) throws IOException {
        java.util.List<String> kept = new java.util.ArrayList<>();
        JsonToken token = in.nextToken();
        while (token != JsonToken.END_ARRAY && token != null) {
            ByteArrayOutputStream one = new ByteArrayOutputStream(64);
            try (JsonGenerator gen = JSON.createGenerator(one)) {
                gen.copyCurrentStructure(in);
            }
            String text = one.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (!text.contains(ElementAncestors.SHAPE_URL)) {
                kept.add(text);
            }
            token = in.nextToken();
        }
        return kept;
    }

    /**
     * One reference, answered if it asked something.
     *
     * <p>Refused by name when it matches nothing: a question nobody answered
     * would be stored pointing at nothing, which is the one thing a reference
     * that is a question must never become.
     */
    private static String answered(String value, ElementReferences.Resolver resolver) {
        int question = value.indexOf('?');
        if (question <= 0) {
            return value;
        }
        String typeName = value.substring(0, question);
        String query = value.substring(question + 1);
        String id = resolver.resolve(typeName, query).orElseThrow(
                () -> new IllegalArgumentException("the reference '" + value
                        + "' matches nothing in this store — a reference that is a "
                        + "question is answered when the document is written, and an "
                        + "unanswered one would be stored pointing at nothing"));
        return typeName + "/" + id;
    }
}
