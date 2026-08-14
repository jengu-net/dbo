package cloud.jengu.dbo.terminology;

import java.util.List;

/**
 * Normalized ValueSet compose rules — the subset this slice expands:
 * include whole system / enumerated codes / is-a descendants; exclude
 * enumerated codes.
 */
public record Compose(List<Include> includes, List<Exclude> excludes) {

    /** {@code codes} empty + {@code isA} null = the whole system. */
    public record Include(String system, List<String> codes, String isA) {}

    public record Exclude(String system, List<String> codes) {}
}
