package cloud.jengu.dbo.core.api;

/** A declared index over an envelope path, typed so the index expression matches the sort/filter cast. */
public record IndexSpec(String path, ValueKind kind) {}
