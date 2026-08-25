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
        String origin) {

    /**
     * @param origin which dependency streamed this copy here, or null for a
     *        record this tenant authored (#109). An engine fact carried with
     *        the record so the serving path can SAY it — {@code Meta.source}
     *        is the standard place — without a second read.
     */
    public StoredObject {
    }

    /** The shape every writer uses: a record of this tenant's own. */
    public StoredObject(String id, String typeName, long versionId, Instant lastUpdated,
            byte[] payload, boolean deleted, String payloadVersion) {
        this(id, typeName, versionId, lastUpdated, payload, deleted, payloadVersion, null);
    }
}
