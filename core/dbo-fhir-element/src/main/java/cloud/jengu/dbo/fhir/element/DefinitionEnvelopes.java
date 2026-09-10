package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.model.JsonPrimitive;
import org.hl7.fhir.utilities.json.parser.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A definition's envelope, read from its JSON with no worker context.
 *
 * <p>The toolchain's extraction parses a document into the element model,
 * and parsing a StructureDefinition needs the StructureDefinition of
 * StructureDefinition — a context that already holds the version. That is
 * fine for a Patient and circular for the version itself: the definitions
 * arriving at a tenant are exactly what the tenant does not have yet. So the
 * five definition types are indexed from their JSON, along the same
 * expressions, and the answer is held to be identical to the toolchain's —
 * over every definition a face carries, by test — rather than merely similar.
 *
 * <p>What the expressions over these types use, and all this evaluates: a
 * dotted path from the type, unions with {@code |}, a choice resolved with
 * {@code as T} or {@code ofType(T)}, {@code where(field='literal')}, and
 * {@code repeat(name)}. Anything else yields nothing, as the toolchain path
 * also does for an expression it cannot run.
 */
final class DefinitionEnvelopes {

    private static final Pattern WHERE = Pattern.compile("where\\((\\w+)='([^']*)'\\)");
    private static final Pattern REPEAT = Pattern.compile("repeat\\((\\w+)\\)");
    private static final Pattern OF_TYPE = Pattern.compile("ofType\\((\\w+)\\)");

    private DefinitionEnvelopes() {
    }

