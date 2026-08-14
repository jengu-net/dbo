package cloud.jengu.dbo.postgres;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;

import java.time.format.DateTimeFormatter;
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
                string(sb, DateTimeFormatter.ISO_INSTANT.format(d.value()));
                sb.append('}');
            }
            case EnvelopeValue.Token t -> {
                sb.append("{\"t\":\"tok\",\"s\":");
                string(sb, t.system());
                sb.append(",\"v\":");
                string(sb, t.code());
                sb.append('}');
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
