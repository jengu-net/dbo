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
     * A write that says who is making it, so a type's declared handling can
     * refuse it.
     *
     * <p>Deliberately <b>not</b> a default method. A default delegating to the
     * one-argument form would let a wrapper inherit it and silently discard the
     * authority on the way through: the caller believes it declared something,
     * the shield sees the least-privileged default, and the failure is toward
     * permissive. Abstract means a new implementation has to decide.
     *
     * <p>Without this on the interface a replication lane — which holds an
     * {@code ObjectStore}, not a concrete class — has no way to say it is the
     * source tenant, so {@code READ_ONLY_HERE} cannot be satisfied at all.
     */
    PutResult put(PutRequest request, Handling.Authority caller);

    /**
     * Conditional create keyed on primary identity only. Existing object with
     * this identity → returns it untouched ({@code created=false}).
     */
    /**
     * Several writes as ONE unit: every request lands or none does, with
     * data, history and outbox committing in one transaction exactly as a
     * single {@link #put} does — a transaction bundle's promise is this
     * method's promise (REQ-DBO-CORE-ATOMIC-TRANSACTION-BUNDLE).
     *
     * <p>Results are in request order. The default refuses rather than
     * looping over {@code put}: N separate transactions pretending to be one
     * is exactly the lie this seam exists to make impossible.
     */
    default List<PutResult> transact(List<PutRequest> requests) {
        throw new UnsupportedOperationException(
                "this store cannot apply several writes as one unit");
    }

    /**
     * The unit above, saying who is making it — see
     * {@link #put(PutRequest, Handling.Authority)}. The default refuses for
     * the same reason the unit's default does, and for one more: a default
     * that dropped to the one-argument form would discard the authority on
     * the way through a wrapper, and the failure would be toward permissive.
     */
    default List<PutResult> transact(List<PutRequest> requests, Handling.Authority caller) {
        throw new UnsupportedOperationException(
                "this store cannot apply several writes as one unit for a stated caller");
    }

    PutResult putIfAbsent(IdentityRef identity, PutRequest request);

    /** Conditional upsert by identity: create if absent, else update (with optional expected version). */
    PutResult putConditional(IdentityRef identity, PutRequest request);

    Optional<StoredObject> get(String typeName, String id);

    /** OR-match across the given identifiers. */
    List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers);

    /** Tombstone delete: version row + outbox event; frees identity claims. */
    void delete(String typeName, String id, Long expectedVersion);

    /** As above, saying who is asking — see {@link #put(PutRequest, Handling.Authority)}. */
    void delete(String typeName, String id, Long expectedVersion, Handling.Authority caller);

    /** All versions, oldest first (REQ-DBO-CORE-VERSIONED-HISTORY). */
    List<StoredObject> history(String typeName, String id);

    List<StoredObject> select(Criteria criteria);

    /** Matching-object count for the criteria (serves _summary=count). */
    long count(Criteria criteria);

    /**
     * Keyset pagination over a selection (REQ-DBO-FEED-KEYSET-CURSORS): the
     * chunk's cursor continues after the last row's (sort value, id) — stable
     * under concurrent writes, never an offset. Chunk size is the criteria
     * limit. §10 caveat applies: a row updated after the cursor passed it
     * will not reappear; never-miss consumers belong on the change feed.
     */
    cloud.jengu.dbo.core.api.feed.FeedChunk<StoredObject> page(Criteria criteria, String cursor);

    /**
     * Recompute envelopes, identifiers and references for a type from stored
     * payloads and (re)apply declared indexes. Payloads are never touched
     * (REQ-DBO-CORE-REINDEX-IS-AN-OPERATION).
     */
    int rebuildEnvelopes(String typeName);

    /**
     * How this store has type registered right now.
     *
     * <p>Here because {@link #reindexUnder} would otherwise be callable only
     * by whoever built the original registration: a replacement has to carry
     * every field the current one carries, and a caller changing one of them
     * must not have to reproduce the rest from memory.
     *
     * @throws UnknownTypeException if this store serves no such type
     */
    TypeRegistration registrationOf(String typeName);

    /**
     * Registers a type differently and rebuilds it under the new registration.
     *
     * <p>The two halves are one operation because either alone is a store that
     * lies. A registration swapped without a reindex leaves every existing row
     * extracted under the old one, so the new declaration answers correctly
     * about rows written after it and wrongly about everything before. A
     * reindex without the swap rebuilds each row into exactly what it already
     * held.
     *
     * <p>The replacement must name a type this store already has, in the same
     * domain: this exists so a type's extractor and declared indexes can
     * change under a live store — a tenant authoring a search parameter is the
     * case it was built for — not so a catalogue can be edited at runtime.
     *
     * <p>What the engine knows about that case is nothing. A registration is
     * engine vocabulary, and whether one changed because somebody wrote a
     * FHIR SearchParameter or for a reason no face has thought of yet is not
     * a question this interface can ask.
     *
     * @return how many objects were rebuilt
     */
    int reindexUnder(TypeRegistration replacement);
}
