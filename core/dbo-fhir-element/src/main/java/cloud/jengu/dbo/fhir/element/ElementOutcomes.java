package cloud.jengu.dbo.fhir.element;

import java.util.List;

/**
 * OperationOutcome, written directly.
 *
 * <p>An outcome is the same handful of elements in every FHIR version, and
 * building one through the element model would mean holding a StructureDefinition
 * to say what a client already knows. What is version-specific about an outcome
 * — the issue codes — is FHIR's own fixed list, not a version's.
 */
final class ElementOutcomes {

    private ElementOutcomes() {
    }

    /** The verdict on a resource, as {@code $validate} answers it. */
    static String validation(List<String> issues) {
        if (issues.isEmpty()) {
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\","
                    + "\"code\":\"informational\",\"diagnostics\":\"No issues detected\"}]}";
        }
        StringBuilder out = new StringBuilder("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
        boolean first = true;
        for (String issue : issues) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"severity\":\"error\",\"code\":\"invalid\",\"diagnostics\":")
                    .append(quoted(issue)).append('}');
        }
        return out.append("]}").toString();
    }

    static String outcome(String issueCode, String diagnostics) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":"
                + quoted(issueCode) + ",\"diagnostics\":" + quoted(diagnostics) + "}]}";
    }

    static String quoted(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
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
