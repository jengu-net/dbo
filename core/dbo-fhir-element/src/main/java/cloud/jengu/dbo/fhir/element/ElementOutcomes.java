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

    /**
     * The same verdict, naming the shape it was held to (#49).
     *
     * <p>A caller told "invalid" against an unnamed profile cannot tell whether
     * they used the wrong shape or the wrong data — and the two have different
     * fixes, in different people's hands.
     */
    static String validation(List<String> issues, String profile) {
        if (issues.isEmpty()) {
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\","
                    + "\"code\":\"informational\",\"diagnostics\":"
                    + quoted("No issues detected against " + profile) + "}]}";
        }
        StringBuilder out = new StringBuilder("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
        boolean first = true;
        for (String issue : issues) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"severity\":\"error\",\"code\":\"invalid\",\"diagnostics\":")
                    .append(quoted(issue + " (against " + profile + ")")).append('}');
        }
        return out.append("]}").toString();
    }

    /**
     * The verdict with everything the face had to say, at the severity it said
     * it (#50).
     *
     * <p>What a caller does with a warning is their business; what they cannot
     * do is act on advice nobody gave them. The code is {@code invalid} for a
     * refusal and {@code code-invalid} for the rest, because the rest is almost
     * always a binding — and {@code not-found} when the store could not resolve
     * the system at all, which is a statement about this store's content rather
     * than about the caller's data.
     */
    static String issues(java.util.List<cloud.jengu.dbo.core.face.Payloads.Issue> issues,
            String profile) {
        if (issues.isEmpty()) {
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\","
                    + "\"code\":\"informational\",\"diagnostics\":"
                    + quoted(profile == null ? "No issues detected"
                            : "No issues detected against " + profile) + "}]}";
        }
        StringBuilder out = new StringBuilder("{\"resourceType\":\"OperationOutcome\",\"issue\":[");
        boolean first = true;
        for (cloud.jengu.dbo.core.face.Payloads.Issue issue : issues) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"severity\":").append(quoted(issue.severity()))
                    .append(",\"code\":").append(quoted(codeFor(issue)))
                    .append(",\"expression\":[").append(quoted(issue.location())).append(']')
                    .append(",\"diagnostics\":").append(quoted(profile == null
                            ? issue.message() : issue.message() + " (against " + profile + ")"))
                    .append('}');
        }
        return out.append("]}").toString();
    }

    /**
     * Unresolvable is not invalid: a code from a system this tenant does not
     * hold says the store's content is incomplete, and telling a caller their
     * data is wrong for it sends them to fix the wrong thing.
     */
    private static String codeFor(cloud.jengu.dbo.core.face.Payloads.Issue issue) {
        String message = issue.message() == null ? "" : issue.message().toLowerCase(
                java.util.Locale.ROOT);
        if (message.contains("could not be found") || message.contains("unknown code system")
                || message.contains("not been checked") || message.contains("can't be found")) {
            return "not-found";
        }
        return issue.refuses() ? "invalid" : "code-invalid";
    }

    /**
     * A fault in this store, said so that nobody has to read the diagnostics to
     * know it (#105).
     *
     * <p>{@code severity: fatal} is FHIR's own word for it — "the action failed
     * and no further checking could be performed" — which is exactly what an
     * internal fault is and exactly what a finding about a document is not. A
     * caller sorting outcomes can act on the severity alone: {@code fatal}
     * means open a bug against this store, {@code error} means fix the content.
     * Before this they were both {@code error, exception} and told apart only
     * by a message naming a local variable.
     */
    static String fault(String diagnostics) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\","
                + "\"code\":\"exception\",\"diagnostics\":" + quoted(diagnostics) + "}]}";
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
