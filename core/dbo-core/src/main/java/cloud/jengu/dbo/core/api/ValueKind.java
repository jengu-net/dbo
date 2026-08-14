package cloud.jengu.dbo.core.api;

/** The typed interpretation of an envelope path for ordering, filtering and indexing. */
public enum ValueKind {
    STRING,
    NUMBER,
    DATE,
    TOKEN,
    REFERENCE,
}
