package cloud.jengu.dbo.core.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, typed selection over envelope values, reference edges and engine
 * metadata. This is the engine-level criteria SPI; FHIR search parameters
 * compile down to it in the personality.
 */
public final class Criteria {

    public enum RangeOp { GT, LT, GE, LE }

    public record Eq(String path, EnvelopeValue value) {}

    public record NotEq(String path, EnvelopeValue value) {}

    public record StartsWith(String path, String prefix) {}

    public record Missing(String path, boolean missing) {}

    /** {@code value} is the typed key text: fixed-width date key or plain number. */
    public record Range(String path, ValueKind kind, RangeOp op, String value) {}

    public record LastUpdatedRange(RangeOp op, Instant value) {}

    public record Referencing(String refType, String targetType, String targetId) {}

    /** Owner has an edge of this type to ANY of the targets — the compartment shape (#126). */
    public record ReferencingAny(String refType, String targetType,
            java.util.List<String> targetIds) {
        public ReferencingAny {
            if (targetIds == null || targetIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "an empty reach is a refusal, not a filter matching everything");
            }
        }
    }

    /** Presence/absence of any reference edge of the given type. */
    public record RefMissing(String refType, boolean missing) {}

    /** One-level chain: owner --refPath--> target, target matched by identifier or envelope. */
    public sealed interface ChainTarget {
        record ByIdentifier(String system, String value) implements ChainTarget {
            public ByIdentifier {
                if (system == null && value == null) {
                    throw new IllegalArgumentException("chain identifier needs system or value");
                }
            }
        }

        record ByEq(String path, EnvelopeValue value) implements ChainTarget {}
    }

    public record Chained(String refPath, String targetType, ChainTarget target) {}

    public record Sort(String path, ValueKind kind, boolean ascending) {}

    private final String typeName;
    private final List<Eq> equals = new ArrayList<>();
    private final List<NotEq> notEquals = new ArrayList<>();
    private final List<StartsWith> startsWith = new ArrayList<>();
    private final List<Missing> missing = new ArrayList<>();
    private final List<Range> ranges = new ArrayList<>();
    private final List<LastUpdatedRange> lastUpdated = new ArrayList<>();
    private final List<Referencing> referencing = new ArrayList<>();
    private final List<ReferencingAny> referencingAny = new ArrayList<>();
    private final List<RefMissing> refMissing = new ArrayList<>();
    private final List<Chained> chained = new ArrayList<>();
    private Sort sort;
    private Boolean sortLastUpdatedAscending;
    private String idEquals;
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

    public Criteria notEq(String path, EnvelopeValue value) {
        Paths.requireValid(path);
        notEquals.add(new NotEq(path, value));
        return this;
    }

    public Criteria startsWith(String path, String prefix) {
        Paths.requireValid(path);
        startsWith.add(new StartsWith(path, prefix));
        return this;
    }

    public Criteria missing(String path, boolean isMissing) {
        Paths.requireValid(path);
        missing.add(new Missing(path, isMissing));
        return this;
    }

    public Criteria range(String path, ValueKind kind, RangeOp op, String value) {
        Paths.requireValid(path);
        ranges.add(new Range(path, kind, op, value));
        return this;
    }

    public Criteria lastUpdated(RangeOp op, Instant value) {
        lastUpdated.add(new LastUpdatedRange(op, value));
        return this;
    }

    public Criteria referencingAny(String refType, String targetType,
            java.util.Collection<String> targetIds) {
        referencingAny.add(new ReferencingAny(refType, targetType,
                java.util.List.copyOf(targetIds)));
        return this;
    }

    public Criteria referencing(String refType, String targetType, String targetId) {
        referencing.add(new Referencing(refType, targetType, targetId));
        return this;
    }

    public Criteria refMissing(String refType, boolean isMissing) {
        refMissing.add(new RefMissing(refType, isMissing));
        return this;
    }

    public Criteria chained(String refPath, String targetType, ChainTarget target) {
        chained.add(new Chained(refPath, targetType, target));
        return this;
    }

    public Criteria sortBy(String path, ValueKind kind, boolean ascending) {
        Paths.requireValid(path);
        this.sort = new Sort(path, kind, ascending);
        this.sortLastUpdatedAscending = null;
        return this;
    }

    public Criteria sortByLastUpdated(boolean ascending) {
        this.sortLastUpdatedAscending = ascending;
        this.sort = null;
        return this;
    }

    public Criteria idEquals(String id) {
        this.idEquals = id;
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

    public List<NotEq> notEqualsPredicates() { return notEquals; }

    public List<StartsWith> startsWithPredicates() { return startsWith; }

    public List<Missing> missingPredicates() { return missing; }

    public List<Range> rangePredicates() { return ranges; }

    public List<LastUpdatedRange> lastUpdatedPredicates() { return lastUpdated; }

    public List<Referencing> referencingPredicates() { return referencing; }
    public List<ReferencingAny> referencingAnyPredicates() { return referencingAny; }

    public List<RefMissing> refMissingPredicates() { return refMissing; }

    public List<Chained> chainedPredicates() { return chained; }

    public Sort sort() { return sort; }

    /** Null when no explicit last-updated sort was requested. */
    public Boolean sortLastUpdatedAscending() { return sortLastUpdatedAscending; }

    /** Null unless the selection is pinned to one object id. */
    public String idEqualsValue() { return idEquals; }

    public int limitValue() { return limit; }
}
