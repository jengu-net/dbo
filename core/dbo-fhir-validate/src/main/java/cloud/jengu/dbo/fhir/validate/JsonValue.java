package cloud.jengu.dbo.fhir.validate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Equality and containment between a document's value and what a profile
 * pinned to it.
 *
 * <p>{@code fixed} is equality: the element is that value and no other.
 * {@code pattern} is containment, which is what a pattern means — the element
 * must carry what the pattern states and may carry more.
 *
 * <p><b>These are Postgres's own relations, on purpose.</b> The database
 * answers the same two questions with {@code IS DISTINCT FROM} and
 * {@code @>} over jsonb, and the whole case for a third answerer is that it
 * says the same thing. So the rules here are jsonb's: an object is contained
 * when every key it states is present and contained; an array is contained
 * when each of its members is contained by some member of the other; a scalar
 * is contained when it is equal. And jsonb's one top-level exception is kept
 * too — a scalar is contained in an array that holds it — because a profile
 * pinning one coding against an element that repeats relies on it.
 *
 * <p><b>Numbers compare as numbers.</b> The scan keeps a value as the text
 * that was written, which is what preserves a decimal's precision; jsonb
 * stores it as a numeric and reads {@code 1.0} and {@code 1.00} as the same
 * value. Comparing the text would make the two answerers disagree about a
 * document neither thinks is wrong, so where both sides are numeric the
 * comparison is numeric.
 */
final class JsonValue {

    private JsonValue() {
    }

    /** Whether the document's value is the pinned one. */
    static boolean same(Object held, String pinned) {
        return equal(held, JsonDocument.value(pinned));
    }

    /** Whether the document's value carries what the pattern states. */
    static boolean contains(Object held, String pattern) {
        Object stated = JsonDocument.value(pattern);
        // jsonb's top-level exception: a bare value is contained in an array
        // that holds it, and only at the top level.
        if (held instanceof List<?> many && !(stated instanceof List) && !(stated instanceof Map)) {
            for (Object one : many) {
                if (equal(one, stated)) {
                    return true;
                }
            }
            return false;
        }
        return contained(held, stated);
    }

    private static boolean contained(Object held, Object stated) {
        if (stated instanceof Map<?, ?> object) {
            if (!(held instanceof Map<?, ?> in)) {
                return false;
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!in.containsKey(entry.getKey())
                        || !contained(in.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (stated instanceof List<?> many) {
            if (!(held instanceof List<?> in)) {
                return false;
            }
            for (Object one : many) {
                boolean found = false;
                for (Object candidate : in) {
                    if (contained(candidate, one)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
        return equal(held, stated);
    }

    private static boolean equal(Object held, Object stated) {
        if (held instanceof Map<?, ?> left && stated instanceof Map<?, ?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : right.entrySet()) {
                if (!left.containsKey(entry.getKey())
                        || !equal(left.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (held instanceof List<?> left && stated instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!equal(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (held instanceof String left && stated instanceof String right) {
            if (left.equals(right)) {
                return true;
            }
            return numeric(left) != null && numeric(right) != null
                    && numeric(left).compareTo(numeric(right)) == 0;
        }
        return false;
    }

    private static BigDecimal numeric(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** A value as it would be written, for a message a person reads. */
    static String asText(Object held) {
        if (held instanceof String text) {
            return text;
        }
        return String.valueOf(held);
    }
}
