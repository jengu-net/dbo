package cloud.jengu.dbo.definitions;

import java.util.ArrayList;
import java.util.List;

/**
 * The one structure this module writes and reads back: what may stand at an
 * element.
 *
 * <p>Hand-written, like the terminology store's, and for the same reason —
 * this module's dependency list is the driver and nothing else, and a JSON
 * library for one array of three-field objects would be a dependency bought
 * with a reason that does not survive being read aloud.
 */
final class Json {

    private Json() {}

    /** {@code [{"code":"Reference","profiles":[…],"targets":[…]}]} */
    static String types(List<DefinitionElement.Type> types) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < types.size(); i++) {
            DefinitionElement.Type type = types.get(i);
            sb.append(i == 0 ? "" : ",")
                    .append("{\"code\":").append(quote(type.code()))
                    .append(",\"profiles\":").append(strings(type.profiles()))
                    .append(",\"targets\":").append(strings(type.targets()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    static List<DefinitionElement.Type> readTypes(String json) {
        List<DefinitionElement.Type> types = new ArrayList<>();
        for (String object : objects(json)) {
            types.add(new DefinitionElement.Type(field(object, "code"),
                    array(object, "profiles"), array(object, "targets")));
        }
        return List.copyOf(types);
    }

    // ------------------------------------------------------------- writing

    /** A bare JSON array of strings, for a column that holds one. */
    static String arrayOf(List<String> values) {
        return strings(values);
    }

    /** The strings in a bare JSON array, as written by {@link #arrayOf}. */
    static List<String> stringsOf(String json) {
        List<String> values = new ArrayList<>();
        if (json == null) {
            return values;
        }
        int i = 0;
        while (i < json.length()) {
            if (json.charAt(i) == '"') {
                String value = unquote(json, i);
                values.add(value);
                i += quote(value).length();
            } else {
                i++;
            }
        }
        return List.copyOf(values);
    }

    private static String strings(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    // ------------------------------------------------------------- reading

    /** The top-level objects of an array, as text — depth counting, no parser. */
    private static List<String> objects(String json) {
        List<String> objects = new ArrayList<>();
        if (json == null) {
            return objects;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                inString = escaped || c != '"';
                escaped = !escaped && c == '\\';
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth++ == 0) {
                        start = i;
                    }
                }
                case '}' -> {
                    if (--depth == 0) {
                        objects.add(json.substring(start, i + 1));
                    }
                }
                default -> { }
            }
        }
        return objects;
    }

    private static String field(String object, String name) {
        int at = object.indexOf("\"" + name + "\":");
        if (at < 0) {
            return null;
        }
        int from = at + name.length() + 3;
        if (object.startsWith("null", from)) {
            return null;
        }
        return unquote(object, from);
    }

    private static List<String> array(String object, String name) {
        int at = object.indexOf("\"" + name + "\":[");
        List<String> values = new ArrayList<>();
        if (at < 0) {
            return values;
        }
        int i = at + name.length() + 4;
        while (i < object.length() && object.charAt(i) != ']') {
            if (object.charAt(i) == '"') {
                String value = unquote(object, i);
                values.add(value);
                i += quote(value).length();
            } else {
                i++;
            }
        }
        return List.copyOf(values);
    }

    /** The string literal starting at {@code from}, unescaped. */
    private static String unquote(String json, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = json.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(escaped);
            }
        }
        throw new IllegalArgumentException("a string that never ends: " + json);
    }
}
