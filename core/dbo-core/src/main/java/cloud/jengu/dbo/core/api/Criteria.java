package cloud.jengu.dbo.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, typed selection over envelope values and reference edges. This is
 * the engine-level criteria SPI; FHIR search parameters compile down to it in
 * the personality slice.
 */
public final class Criteria {

    public record Eq(String path, EnvelopeValue value) {}

    public record Referencing(String refType, String targetType, String targetId) {}

    public record Sort(String path, ValueKind kind, boolean ascending) {}

    private final String typeName;
    private final List<Eq> equals = new ArrayList<>();
    private final List<Referencing> referencing = new ArrayList<>();
    private Sort sort;
    private int limit = 100;

    private Criteria(String typeName) {
        this.typeName = Objects.requireNonNull(typeName, "typeName");
    }

    public static Criteria of(String typeName) {
        return new Criteria(typeName);
    }

    public Criteria eq(String path, EnvelopeValue value) {
        Paths.requireValid(path);
        equals.add(new Eq(path, value));
        return this;
    }

    public Criteria referencing(String refType, String targetType, String targetId) {
        referencing.add(new Referencing(refType, targetType, targetId));
        return this;
    }

    public Criteria sortBy(String path, ValueKind kind, boolean ascending) {
        Paths.requireValid(path);
        this.sort = new Sort(path, kind, ascending);
        return this;
    }

    public Criteria limit(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit out of range: " + limit);
        }
        this.limit = limit;
        return this;
    }

    public String typeName() { return typeName; }

    public List<Eq> equalsPredicates() { return equals; }

    public List<Referencing> referencingPredicates() { return referencing; }

    public Sort sort() { return sort; }

    public int limitValue() { return limit; }
}
