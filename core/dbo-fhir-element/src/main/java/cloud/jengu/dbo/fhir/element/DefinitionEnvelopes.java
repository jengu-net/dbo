package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.fhir.validate.Documents;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Map<String, Object> document = parsed(payload, typeName);
        Envelope envelope = new Envelope();
        for (DefinitionParameters.Parameter parameter : parameters) {
            String path = ElementEnvelopes.pathName(parameter.code());
            for (Object hit : evaluate(document, typeName, parameter.expression())) {
                add(envelope, parameter.type(), path, hit);
            }
        }
        meta(envelope, document);
        if (canonical) {
            canonical(envelope, document);
        }
        return envelope;
    }

    /**
     * The same envelope, driven by the parameters the cut COMPILED rather
     * than by expressions written out here.
     *
     * <p>The generalisation. What is written out above is five types' worth
     * of expressions and an evaluator for the FHIRPath they use, and it
     * exists because a definition arriving at a tenant is what the tenant
     * does not have yet — the bootstrap cannot read rows that the document
     * itself is about to create. Every OTHER type has its parameters
     * compiled to jsonpath when they arrive, in {@code definition_parameter},
     * and those need no evaluator of ours at all: they are run by the same
     * reader the checks run an invariant's condition with.
     *
     * <p>So the typed rules below serve both, which is the point. A token is
     * three questions and not one, a string is held lowercased and again
     * exactly, a date is the moment its span opens — stated once, for the
     * bootstrap and for everything else, rather than once per front end.
     *
     * <p><b>A parameter whose path this cannot run indexes nothing, and that
     * is a loss rather than a silence.</b> An envelope missing a key is a
     * search that finds nothing while looking exactly like an answer, so the
     * codes are returned rather than swallowed: a caller that cares can say
     * so, and one that does not gets the same behaviour the toolchain path
     * has for an expression it cannot evaluate.
     */
    static Envelope extract(List<DefinitionRows.Parameter> parameters, String typeName,
            byte[] payload, boolean canonical, List<String> declined) {
        Map<String, Object> document = parsed(payload, typeName);
        Envelope envelope = new Envelope();
        for (DefinitionRows.Parameter parameter : parameters) {
            if (!typeName.equals(parameter.base())) {
                continue;
            }
            String path = ElementEnvelopes.pathName(parameter.code());
            for (String compiled : parameter.paths()) {
                List<Object> hits = Documents.at(document, compiled);
                if (hits == null) {
                    if (declined != null && !declined.contains(parameter.code())) {
                        declined.add(parameter.code());
                    }
                    continue;
                }
                for (Object hit : hits) {
                    if (parameter.predicate() != null && !Boolean.TRUE.equals(
                            Documents.holds(hit, parameter.predicate()))) {
                        continue;
                    }
                    add(envelope, parameter.kind(), path, hit);
                }
            }
        }
        meta(envelope, document);
        if (canonical) {
            canonical(envelope, document);
        }
        return envelope;
    }

    /**
     * The document, refused the way the toolchain path refuses the same
     * bytes: a body that is not FHIR JSON is the caller's, not the server's.
     */
    private static Map<String, Object> parsed(byte[] payload, String typeName) {
        Map<String, Object> document;
        try {
            document = Documents.read(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                    + e.getMessage(), e);
        }
        if (document == null) {
            throw new IllegalArgumentException("body is not parseable FHIR JSON");
        }
        if (!typeName.equals(Documents.text(document.get("resourceType")))) {
            // The toolchain refuses a resource type it does not know, and a
            // set handed over with one is applied without it. Here the
            // document is read for exactly one type, so being any other is
            // the same refusal.
            throw new IllegalArgumentException("body is not parseable FHIR JSON: expected a "
                    + typeName + ", found '" + Documents.text(document.get("resourceType")) + "'");
        }
        return document;
    }

    /** What every resource carries and no search parameter names. */
    private static void meta(Envelope envelope, Map<String, Object> document) {
        if (!(document.get("meta") instanceof Map<?, ?> meta)) {
            return;
        }
        for (Object profile : items(meta, "profile")) {
            String url = primitive(profile);
            if (url != null && !url.isBlank()) {
                envelope.value("_profile", EnvelopeValue.of(url));
            }
        }
        for (Object tag : items(meta, "tag")) {
            if (tag instanceof Map<?, ?> coding) {
                ElementEnvelopes.tokenForms(envelope, "_tag",
                        field(coding, "system"), field(coding, "code"));
            }
        }
    }

    /** What a canonical resource is identified BY, beside what it is found by. */
    private static void canonical(Envelope envelope, Map<String, Object> document) {
        String url = Documents.text(document.get("url"));
        if (url != null) {
            envelope.identifier(cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url);
            envelope.value("url", EnvelopeValue.of(url));
        }
    }

    // ------------------------------------------------------------ the path

    private static List<Object> evaluate(Map<String, Object> document, String typeName,
            String expression) {
        List<Object> hits = new ArrayList<>();
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
            List<Object> focus = List.of((Object) document);
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
            for (Object hit : focus) {
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
    private static boolean subsumes(Object kept, Object candidate) {
        String text = Documents.text(kept);
        if (text != null) {
            return text.equals(Documents.text(candidate));
        }
        if (!(kept instanceof Map<?, ?> k) || !(candidate instanceof Map<?, ?> c)) {
            return false;
        }
        for (Map.Entry<?, ?> property : k.entrySet()) {
            Object theirs = c.get(property.getKey());
            if (theirs == null) {
                return false;
            }
            List<Object> mine = property.getValue() instanceof List<?> a
                    ? List.copyOf(a) : List.of(property.getValue());
            List<Object> others = theirs instanceof List<?> a ? List.copyOf(a) : List.of(theirs);
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

    private static List<Object> children(List<Object> focus, String name) {
        List<Object> out = new ArrayList<>();
        for (Object element : focus) {
            if (element instanceof Map<?, ?> object) {
                out.addAll(items(object, name));
            }
        }
        return out;
    }

    private static List<Object> filtered(List<Object> focus, String name, String literal) {
        List<Object> out = new ArrayList<>();
        for (Object element : focus) {
            if (element instanceof Map<?, ?> object && literal.equals(field(object, name))) {
                out.add(element);
            }
        }
        return out;
    }

    /** Descendants reached by stepping {@code name} again and again, a level at a time. */
    private static List<Object> repeated(List<Object> focus, String name) {
        List<Object> out = new ArrayList<>();
        List<Object> level = children(focus, name);
        while (!level.isEmpty()) {
            out.addAll(level);
            level = children(level, name);
        }
        return out;
    }

    private static List<Object> items(Map<?, ?> object, String name) {
        Object value = object.get(name);
        if (value == null) {
            return List.of();
        }
        return value instanceof List<?> array ? List.copyOf(array) : List.of(value);
    }

    /** A member's written text, for the shapes this reads by name. */
    private static String field(Map<?, ?> object, String name) {
        return Documents.text(object.get(name));
    }

    private static String capitalised(String type) {
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    // ---------------------------------------------------------- the values

    private static void add(Envelope envelope, String type, String path, Object hit) {
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
                if (value == null && hit instanceof Map<?, ?> period) {
                    value = field(period, "start");
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
    private static void token(Envelope envelope, String path, Object hit) {
        if (!(hit instanceof Map<?, ?> element)) {
            String value = primitive(hit);
            if (value != null) {
                envelope.value(path, EnvelopeValue.token(null, value));
            }
            return;
        }
        if (element.containsKey("coding")) {
            for (Object coding : items(element, "coding")) {
                if (coding instanceof Map<?, ?> c) {
                    ElementEnvelopes.tokenForms(envelope, path, field(c, "system"), field(c, "code"));
                }
            }
        } else if (element.containsKey("code")) {
            ElementEnvelopes.tokenForms(envelope, path, field(element, "system"),
                    field(element, "code"));
        } else if (element.containsKey("value")) {
            String system = field(element, "system");
            String value = field(element, "value");
            if (value != null) {
                ElementEnvelopes.tokenForms(envelope, path, system, value);
                if (system != null) {
                    envelope.identifier(system, value);
                }
            }
        }
    }

    private static void reference(Envelope envelope, String path, Object hit) {
        boolean isReference = hit instanceof Map;
        String reference = isReference ? field((Map<?, ?>) hit, "reference") : primitive(hit);
        if (reference != null) {
            int slash = reference.lastIndexOf('/');
            if (slash > 0) {
                String type = reference.substring(0, slash);
                int previous = type.lastIndexOf('/');
                envelope.reference(path, previous < 0 ? type : type.substring(previous + 1),
                        reference.substring(slash + 1));
            }
        }
        if (isReference && ((Map<?, ?>) hit).get("identifier") instanceof Map<?, ?> identifier
                && field(identifier, "system") != null) {
            ElementEnvelopes.tokenForms(envelope, path + "_identifier",
                    field(identifier, "system"), field(identifier, "value"));
        }
    }

    private static String primitive(Object hit) {
        return Documents.text(hit);
    }
}
