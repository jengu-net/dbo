package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PolicyViolationException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.util.List;
import java.util.Optional;

/**
 * The policy decorator (§15): outermost in the engine stack (policy wraps
 * isolation wraps engine), so audit sees interactions after authorization
 * and never sees content — actor, interaction, type, target id, outcome.
 * Append-only discipline rejects tombstones with an error that names the
 * policy; correction is supersession, never removal.
 *
 * <p>Audit entries are written to the same store's {@code audit} domain
 * immediately after the audited interaction commits. The AuditEntry
 * registration must be present in the wrapped engine.
 */
public final class PolicyObjectStore implements ObjectStore {

    private final ObjectStore inner;
    private final TenantPolicies policies;

    public PolicyObjectStore(ObjectStore inner, TenantPolicies policies) {
        this.inner = inner;
        this.policies = policies;
    }

    public TenantPolicies policies() {
        return policies;
    }

    // ------------------------------------------------------------- writes

    @Override
    public PutResult put(PutRequest request) {
        PutResult result = inner.put(request);
        auditWrite(result.created() ? "create" : "update", request.typeName(), result.id());
        return result;
    }

    @Override
    public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        PutResult result = inner.putIfAbsent(identity, request);
        if (result.created()) {
            auditWrite("create", request.typeName(), result.id());
        }
        return result;
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        PutResult result = inner.putConditional(identity, request);
        auditWrite(result.created() ? "create" : "update", request.typeName(), result.id());
        return result;
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        if (policies.disciplineFor(typeName) == TenantPolicies.Discipline.APPEND_ONLY) {
            throw new PolicyViolationException(
                    "append-only write discipline forbids deleting " + typeName
                            + " — correct by supersession or entered-in-error (§15.2)");
        }
        inner.delete(typeName, id, expectedVersion);
        auditWrite("delete", typeName, id);
    }

    // ------------------------------------------------------------- reads

    @Override
    public Optional<StoredObject> get(String typeName, String id) {
        Optional<StoredObject> result = inner.get(typeName, id);
        auditRead("read", typeName, id);
        return result;
    }

    @Override
    public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
        List<StoredObject> result = inner.getByIdentifier(typeName, identifiers);
        auditRead("search", typeName, null);
        return result;
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        List<StoredObject> result = inner.history(typeName, id);
        auditRead("history", typeName, id);
        return result;
    }

    @Override
    public List<StoredObject> select(Criteria criteria) {
        List<StoredObject> result = inner.select(criteria);
        auditRead("search", criteria.typeName(), null);
        return result;
    }

    @Override
    public long count(Criteria criteria) {
        return inner.count(criteria);
    }

    @Override
    public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
        FeedChunk<StoredObject> result = inner.page(criteria, cursor);
        auditRead("search", criteria.typeName(), null);
        return result;
    }

    @Override
    public int rebuildEnvelopes(String typeName) {
        return inner.rebuildEnvelopes(typeName);
    }

    // ------------------------------------------------------------- audit

    private void auditWrite(String interaction, String typeName, String targetId) {
        if (policies.auditsWrites() && !"AuditEntry".equals(typeName)) {
            record(interaction, typeName, targetId, null);
        }
    }

    private void auditRead(String interaction, String typeName, String targetId) {
        if (policies.auditsReads() && !"AuditEntry".equals(typeName)) {
            record(interaction, typeName, targetId, null);
        }
    }

    void record(String interaction, String typeName, String targetId, String rule) {
        inner.put(PutRequest.create("AuditEntry",
                AuditModel.entry(Caller.current(), interaction, typeName, targetId, "ok", rule)));
    }
}
