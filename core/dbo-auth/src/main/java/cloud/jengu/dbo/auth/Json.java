package cloud.jengu.dbo.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON for the spec file format (objects, arrays, strings). */
final class Json {

    private Json() {}

    static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    static String str(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        if (value == null) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    static List<String> strings(Object node, String field) {
        return array(node, field).stream().map(String::valueOf).toList();
    }

    static String strOpt(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        return value == null ? null : String.valueOf(value);
    }

    static long num(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("not a number: " + field);
        }
        return n.longValue();
    }

    /** Re-serializes a parsed subtree (objects, arrays, strings, numbers, booleans). */
    static String render(Object node) {
        StringBuilder sb = new StringBuilder();
        render(node, sb);
        return sb.toString();
    }

    private static void render(Object node, StringBuilder sb) {
        switch (node) {
            case null -> sb.append("null");
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('"').append(e.getKey()).append("\":");
                    render(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    render(list.get(i), sb);
                }
                sb.append(']');
            }
            case String str -> {
                sb.append('"');
                for (char c : str.toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        default -> {
                            if (c < 0x20) {
                                sb.append(String.format("\\u%04x", (int) c));
                            } else {
                                sb.append(c);
                            }
                        }
                    }
                }
                sb.append('"');
            }
            default -> sb.append(node);
        }
    }

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
                default -> parseLiteral();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            i++;
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
            i++;
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
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
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

        private Object parseLiteral() {
            int start = i;
            while (i < s.length() && "-+.0123456789eEtruefalsnl".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String literal = s.substring(start, i);
            return switch (literal) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> {
                    if (literal.contains(".") || literal.contains("e") || literal.contains("E")) {
                        yield Double.parseDouble(literal);
                    }
                    yield Long.parseLong(literal);
                }
            };
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
