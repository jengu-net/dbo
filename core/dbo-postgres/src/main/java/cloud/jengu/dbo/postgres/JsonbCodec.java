package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;

import cloud.jengu.dbo.core.api.DateKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envelope paths → JSONB document, and equality predicates → a containment
 * document bound as a single parameter ({@code envelope @> ?::jsonb}). Values
 * always travel as bound parameters, never as SQL text
 * (REQ-DBO-CORE-PARAMETERIZED-SQL).
 */
final class JsonbCodec {

    private JsonbCodec() {}

    static String envelopeJson(Map<String, List<EnvelopeValue>> paths) {
        StringBuilder sb = new StringBuilder("{");
        boolean firstPath = true;
        for (Map.Entry<String, List<EnvelopeValue>> e : paths.entrySet()) {
            if (!firstPath) sb.append(',');
            firstPath = false;
            string(sb, e.getKey());
            sb.append(":[");
            boolean firstV = true;
            for (EnvelopeValue v : e.getValue()) {
                if (!firstV) sb.append(',');
                firstV = false;
                value(sb, v);
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    static String containmentJson(List<Criteria.Eq> predicates) {
        Map<String, List<EnvelopeValue>> byPath = new LinkedHashMap<>();
        for (Criteria.Eq eq : predicates) {
            byPath.computeIfAbsent(eq.path(), k -> new java.util.ArrayList<>()).add(eq.value());
        }
        return envelopeJson(byPath);
    }

    private static void value(StringBuilder sb, EnvelopeValue v) {
        switch (v) {
            case EnvelopeValue.Str s -> {
                sb.append("{\"t\":\"str\",\"v\":");
                string(sb, s.value());
                sb.append('}');
            }
            case EnvelopeValue.Num n ->
                sb.append("{\"t\":\"num\",\"v\":").append(n.value().toPlainString()).append('}');
            case EnvelopeValue.Date d -> {
                sb.append("{\"t\":\"date\",\"v\":");
                string(sb, DateKeys.of(d.value()));
                sb.append('}');
            }
            case EnvelopeValue.Token t -> {
                if (t.code() == null) {
                    // system-only form: FHIR "sys|" matches any value in system
                    sb.append("{\"t\":\"toks\",\"v\":");
                    string(sb, t.system());
                    sb.append('}');
                } else if (t.system() == null) {
                    // bare-code token form: FHIR "code=x" matches any system
                    sb.append("{\"t\":\"tokc\",\"v\":");
                    string(sb, t.code());
                    sb.append('}');
                } else {
                    sb.append("{\"t\":\"tok\",\"s\":");
                    string(sb, t.system());
                    sb.append(",\"v\":");
                    string(sb, t.code());
                    sb.append('}');
                }
            }
            case EnvelopeValue.Ref r -> {
                sb.append("{\"t\":\"ref\",\"tt\":");
                string(sb, r.targetType());
                sb.append(",\"ti\":");
                string(sb, r.targetId());
                sb.append('}');
            }
        }
    }

    /**
     * One value back from the form {@link #envelopeJson} wrote — the exact
     * shapes above and no others, which is why this is not a JSON parser.
     */
    static EnvelopeValue decodeValue(String json) {
        // Postgres renders jsonb back with a space after each colon and
        // comma, whatever was written, so the reader steps over blanks.
        Map<String, String> fields = new java.util.HashMap<>();
        int at = blanks(json, 1);
        while (at < json.length() && json.charAt(at) == '"') {
            int keyEnd = json.indexOf('"', at + 1);
            String key = json.substring(at + 1, keyEnd);
            at = blanks(json, keyEnd + 1);
            at = blanks(json, at + 1); // past the colon
            StringBuilder value = new StringBuilder();
            if (json.charAt(at) == '"') {
                at = unstring(json, at + 1, value);
            } else {
                int end = at;
                while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                    end++;
                }
                value.append(json, at, end);
                at = end;
            }
            fields.put(key, value.toString());
            at = blanks(json, at);
            if (at < json.length() && json.charAt(at) == ',') {
                at = blanks(json, at + 1);
            }
        }
        String v = fields.get("v");
        return switch (fields.get("t")) {
            case "str" -> EnvelopeValue.of(v);
            case "num" -> EnvelopeValue.of(new java.math.BigDecimal(v));
            case "date" -> EnvelopeValue.of(java.time.Instant.from(
                    cloud.jengu.dbo.core.api.DateKeys.FORMAT.parse(v)));
            case "toks" -> new EnvelopeValue.Token(v, null);
            case "tokc" -> new EnvelopeValue.Token(null, v);
            case "tok" -> new EnvelopeValue.Token(fields.get("s"), v);
            case "ref" -> new EnvelopeValue.Ref(fields.get("tt"), fields.get("ti"));
            default -> throw new IllegalStateException("not an envelope value: " + json);
        };
    }

    private static int blanks(String json, int at) {
        while (at < json.length() && json.charAt(at) == ' ') {
            at++;
        }
        return at;
    }

    /** Reads a string written by {@link #string} from {@code at} (past the quote); returns the index past its closing quote. */
    private static int unstring(String json, int at, StringBuilder out) {
        while (json.charAt(at) != '"') {
            char c = json.charAt(at);
            if (c == '\\') {
                char escaped = json.charAt(at + 1);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(json.substring(at + 2, at + 6), 16));
                        at += 4;
                    }
                    default -> out.append(escaped);
                }
                at += 2;
            } else {
                out.append(c);
                at++;
            }
        }
        return at + 1;
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
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
        sb.append('"');
    }
}
