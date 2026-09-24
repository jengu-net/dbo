package cloud.jengu.dbo.fhir.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a compiled path selects out of a document.
 *
 * <p>The navigation half of a jsonpath, shared by the two things that run one:
 * an invariant, which asks whether what it selects exists, and a search
 * parameter, whose selections are the values an envelope holds.
 *
 * <p><b>Lax, because Postgres is.</b> A member accessor applied to an array
 * unwraps it and applies to each member, so a path written for one name
 * answers over all of them. Matching that is not a nicety — both readers are
 * compared against the same statement running in the database, and a strict
 * reading would disagree about every repeating element in the corpus.
 *
 * <p><b>Null is a real answer.</b> A path using something this does not
 * implement selects nothing KNOWN rather than nothing, and the difference
 * decides whether a caller may treat silence as an answer. An invariant
 * treats it as cannot-say; an envelope records the parameter as declined,
 * because a key quietly absent is a search that finds nothing and looks like
 * an answer.
 */
final class JsonPathValues {

    private final String text;
    private int at;

    private JsonPathValues(String text) {
        this.text = text;
    }

    /** What the path selects, or null where this reader cannot run it. */
    static List<Object> at(Object instance, String path) {
        try {
            JsonPathValues reader = new JsonPathValues(path);
            List<Object> found = reader.navigate(instance);
            reader.spaces();
            return reader.at == reader.text.length() ? found : null;
        } catch (RuntimeException notUnderstood) {
            return null;
        }
    }

    /** The same, starting where the caller already is in the text. */
    static List<Object> from(Object instance, String text, int[] cursor) {
        JsonPathValues reader = new JsonPathValues(text);
        reader.at = cursor[0];
        List<Object> found = reader.navigate(instance);
        cursor[0] = reader.at;
        return found;
    }

    private List<Object> navigate(Object instance) {
        spaces();
        if (!peek("$") && !peek("@")) {
            throw new Unreadable();
        }
        at++;
        List<Object> cursor = new ArrayList<>();
        cursor.add(instance);
        while (at < text.length()) {
            if (peek("[*]")) {
                at += 3;
                cursor = unwrapped(cursor);
                continue;
            }
            if (peek("?")) {
                // A filter over the members reached so far. One form —
                // equality, optionally joined by and — which is what both the
                // slices and the nine filtered search parameters use.
                at++;
                String predicate = balanced();
                List<Object> kept = new ArrayList<>();
                for (Object one : unwrapped(cursor)) {
                    if (SlicePredicate.claims(one, predicate)) {
                        kept.add(one);
                    }
                }
                if (!SlicePredicate.understood(predicate)) {
                    throw new Unreadable();
                }
                cursor = kept;
                continue;
            }
            if (text.charAt(at) != '.') {
                break;
            }
            at++;
            String key = quoted();
            List<Object> next = new ArrayList<>();
            for (Object one : unwrapped(cursor)) {
                if (one instanceof Map<?, ?> object) {
                    Object held = object.get(key);
                    if (held != null) {
                        next.add(held);
                    }
                }
            }
            cursor = next;
        }
        return unwrapped(cursor);
    }

    /** The parenthesised text of a filter, brackets balanced. */
    private String balanced() {
        spaces();
        if (at >= text.length() || text.charAt(at) != '(') {
            throw new Unreadable();
        }
        int from = at;
        int depth = 0;
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return text.substring(from, at);
                }
            }
        }
        throw new Unreadable();
    }

    /** Arrays flattened one level, which is what lax mode does at each step. */
    static List<Object> unwrapped(List<Object> values) {
        List<Object> out = new ArrayList<>();
        for (Object one : values) {
            if (one instanceof List<?> many) {
                out.addAll(many);
            } else {
                out.add(one);
            }
        }
        return out;
    }

    private String quoted() {
        spaces();
        if (at >= text.length() || text.charAt(at) != '"') {
            throw new Unreadable();
        }
        int from = ++at;
        while (at < text.length() && text.charAt(at) != '"') {
            at++;
        }
        if (at >= text.length()) {
            throw new Unreadable();
        }
        return text.substring(from, at++);
    }

    private void spaces() {
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
    }

    private boolean peek(String token) {
        spaces();
        return text.startsWith(token, at);
    }

    /** A construct this reader does not implement. */
    static final class Unreadable extends RuntimeException {
        Unreadable() {
            super(null, null, false, false);
        }
    }
}
