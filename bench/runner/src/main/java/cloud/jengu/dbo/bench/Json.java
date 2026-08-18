package cloud.jengu.dbo.bench;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Just enough JSON to write a result file.
 *
 * <p>A serialization library would be one more dependency on a box chosen for
 * having nothing on it. The runner writes one document of a shape this
 * repository controls; that is not a job worth a dependency.
 */
final class Json {

    private Json() {
    }

    record Field(String name, String rendered) {}

    static Field field(String name, String value) {
        return new Field(name, value == null ? "null" : quote(value));
    }

    static Field field(String name, long value) {
        return new Field(name, Long.toString(value));
    }

    static Field field(String name, double value) {
        return new Field(name, Double.isFinite(value) ? Double.toString(value) : "null");
    }

    static Field field(String name, boolean value) {
        return new Field(name, Boolean.toString(value));
    }

    /** A field whose value is already JSON — an object, an array, a number. */
    static Field raw(String name, String json) {
        return new Field(name, json == null ? "null" : json);
    }

    static String object(Field... fields) {
        return Arrays.stream(fields)
                .filter(f -> f != null)
                .map(f -> quote(f.name()) + ":" + f.rendered())
                .collect(Collectors.joining(",", "{", "}"));
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
