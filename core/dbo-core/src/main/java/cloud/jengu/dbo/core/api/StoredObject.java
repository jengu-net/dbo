package cloud.jengu.dbo.core.api;

import java.time.Instant;

/**
 * A stored version: the opaque payload plus engine metadata.
 * {@code payloadVersion} is the schema version of the payload AS RETURNED —
 * reads upgrade lazily through registered converters, so it normally equals
 * the registration's current version (REQ-DBO-CORE-UPGRADE-ON-READ); history
 * reads are exempt and return stored versions verbatim (§11 fidelity).
 */
public record StoredObject(
        String id,
        String typeName,
        long versionId,
        Instant lastUpdated,
        byte[] payload,
        boolean deleted,
        String payloadVersion) {}
