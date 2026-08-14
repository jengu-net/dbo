package cloud.jengu.dbo.core.api;

import java.util.List;
import java.util.Optional;

/**
 * The engine's storage surface for one tenant store. Tenant scoping is
 * structural: an ObjectStore instance IS one tenant's store — there is no
 * tenant parameter to forget (REQ-DBO-TEN-STRUCTURAL-SCOPING).
 */
public interface ObjectStore {

    /** Create (id null) or update; returns after data+history+outbox commit in one tx. */
    PutResult put(PutRequest request);

    /**
     * Conditional create keyed on primary identity only. Existing object with
     * this identity → returns it untouched ({@code created=false}).
     */
    PutResult putIfAbsent(IdentityRef identity, PutRequest request);

    /** Conditional upsert by identity: create if absent, else update (with optional expected version). */
    PutResult putConditional(IdentityRef identity, PutRequest request);

    Optional<StoredObject> get(String typeName, String id);

    /** OR-match across the given identifiers. */
    List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers);

    /** Tombstone delete: version row + outbox event; frees identity claims. */
    void delete(String typeName, String id, Long expectedVersion);

    /** All versions, oldest first (REQ-DBO-CORE-VERSIONED-HISTORY). */
    List<StoredObject> history(String typeName, String id);

    List<StoredObject> select(Criteria criteria);

    /**
     * Recompute envelopes, identifiers and references for a type from stored
     * payloads and (re)apply declared indexes. Payloads are never touched
     * (REQ-DBO-CORE-REINDEX-IS-AN-OPERATION).
     */
    int rebuildEnvelopes(String typeName);
}
