package cloud.jengu.dbo.fhir.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which members of a repeating element a slice claims.
 *
 * <p>A slice is located by a predicate over the members of the element it
 * slices, and the database gets that from Postgres because the predicate is
 * part of the jsonpath it locates by. Anything in heap evaluates it itself.
 *
 * <p><b>One form, and that is measured rather than assumed.</b> Over
 * everything a face root holds, 203 of 13,991 located rows carry a predicate
 * and every one of them is equality, optionally joined by {@code &&}, over a
 * path of one to three segments — no comparison, no regex, no existence test.
 * So this evaluates that form and refuses to guess at any other: a predicate
 * it does not recognise claims nothing, which leaves the slice empty and its
 * minimum unmet, and a test fails the moment a shape appears that is not
 * this one.
 */
final class SlicePredicate {

    private SlicePredicate() {
    }

    /** Whether this member is one the slice claims. */
    static boolean claims(Object member, String predicate) {
        List<String[]> terms = termsOf(predicate);
        if (terms.isEmpty()) {
            return false;
        }
        for (String[] term : terms) {
            if (!holds(member, term[0], term[1])) {
                return false;
            }
        }
        return true;
    }

    /** Whether this is a form the evaluator understands at all. */
    static boolean understood(String predicate) {
        return !termsOf(predicate).isEmpty();
    }

    /**
     * The predicate as path-and-value pairs, or empty where it is not the
     * one form.
     */
    private static List<String[]> termsOf(String predicate) {
        List<String[]> terms = new ArrayList<>();
        String body = predicate.trim();
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length() - 1);
        }
        for (String term : body.split("&&")) {
            int equals = term.indexOf("==");
            if (equals < 0) {
                return List.of();
            }
            String left = term.substring(0, equals).trim();
            String right = term.substring(equals + 2).trim();
            if (!left.startsWith("@.")) {
                return List.of();
            }
            terms.add(new String[] {left.substring(2), unquoted(right)});
        }
        return terms;
    }

    private static boolean holds(Object member, String path, String expected) {
        Object at = member;
        for (String segment : path.split("\\.")) {
            if (!(at instanceof Map<?, ?> object)) {
                return false;
            }
            at = object.get(unquoted(segment));
        }
        // A member whose discriminating element repeats satisfies the slice
        // when any of them does, which is what jsonpath's own [*] gives the
        // database for the same step.
        if (at instanceof List<?> many) {
            for (Object one : many) {
                if (expected.equals(one)) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(at);
    }

    private static String unquoted(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
