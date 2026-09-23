package cloud.jengu.dbo.fhir.element;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 025 step 9, spiked: what it would take to stop parsing through
 * {@code elementmodel}.
 *
 * <p>This is the move the decision at step 4 rests on, which is why it is
 * spiked out of dependency order. Every write goes through
 * {@code ElementPayloads.read}, which parses into an
 * {@code elementmodel.Element} and therefore needs a POPULATED context —
 * whether or not anything validates. So a finished checker over the index
 * removes no megabyte at all while this remains. If the payload path cannot
 * leave the element model, steps 5 to 8 are a faster cardinality check and
 * nothing more, and the item should say so and stop.
 *
 * <p>Two questions, because the payload path does two things: it carries a
 * document in and out, and it extracts the envelope a search reads.
 */
final class WhatThePayloadPathWouldNeedTest {

    /**
     * A document in and out, with no context anywhere.
     *
     * <p>The bar is not "a JSON tree round-trips" — that is trivially true.
     * It is that nothing the WIRE carries is lost, over the release's own
     * documents rather than over something written to pass: order, repeats,
     * the {@code _field} form a primitive's extensions arrive in, and above
     * all a decimal's written precision, which FHIR makes significant and
     * which a parser reading numbers as doubles silently destroys.
     */
    @Test
    void aDocumentGoesInAndComesBackUnchanged() throws IOException {
        Map<String, byte[]> corpus = AThirdAnswererChecksCardinalityTest.carriedResourcesOfR5();
        List<String> lost = new ArrayList<>();
        int checked = 0;
        long bytes = 0;
        for (Map.Entry<String, byte[]> one : corpus.entrySet()) {
            byte[] original = one.getValue();
            byte[] returned = write(read(original));
            bytes += original.length;
            checked++;
            if (!canonical(original).equals(canonical(returned))) {
                lost.add(one.getKey());
            }
        }

        System.out.printf("%n=== a document in and out, no context ===%n");
        System.out.printf("documents  %d   bytes %d%n", checked, bytes);
        System.out.printf("altered    %d%n", lost.size());
        lost.stream().limit(5).forEach(l -> System.out.println("  " + l));

        assertTrue(checked > 1000, "too few documents to mean anything: " + checked);
        assertEquals(List.of(), lost, "the payload path altered documents");

        // The precision case, stated separately because the corpus may not
        // contain one and it is the failure that would be silent.
        byte[] precise = """
                {"resourceType":"Observation","valueQuantity":{"value":1.500}}"""
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(new String(write(read(precise)), StandardCharsets.UTF_8).contains("1.500"),
                "a decimal's written precision was lost, which FHIR makes significant");
    }

    /**
     * How much of the envelope a typed walk could extract on its own.
     *
     * <p>The other half of the payload path. A search parameter is a FHIRPath
     * expression, and the toolchain evaluates it against the element model —
     * so if most expressions need a real engine, leaving the element model
     * means carrying an engine anyway and the win shrinks to the definitions.
     *
     * <p>This counts rather than argues: every SearchParameter the release
     * carries, sorted by whether a walk over a JSON tree with the index for
     * types could evaluate it.
     */
    @Test
    void howMuchOfTheEnvelopeIsAPlainPath() throws IOException {
        List<String> expressions = new ArrayList<>();
        for (CarriedDefinitions.Carried carried : CarriedDefinitions.forVersion("r5")) {
            for (var indexed : CarriedDefinitions.indexed(carried, "SearchParameter")) {
                try (var in = CarriedDefinitions.read(indexed)) {
                    String expression = fieldOf(in.readAllBytes(), "expression");
                    if (expression != null && !expression.isBlank()) {
                        expressions.add(expression);
                    }
                }
            }
        }

        Map<String, Integer> byShape = new TreeMap<>();
        for (String expression : expressions) {
            byShape.merge(shapeOf(expression), 1, Integer::sum);
        }
        int plain = byShape.getOrDefault("a plain path", 0)
                + byShape.getOrDefault("plain paths joined by |", 0);

        System.out.printf("%n=== what a search parameter's expression looks like, r5 ===%n");
        System.out.printf("expressions %d%n", expressions.size());
        byShape.forEach((shape, count) -> System.out.printf("  %-28s %5d  %4.1f%%%n",
                shape, count, 100.0 * count / expressions.size()));
        System.out.printf("a typed walk could evaluate  %d of %d, %.1f%%%n",
                plain, expressions.size(), 100.0 * plain / expressions.size());
        System.out.println();

        assertTrue(expressions.size() > 500, "too few expressions read: " + expressions.size());
    }

