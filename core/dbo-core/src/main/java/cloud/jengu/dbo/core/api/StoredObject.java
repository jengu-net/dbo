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
        String payloadVersion,
        String origin,
        String shadowing,
        java.util.List<String> shape) {

    /** Compatibility with callers that predate {@code shape}. */
    public StoredObject(String id, String typeName, long versionId, Instant lastUpdated,
            byte[] payload, boolean deleted, String payloadVersion, String origin,
            String shadowing) {
        this(id, typeName, versionId, lastUpdated, payload, deleted, payloadVersion,
                origin, shadowing, null);
    }

    /**
     * @param origin    which dependency streamed this copy here, or null for a
     *        record this tenant authored (#109). An engine fact carried with
     *        the record so the serving path can SAY it — {@code Meta.source}
     *        is the standard place — without a second read.
     * @param shadowing which dependency's publication this record locally
     *        overrides — a shadow is parked behind it — or null. Said as a
     *        {@code Meta.tag}, because a parked shadow visible only to whoever
     *        queries the sync engine sits unnoticed (#102, #109).
     */
    public StoredObject {
    }

    public StoredObject(String id, String typeName, long versionId, Instant lastUpdated,
            byte[] payload, boolean deleted, String payloadVersion, String origin) {
        this(id, typeName, versionId, lastUpdated, payload, deleted, payloadVersion, origin, null);
    }

    /** The shape every writer uses: a record of this tenant's own. */
    public StoredObject(String id, String typeName, long versionId, Instant lastUpdated,
            byte[] payload, boolean deleted, String payloadVersion) {
        this(id, typeName, versionId, lastUpdated, payload, deleted, payloadVersion, null, null);
    }
}
