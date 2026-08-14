package cloud.jengu.dbo.core.api;

/** Returned only after data + history + outbox committed in one transaction (REQ-DBO-CORE-READ-YOUR-WRITES). */
public record PutResult(String id, long versionId, boolean created) {}
