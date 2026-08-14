package cloud.jengu.dbo.fhir.r4;

import java.util.List;

/** Validation gated the write; carries the ERROR/FATAL issue lines. */
public class ValidationFailedException extends RuntimeException {

    private final List<String> issues;

    public ValidationFailedException(String typeName, List<String> issues) {
        super("validation failed for %s: %s".formatted(typeName, issues));
        this.issues = List.copyOf(issues);
    }

    public List<String> issues() {
        return issues;
    }
}
