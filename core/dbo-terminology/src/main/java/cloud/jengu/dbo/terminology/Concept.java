package cloud.jengu.dbo.terminology;

import java.util.Map;

/**
 * One concept row. {@code designations} maps language → text;
 * {@code properties} maps property code → value. {@code parentCode} null for
 * roots (single-parent hierarchy in this slice).
 */
public record Concept(
        String code,
        String display,
        String parentCode,
        Map<String, String> designations,
        Map<String, String> properties) {

    public static Concept of(String code, String display) {
        return new Concept(code, display, null, Map.of(), Map.of());
    }

    public Concept withParent(String parent) {
        return new Concept(code, display, parent, designations, properties);
    }
}
