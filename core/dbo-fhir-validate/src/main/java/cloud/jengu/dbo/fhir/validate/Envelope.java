package cloud.jengu.dbo.fhir.validate;

import cloud.jengu.dbo.fhir.index.DefinitionRows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The values a search asks by, pulled out of a document by the compiled
 * search parameters.
 *
 * <p>The other half of item 025's step 9. Reading and writing a document
 * losslessly needed no type knowledge at all; extracting the envelope does,
 * and it is the last thing on the write path that reached for the element
 * model.
 *
 * <p><b>The typed rule is the contract, not the expression that found the
 * value.</b> A token is three questions and not one; a string is asked
 * case-insensitively and, with {@code :exact}, case-sensitively; a date is a
 * span written at whatever precision its author had and is indexed by the
 * moment that span opens. Those rules are held here exactly as
 * {@code dbo.envelope_pairs} holds them, because the two are compared over
 * everything the version publishes.
 *
 * <p><b>A missing key is worse than a missing refusal.</b> A checker that
 * declines a rule loses a refusal, which looks like a correct document. An
 * envelope that declines a parameter loses a KEY, and a search by it then
 * finds nothing while looking exactly like an answer. So a parameter whose
 * path this cannot evaluate is reported by {@link #declined()} rather than
 * quietly skipped — 92.1% of the compiled parameters over a face root's
 * declared types are plain navigation, and the residue is a list to finish
 * rather than a majority to be satisfied with.
 *
 * <p>Note on where this lives: it is not a check, and the module it sits in
 * is named for checks. What it shares with them is the index and the reader
 * that runs a compiled path over a document, which is why it is here rather
 * than in a module of its own.
 */
public final class Envelope {

    private final List<DefinitionRows.Parameter> parameters;
    private final List<String> declined = new ArrayList<>();

    private Envelope(List<DefinitionRows.Parameter> parameters) {
        this.parameters = parameters;
    }

    public static Envelope over(List<DefinitionRows.Parameter> parameters) {
        return new Envelope(parameters);
    }

    /** Parameters whose compiled path this reader could not run, by code. */
    public List<String> declined() {
        return List.copyOf(declined);
    }

    /**
     * The envelope of one document: the key a search asks by, and its values.
     *
     * <p>Sorted, and the values in the order the paths found them, because
     * this is compared against what the database builds and an order that
     * differed would be a difference nobody meant.
     */
    public Map<String, List<Object>> of(byte[] document, String type) {
        Map<String, Object> tree = JsonDocument.of(document);
        Map<String, List<Object>> envelope = new TreeMap<>();
        if (tree == null) {
            return envelope;
        }
        for (DefinitionRows.Parameter parameter : parameters) {
            if (!type.equals(parameter.base())) {
                continue;
            }
            for (String path : parameter.paths()) {
                List<Object> hits = JsonPathValues.at(tree, path);
                if (hits == null) {
                    if (!declined.contains(parameter.code())) {
                        declined.add(parameter.code());
                    }
                    continue;
                }
                for (Object hit : hits) {
                    if (parameter.predicate() != null && !Boolean.TRUE.equals(
                            JsonPathPredicate.holds(hit, parameter.predicate()))) {
                        continue;
                    }
                    pairs(key(parameter.code()), parameter.kind(), hit, envelope);
                }
            }
        }
        return envelope;
    }

    /**
     * The key an envelope holds a parameter's values under.
     *
     * <p>A search parameter's code may carry a hyphen and an envelope path may
     * not, so it folds to an underscore — the same fold {@code
     * dbo.envelope_key} makes, and a convention spelt out in two places is one
     * that drifts in one of them.
     */
    private static String key(String code) {
        return code.replace('-', '_');
    }

    private void pairs(String key, String kind, Object hit, Map<String, List<Object>> into) {
        if (hit == null) {
            return;
        }
        String text = hit instanceof String one ? one : null;
        switch (kind == null ? "" : kind) {
            case "token" -> token(key, text, hit, into);
            case "string" -> {
                if (text != null) {
                    add(into, key, Map.of("t", "str", "v", text.toLowerCase()));
                    add(into, key + "_xct", Map.of("t", "str", "v", text));
                }
            }
            // as written: a uri is case-sensitive
            case "uri" -> {
                if (text != null) {
                    add(into, key, Map.of("t", "str", "v", text));
                }
            }
            case "number" -> {
                if (text != null && text.matches("-?[0-9]+(\\.[0-9]+)?")) {
                    add(into, key, Map.of("t", "num", "v", text));
                }
            }
            case "date" -> {
                String written = text != null ? text : field(hit, "start");
                String moment = Dates.key(written);
                if (moment != null) {
                    add(into, key, Map.of("t", "date", "v", moment));
                }
            }
            case "reference" -> {
                String written = field(hit, "reference");
                String value = written != null ? written : text;
                if (value != null && value.indexOf('/') >= 0) {
                    int last = value.lastIndexOf('/');
                    String before = value.substring(0, last);
                    int slash = before.lastIndexOf('/');
                    add(into, key, new LinkedHashMap<>(Map.of(
                            "t", "ref",
                            "tt", slash < 0 ? before : before.substring(slash + 1),
                            "ti", value.substring(last + 1))));
                }
            }
            // quantity and composite produce no pairs, here as there: the
            // database has no branch for them either, so a type asked after by
            // one is asked after by nothing on both sides.
            default -> { }
        }
    }

    /**
     * A token in the three shapes a search can ask for it.
     *
     * <p>{@code sys|code} is this code in this system, {@code code} is this
     * code in any system, and {@code sys|} is anything at all in this system.
     * The index answers by containment, so a form it does not carry is a
     * search that silently finds nothing.
     */
    private void token(String key, String text, Object hit, Map<String, List<Object>> into) {
        if (text != null) {
            add(into, key, Map.of("t", "tokc", "v", text));
            return;
        }
        if (!(hit instanceof Map<?, ?> object)) {
            return;
        }
        if (object.get("coding") instanceof List<?> codings) {
            for (Object coding : codings) {
                forms(key, field(coding, "system"), field(coding, "code"), into);
            }
            return;
        }
        if (object.containsKey("code")) {
            forms(key, field(hit, "system"), field(hit, "code"), into);
            return;
        }
        if (object.containsKey("value")) {
            forms(key, field(hit, "system"), field(hit, "value"), into);
        }
    }

    private void forms(String key, String system, String code,
            Map<String, List<Object>> into) {
        if (code == null) {
            return;
        }
        if (system == null) {
            add(into, key, Map.of("t", "tokc", "v", code));
            return;
        }
        add(into, key, new LinkedHashMap<>(Map.of("t", "tok", "s", system, "v", code)));
        add(into, key, Map.of("t", "toks", "v", system));
        add(into, key, Map.of("t", "tokc", "v", code));
    }

    private static String field(Object held, String name) {
        return held instanceof Map<?, ?> object && object.get(name) instanceof String text
                ? text : null;
    }

    private static void add(Map<String, List<Object>> into, String key, Object value) {
        into.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
}
