package cloud.jengu.dbo.telemetry;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;

/**
 * What a measurement is labelled with — nothing a {@link Label} does not name.
 *
 * <p>Built rather than passed as a map, so the closed set is a property of the
 * type rather than a rule somebody remembers. There is no overload taking a
 * string key, deliberately: that overload is how the set stops being closed.
 */
public final class Labels {

    private static final Labels NONE = new Labels(new EnumMap<>(Label.class));

    private final Map<Label, String> values;

    private Labels(Map<Label, String> values) {
        this.values = values;
    }

    /** A measurement about the node itself, belonging to no tenant's work. */
    public static Labels none() {
        return NONE;
    }

    public static Labels of(Label label, String value) {
        return none().and(label, value);
    }

    /** The same labels with one more; a null or blank value is left off. */
    public Labels and(Label label, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        EnumMap<Label, String> next = new EnumMap<>(values);
        next.put(label, value);
        return new Labels(next);
    }

    public Map<Label, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
