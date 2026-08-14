package cloud.jengu.dbo.core.api;

import java.time.Instant;

/** A stored version: the opaque payload plus engine metadata. */
public record StoredObject(
        String id,
        String typeName,
        long versionId,
        Instant lastUpdated,
        byte[] payload,
        boolean deleted) {}