    /** What kind of expression this is, from the cheapest shape upwards. */
    private static String shapeOf(String expression) {
        String trimmed = expression.trim();
        if (trimmed.matches("[A-Za-z]+(\\.[A-Za-z]+)*")) {
            return "a plain path";
        }
        if (trimmed.matches("[A-Za-z]+(\\.[A-Za-z]+)*(\\s*\\|\\s*[A-Za-z]+(\\.[A-Za-z]+)*)+")) {
            return "plain paths joined by |";
        }
        if (trimmed.contains(".as(") || trimmed.contains(" as ")) {
            return "a path with a type cast";
        }
        if (trimmed.contains(".where(")) {
            return "a path with where()";
        }
        if (trimmed.contains(".resolve()")) {
            return "a path with resolve()";
        }
        if (trimmed.contains(".ofType(")) {
            return "a path with ofType()";
        }
        if (trimmed.contains(".extension(")) {
            return "an extension lookup";
        }
        return "something else";
    }

    // --- the payload path, such as it is ---

    /**
     * A document as maps, lists and the TEXT of its scalars.
     *
     * <p>The text, not the value: {@code 1.500} read as a double comes back
     * {@code 1.5}, and in FHIR those are different statements about how
     * precisely something was measured.
     */
    private static Object read(byte[] document) throws IOException {
        try (JsonParser p = new JsonFactory().createParser(document)) {
            p.nextToken();
            return value(p);
        }
    }

    /** One scalar, carrying whether it was written as a string. */
    private record Scalar(String text, boolean quoted) {
    }

    private static Object value(JsonParser p) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_OBJECT) {
            Map<String, Object> object = new LinkedHashMap<>();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                object.put(field, value(p));
            }
            return object;
        }
        if (t == JsonToken.START_ARRAY) {
            List<Object> many = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                many.add(value(p));
            }
            return many;
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        return new Scalar(p.getText(), t == JsonToken.VALUE_STRING);
    }

    private static byte[] write(Object document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = new JsonFactory().createGenerator(out)) {
            emit(g, document);
        }
        return out.toByteArray();
    }

    private static void emit(JsonGenerator g, Object node) throws IOException {
        switch (node) {
            case null -> g.writeNull();
            case Map<?, ?> object -> {
                g.writeStartObject();
                for (Map.Entry<?, ?> entry : object.entrySet()) {
                    g.writeFieldName(String.valueOf(entry.getKey()));
                    emit(g, entry.getValue());
                }
                g.writeEndObject();
            }
            case List<?> many -> {
                g.writeStartArray();
                for (Object one : many) {
                    emit(g, one);
                }
                g.writeEndArray();
            }
            case Scalar scalar -> {
                if (scalar.quoted()) {
                    g.writeString(scalar.text());
                } else {
                    g.writeRawValue(scalar.text());
                }
            }
            default -> g.writeString(String.valueOf(node));
        }
    }

    /**
     * A document reduced to something two of them can be compared by.
     *
     * <p>Not the bytes: this path re-emits without the original's whitespace,
     * and comparing those would measure the formatting. Field order IS kept,
     * because a repeat's order is meaningful in FHIR and a canonical form that
     * sorted it would hide exactly the loss worth catching.
     */
    private static String canonical(byte[] document) throws IOException {
        StringBuilder out = new StringBuilder();
        describe(read(document), out);
        return out.toString();
    }

    private static void describe(Object node, StringBuilder out) {
        switch (node) {
            case null -> out.append("null;");
            case Map<?, ?> object -> {
                out.append('{');
                object.forEach((k, v) -> {
                    out.append(k).append('=');
                    describe(v, out);
                });
                out.append('}');
            }
            case List<?> many -> {
                out.append('[');
                many.forEach(one -> describe(one, out));
                out.append(']');
            }
            case Scalar scalar -> out.append(scalar.quoted() ? '"' : '#')
                    .append(scalar.text()).append(';');
            default -> out.append(node).append(';');
        }
    }

    /** One top-level string field, read without building anything. */
    private static String fieldOf(byte[] document, String wanted) throws IOException {
        try (JsonParser p = new JsonFactory().createParser(document)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if (field.equals(wanted)) {
                    return p.getValueAsString();
                }
                if (p.currentToken() == JsonToken.START_OBJECT
                        || p.currentToken() == JsonToken.START_ARRAY) {
                    p.skipChildren();
                }
            }
        }
        return null;
    }
}
