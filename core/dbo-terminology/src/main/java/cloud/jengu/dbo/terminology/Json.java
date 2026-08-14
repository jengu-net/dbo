package cloud.jengu.dbo.terminology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the terminology store's OWN documents (flat string maps and
 * the compose structure) — both sides of the round-trip are written here, so
 * the grammar is closed: objects, arrays, strings.
 */
final class Json {

    private Json() {}

    // ------------------------------------------------------------- writing

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String stringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    static String flatMapJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** CSV field: null → empty, else quoted with doubled quotes. */
    static String csv(String s) {
        if (s == null) {
            return "";
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------- parsing

    static Map<String, String> parseFlatMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Object parsed = new Parser(json).parseValue();
        Map<String, String> out = new LinkedHashMap<>();
        if (parsed instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseObjectArray(String json, String field) {
        Object parsed = new Parser(json).parseValue();
        List<Map<String, Object>> out = new ArrayList<>();
        if (parsed instanceof Map<?, ?> m && m.get(field) instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> obj) {
                    out.add((Map<String, Object>) obj);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Recursive-descent over the closed grammar: object | array | string. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                default -> throw new IllegalArgumentException("unexpected '" + c + "' at " + i);
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                i++; // :
                out.put(key, parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') {
                    return out;
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            i++; // [
            skipWs();
            if (s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') {
                    return out;
                }
            }
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s, i, i + 4, 16));
                            i += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
