package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.util.List;
import java.util.Optional;

/**
 * A store that tells the run in scope what it produced.
 *
 * <p>The rule is that content changes inside a piece of work; this is what
 * makes the run <b>say so</b>. Without it, a run in scope proves a change
 * belonged to some work and the run itself is still silent about which versions
 * it made, so the account has to be assembled afterwards from history — and
 * assembling is the thing this exists to stop doing.
 *
 * <p><b>The engine cannot do this itself.</b> A store writing into the work
 * domain on every content write is the engine re-entering itself, and a run
 * updated inside the transaction that wrote the content would either widen the
 * transaction or lie about it. So it is a decorator: the write commits, and the
 * run is told afterwards.
 *
 * <p>Which is also the honest limit — a crash between the two leaves a version
 * the run does not name. The far side reads it by cursor rather than by
 * manifest, and is behind rather than wrong.
 */
public final class WorkScopedStore implements ObjectStore {

    private final ObjectStore inner;
    private final Runs runs;

    public WorkScopedStore(ObjectStore inner, Runs runs) {
        this.inner = inner;
        this.runs = runs;
    }

    @Override
    public PutResult put(PutRequest request) {
        return recorded(request.typeName(), inner.put(request));
    }

    @Override
    public java.util.List<PutResult> transact(java.util.List<PutRequest> requests) {
        java.util.List<PutResult> results = inner.transact(requests);
        for (int i = 0; i < results.size(); i++) {
            recorded(requests.get(i).typeName(), results.get(i));
        }
        return results;
    }

    @Override
    public PutResult put(PutRequest request, Handling.Authority caller) {
        return recorded(request.typeName(), inner.put(request, caller));
    }

    @Override
    public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        return recorded(request.typeName(), inner.putIfAbsent(identity, request));
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        return recorded(request.typeName(), inner.putConditional(identity, request));
    }

    /**
     * Tells the run, when there is one and when the write is not the run's own.
     *
     * <p>A run recording that it wrote itself would grow by one entry per
     * entry, for ever.
     */
    private PutResult recorded(String typeName, PutResult result) {
        String key = Caller.run();
        if (key == null || WorkModel.TYPE.equals(typeName)) {
            return result;
        }
        runs.byKey(key).ifPresent(run ->
                runs.produced(run, typeName, result.id(), result.versionId()));
        return result;
    }

    @Override
    public Optional<StoredObject> get(String typeName, String id) {
        return inner.get(typeName, id);
    }

    @Override
    public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
        return inner.getByIdentifier(typeName, identifiers);
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        inner.delete(typeName, id, expectedVersion);
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion,
            Handling.Authority caller) {
        inner.delete(typeName, id, expectedVersion, caller);
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        return inner.history(typeName, id);
    }

    @Override
    public List<StoredObject> select(Criteria criteria) {
        return inner.select(criteria);
    }

    @Override
    public long count(Criteria criteria) {
        return inner.count(criteria);
    }

    @Override
    public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
        return inner.page(criteria, cursor);
    }

    @Override
    public int rebuildEnvelopes(String typeName) {
        return inner.rebuildEnvelopes(typeName);
    }

    @Override
    public cloud.jengu.dbo.core.api.TypeRegistration registrationOf(String typeName) {
        return inner.registrationOf(typeName);
    }

    @Override
    public int reindexUnder(cloud.jengu.dbo.core.api.TypeRegistration replacement) {
        return inner.reindexUnder(replacement);
    }
}
