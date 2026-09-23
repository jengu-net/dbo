package cloud.jengu.dbo.fhir.validate;

import java.util.List;
import java.util.Map;

/**
 * A document, and what a compiled path selects out of it.
 *
 * <p>The public face of the reader the checks use, for a caller that has a
 * compiled path of its own to run — a search parameter's selections, where
 * the checks run an invariant's condition. One scanner and one path reader,
 * because a second of either would be a second answer to what a document
 * says, and this store already holds two answerers to one specification on
 * purpose and a third by accident never.
 *
 * <p>Values come back as they were written: a string as a {@link String}, and
 * a number, a boolean or null as a {@link Literal} carrying its text. The
 * distinction is not decoration — a caller that gave a document back with a
 * number written as a quoted string would corrupt every record it touched,
 * and a decimal's trailing zeros are significant in FHIR.
 */
public final class Documents {

    private Documents() {
    }

    /** A number, a boolean or null, as the text that was written. */
    public interface Literal {
        String text();
    }

    /**
     * The document as maps, lists, strings and literals, or null where the
     * bytes are not a JSON object.
     */
    public static Map<String, Object> read(byte[] payload) {
        return JsonDocument.of(payload);
    }

    /** The document as it would be written. */
    public static byte[] write(Object document) {
        return JsonDocument.compose(document);
    }

    /**
     * What a compiled path selects, or null where this reader cannot run it.
     *
     * <p>Null is a real answer and a caller has to treat it as one: a
     * parameter whose path cannot be run loses a KEY, and a search by that
     * key then finds nothing while looking exactly like an answer.
     */
    public static List<Object> at(Object document, String path) {
        return JsonPathValues.at(document, path);
    }

    /** Whether a compiled condition holds: true, false, or null for cannot say. */
    public static Boolean holds(Object instance, String predicate) {
        return JsonPathPredicate.holds(instance, predicate);
    }

    /** A string or a literal as its written text; null for an object or an array. */
    public static String text(Object value) {
        if (value instanceof String written) {
            return written;
        }
        if (value instanceof JsonDocument.Literal literal) {
            return literal.text();
        }
        return null;
    }
}
