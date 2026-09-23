package cloud.jengu.dbo.fhir.validate;

import java.util.List;

/**
 * A compiled invariant, run against one element instance.
 *
 * <p>An invariant is compiled when the definition arrives, into a jsonpath the
 * database executes with {@code jsonb_path_match}. An answerer in its own
 * process executes the same text itself, so what it needs is a reader for the
 * grammar those paths actually reach for.
 *
 * <p><b>Two thirds of it, and that is measured rather than chosen.</b> Over a
 * face root's closure, 95 compiled paths carry 67 distinct rule keys, and 65
 * of the 95 — <b>68.4%</b> — use nothing but navigation and the boolean
 * connectives: a path, {@code exists}, {@code !}, {@code &&}, {@code ||}. The
 * rest compare ({@code ==}, {@code !=}, {@code <}, {@code >}), match
 * ({@code like_regex}, {@code starts with}) or filter ({@code ? (…)}), and
 * each of those is a piece of evaluator on its own.
 *
 * <p><b>What it does not understand, it does not answer.</b> The result is a
 * {@code Boolean} and null means cannot say — the same three answers
 * {@code dbo.invariant_holds} gives, and for the same reason: a document is
 * not wrong because a rule could not be run against it. A reader that guessed
 * at a construct it half-recognised would report conformance it never
 * established, which is the one thing this whole line of work exists not to
 * do.
 */
final class JsonPathPredicate {

    private final String text;
    private int at;

    private JsonPathPredicate(String text) {
        this.text = text;
    }

    /**
     * Whether the rule holds of this instance: true, false, or null where
     * this reader cannot say.
     */
    static Boolean holds(Object instance, String path) {
        try {
            JsonPathPredicate reader = new JsonPathPredicate(path);
            Boolean answer = reader.disjunction(instance);
            reader.spaces();
            return reader.at == reader.text.length() ? answer : null;
        } catch (RuntimeException notUnderstood) {
            return null;
        }
    }

    // --- the grammar, in precedence order ---

    private Boolean disjunction(Object instance) {
        Boolean left = conjunction(instance);
        while (peek("||")) {
            at += 2;
            Boolean right = conjunction(instance);
            left = or(left, right);
        }
        return left;
    }

    private Boolean conjunction(Object instance) {
        Boolean left = unary(instance);
        while (peek("&&")) {
            at += 2;
            Boolean right = unary(instance);
            left = and(left, right);
        }
        return left;
    }

    private Boolean unary(Object instance) {
        spaces();
        if (peek("!")) {
            at++;
            Boolean of = unary(instance);
            return of == null ? null : !of;
        }
        if (peek("(")) {
            at++;
            Boolean inside = disjunction(instance);
            spaces();
            expect(")");
            return inside;
        }
        if (peek("exists")) {
            at += "exists".length();
            spaces();
            expect("(");
            List<Object> found = navigate(instance);
            spaces();
            expect(")");
            return !found.isEmpty();
        }
        // Anything else is a comparison, a match or a filter: measured, not
        // implemented, and answered by nobody rather than badly.
        throw new JsonPathValues.Unreadable();
    }

    /**
     * Three-valued, as SQL's are: unknown and false is false, unknown and
     * true is unknown. A rule that could not be run half-way is still a rule
     * that could not be run.
     */
    private static Boolean and(Boolean left, Boolean right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return false;
        }
        return left == null || right == null ? null : true;
    }

    private static Boolean or(Boolean left, Boolean right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
            return true;
        }
        return left == null || right == null ? null : false;
    }

    // --- navigation ---

    /** What a path selects, shared with the reader an envelope uses. */
    private List<Object> navigate(Object instance) {
        int[] cursor = {at};
        List<Object> found = JsonPathValues.from(instance, text, cursor);
        at = cursor[0];
        return found;
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

    private void expect(String token) {
        spaces();
        if (!text.startsWith(token, at)) {
            throw new JsonPathValues.Unreadable();
        }
        at += token.length();
    }


}
