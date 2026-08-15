package cloud.jengu.dbo.tenant;

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

    static String strOpt(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        return value == null ? null : String.valueOf(value);
    }

    static boolean bool(Object node, String field) {
        Object value = ((Map<?, ?>) node).get(field);
        return Boolean.TRUE.equals(value);
    }

    static List<String> strings(Object node, String field) {
        return array(node, field).stream().map(String::valueOf).toList();
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
                default -> Long.parseLong(literal);
            };
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
