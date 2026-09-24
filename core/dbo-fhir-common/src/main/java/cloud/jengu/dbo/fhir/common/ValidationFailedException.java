package cloud.jengu.dbo.fhir.common;

import java.util.ArrayList;
import java.util.List;

/** Validation gated the write; carries the ERROR/FATAL findings. */
public class ValidationFailedException extends RuntimeException {

    private final List<Finding> findings;

    public ValidationFailedException(String typeName, List<String> issues) {
        this(typeName, issues.stream().map(ValidationFailedException::parsed).toList(), true);
    }

    /**
     * The findings as they were found, with their paths intact.
     *
     * <p>The constructor above takes sentences and is what everything that
     * has not moved yet still uses. It recovers a path from {@code
     * "path: detail"} where it can, which is the shape {@code Finding.says()}
     * writes — so a caller that flattened on the way out gets most of the
     * structure back. Most, not all: a detail containing a colon reads as a
     * path, and that is the reason this constructor exists rather than the
     * parsing being everybody's.
     */
    public ValidationFailedException(String typeName, List<Finding> findings, boolean structured) {
        super("validation failed for %s: %s".formatted(typeName,
                findings.stream().map(Finding::says).toList()));
        this.findings = List.copyOf(findings);
    }

    /** What was wrong, with where. */
    public List<Finding> findings() {
        return findings;
    }

    /** The one-line forms, for everything that reports rather than renders. */
    public List<String> issues() {
        List<String> said = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            said.add(finding.says());
        }
        return said;
    }

    /**
     * A sentence read back into a finding, for the answerers that still speak
     * in sentences. An element path has no spaces, so text before a colon that
     * contains one was never a path.
     */
    private static Finding parsed(String issue) {
        String severity = "error";
        String rest = issue;
        for (String said : SEVERITIES) {
            if (rest.startsWith(said + " ")) {
                severity = said.toLowerCase(java.util.Locale.ROOT);
                rest = rest.substring(said.length() + 1);
                break;
            }
        }
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            return new Finding(severity, null, null, rest);
        }
        String before = rest.substring(0, colon);
        // An element path has no spaces, so text before a colon that has one
        // was prose and not a path. This is a recovery and it says so: the
        // answerers that already build a Finding never come through here.
        if (before.indexOf(' ') >= 0) {
            return new Finding(severity, null, null, rest);
        }
        return new Finding(severity, before, null, rest.substring(colon + 1).trim());
    }

    /** The words the sentence forms put in front, longest first so FATAL wins over none. */
    private static final List<String> SEVERITIES =
            List.of("FATAL", "ERROR", "WARNING", "INFORMATION");
}