    static Envelope extract(List<DefinitionParameters.Parameter> parameters, String typeName,
            byte[] payload, boolean canonical) {
        JsonObject document;
        try {
            document = JsonParser.parseObject(new ByteArrayInputStream(payload));
        } catch (IOException | RuntimeException e) {
            // The refusal the toolchain path gives for the same bytes: a body
            // that is not FHIR JSON is the caller's, not the server's.
            throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                    + e.getMessage(), e);
        }
        if (!typeName.equals(document.asString("resourceType"))) {
            // The toolchain refuses a resource type it does not know, and a
            // set handed over with one is applied without it. Here the
            // document is read for exactly one type, so being any other is
            // the same refusal.
            throw new IllegalArgumentException("body is not parseable FHIR JSON: expected a "
                    + typeName + ", found '" + document.asString("resourceType") + "'");
        }
        Envelope envelope = new Envelope();
        for (DefinitionParameters.Parameter parameter : parameters) {
            String path = ElementEnvelopes.pathName(parameter.code());
            for (JsonElement hit : evaluate(document, typeName, parameter.expression())) {
                add(envelope, parameter.type(), path, hit);
            }
        }
        if (document.hasObject("meta")) {
            JsonObject meta = document.getJsonObject("meta");
            for (JsonElement profile : items(meta, "profile")) {
                String url = primitive(profile);
                if (url != null && !url.isBlank()) {
                    envelope.value("_profile", EnvelopeValue.of(url));
                }
            }
            for (JsonElement tag : items(meta, "tag")) {
                if (tag instanceof JsonObject coding) {
                    ElementEnvelopes.tokenForms(envelope, "_tag",
                            coding.asString("system"), coding.asString("code"));
                }
            }
        }
        if (canonical) {
            String url = document.asString("url");
            if (url != null) {
                envelope.identifier(cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url);
                envelope.value("url", EnvelopeValue.of(url));
            }
        }
        return envelope;
    }

    // ------------------------------------------------------------ the path

    private static List<JsonElement> evaluate(JsonObject document, String typeName,
            String expression) {
        List<JsonElement> hits = new ArrayList<>();
        String[] terms = expression.split("\\|");
        // FHIRPath's union is a set: a value both sides yield is yielded
        // once, and the toolchain's answer over `snapshot | differential`
        // carries each path once however many elements share it.
        boolean union = terms.length > 1;
        for (String term : terms) {
            term = term.trim();
            while (term.startsWith("(") && term.endsWith(")")) {
                term = term.substring(1, term.length() - 1).trim();
            }
            String asType = null;
            int as = term.indexOf(" as ");
            if (as > 0) {
                asType = term.substring(as + 4).trim();
                term = term.substring(0, as).trim();
            }
            String[] steps = term.split("\\.");
            if (steps.length == 0 || !steps[0].equals(typeName)) {
                continue;
            }
            List<JsonElement> focus = List.of(document);
            for (int i = 1; i < steps.length && !focus.isEmpty(); i++) {
                String step = steps[i];
                Matcher where = WHERE.matcher(step);
                Matcher repeat = REPEAT.matcher(step);
                Matcher ofType = OF_TYPE.matcher(step);
                if (where.matches()) {
                    focus = filtered(focus, where.group(1), where.group(2));
                } else if (repeat.matches()) {
                    focus = repeated(focus, repeat.group(1));
                } else if (ofType.matches()) {
                    // already resolved when the choice was stepped into
                    continue;
                } else {
                    String type = i + 1 < steps.length && OF_TYPE.matcher(steps[i + 1]).matches()
                            ? OF_TYPE.matcher(steps[i + 1]).replaceAll("$1")
                            : i + 1 == steps.length ? asType : null;
                    focus = children(focus, type == null ? step : step + capitalised(type));
                }
            }
            for (JsonElement hit : focus) {
                if (!union || hits.stream().noneMatch(kept -> subsumes(kept, hit))) {
                    hits.add(hit);
                }
            }
        }
        return hits;
    }

    /**
     * Whether the toolchain's union would take {@code candidate} for
     * {@code kept}. Its deep equality walks the children of the element
     * already held and asks the newcomer for each — never the other way
     * round — so a newcomer carrying everything the held one carries, and
     * more, is "equal" to it and dropped. An Identifier with a {@code use}
     * beside one without is the case a face actually ships. Mirrored
     * exactly, because the index is promised to be the toolchain's index and
     * not a better one.
     */
    private static boolean subsumes(JsonElement kept, JsonElement candidate) {
        if (kept instanceof JsonPrimitive k) {
            return candidate instanceof JsonPrimitive c && k.getValue().equals(c.getValue());
        }
        if (!(kept instanceof JsonObject k) || !(candidate instanceof JsonObject c)) {
            return false;
        }
        for (var property : k.getProperties()) {
            JsonElement theirs = c.get(property.getName());
            if (theirs == null) {
                return false;
            }
            List<JsonElement> mine = property.getValue() instanceof JsonArray a ? a.getItems() : List.of(property.getValue());
            List<JsonElement> others = theirs instanceof JsonArray a ? a.getItems() : List.of(theirs);
            if (mine.size() != others.size()) {
                return false;
            }
            for (int i = 0; i < mine.size(); i++) {
                if (!subsumes(mine.get(i), others.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<JsonElement> children(List<JsonElement> focus, String name) {
        List<JsonElement> out = new ArrayList<>();
        for (JsonElement element : focus) {
            if (element instanceof JsonObject object) {
                out.addAll(items(object, name));
            }
        }
        return out;
    }

    private static List<JsonElement> filtered(List<JsonElement> focus, String field, String literal) {
        List<JsonElement> out = new ArrayList<>();
        for (JsonElement element : focus) {
            if (element instanceof JsonObject object && literal.equals(object.asString(field))) {
                out.add(element);
            }
        }
        return out;
    }

    /** Descendants reached by stepping {@code name} again and again, a level at a time. */
    private static List<JsonElement> repeated(List<JsonElement> focus, String name) {
        List<JsonElement> out = new ArrayList<>();
        List<JsonElement> level = children(focus, name);
        while (!level.isEmpty()) {
            out.addAll(level);
            level = children(level, name);
        }
        return out;
    }

    private static List<JsonElement> items(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null) {
            return List.of();
        }
        return value instanceof JsonArray array ? array.getItems() : List.of(value);
    }

    private static String capitalised(String type) {
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    // ---------------------------------------------------------- the values

    private static void add(Envelope envelope, String type, String path, JsonElement hit) {
        switch (type) {
            case "token" -> token(envelope, path, hit);
            case "string" -> {
                String value = primitive(hit);
                if (value != null) {
                    envelope.value(path, EnvelopeValue.of(value.toLowerCase()));
                    envelope.value(path + "_xct", EnvelopeValue.of(value));
                }
            }
            case "uri" -> {
                String value = primitive(hit);
                if (value != null) {
                    envelope.value(path, EnvelopeValue.of(value));
                }
            }
            case "date" -> {
                String value = primitive(hit);
                if (value == null && hit instanceof JsonObject period) {
                    value = period.asString("start");
                }
                Instant instant = ElementEnvelopes.instant(value);
                if (instant != null) {
                    envelope.value(path, EnvelopeValue.of(instant));
                }
            }
            case "number" -> {
                String value = primitive(hit);
                if (value != null) {
                    try {
                        envelope.value(path, EnvelopeValue.of(new BigDecimal(value)));
                    } catch (NumberFormatException e) {
                        // a number-typed parameter over something that is not one
                    }
                }
            }
            case "reference" -> reference(envelope, path, hit);
            default -> {
                // composite, quantity, special: not indexed here, as not there
            }
        }
    }

    /**
     * The element's kind, which the JSON does not say, read from its shape:
     * codings make a CodeableConcept, a code beside a system a Coding, and a
     * value an Identifier. Those are the token-typed elements of the
     * definition types; a shape this does not know indexes nothing, as the
     * toolchain path indexes nothing for an element it cannot name.
     */
    private static void token(Envelope envelope, String path, JsonElement hit) {
        if (!(hit instanceof JsonObject element)) {
            String value = primitive(hit);
            if (value != null) {
                envelope.value(path, EnvelopeValue.token(null, value));
            }
            return;
        }
        if (element.has("coding")) {
            for (JsonElement coding : items(element, "coding")) {
                if (coding instanceof JsonObject c) {
                    ElementEnvelopes.tokenForms(envelope, path, c.asString("system"), c.asString("code"));
                }
            }
        } else if (element.has("code")) {
            ElementEnvelopes.tokenForms(envelope, path, element.asString("system"), element.asString("code"));
        } else if (element.has("value")) {
            String system = element.asString("system");
            String value = element.asString("value");
            if (value != null) {
                ElementEnvelopes.tokenForms(envelope, path, system, value);
                if (system != null) {
                    envelope.identifier(system, value);
                }
            }
        }
    }

    private static void reference(Envelope envelope, String path, JsonElement hit) {
        boolean isReference = hit instanceof JsonObject;
        String reference = isReference ? ((JsonObject) hit).asString("reference") : primitive(hit);
        if (reference != null) {
            int slash = reference.lastIndexOf('/');
            if (slash > 0) {
                String type = reference.substring(0, slash);
                int previous = type.lastIndexOf('/');
                envelope.reference(path, previous < 0 ? type : type.substring(previous + 1),
                        reference.substring(slash + 1));
            }
        }
        if (isReference && ((JsonObject) hit).hasObject("identifier")) {
            JsonObject identifier = ((JsonObject) hit).getJsonObject("identifier");
            if (identifier.asString("system") != null) {
                ElementEnvelopes.tokenForms(envelope, path + "_identifier",
                        identifier.asString("system"), identifier.asString("value"));
            }
        }
    }

    private static String primitive(JsonElement hit) {
        return hit instanceof JsonPrimitive primitive ? primitive.getValue() : null;
    }
}
