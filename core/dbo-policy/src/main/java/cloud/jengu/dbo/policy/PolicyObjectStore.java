package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PolicyViolationException;
import cloud.jengu.dbo.core.api.Handling;
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
public final class PolicyObjectStore implements ObjectStore,
        cloud.jengu.dbo.rest.AuditProjection.Recorder {

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
        return put(request, Handling.Authority.TENANT_USERS);
    }

    /**
     * Forwards the caller's authority rather than dropping it. A wrapper that
     * swallowed it would leave the shield seeing the least-privileged default
     * while the caller believed it had declared something.
     */
    @Override
    public PutResult put(PutRequest request, Handling.Authority caller) {
        refuseDirectAuditWrites(request.typeName());
        PutResult result = inner.put(request, caller);
        auditWrite(result.created() ? "create" : "update", request.typeName(), result.id());
        return result;
    }

    @Override
    public java.util.List<PutResult> transact(java.util.List<PutRequest> requests) {
        // The same shield per request as a single write, checked BEFORE the
        // unit begins; the audit entries land after it commits, one per
        // write, exactly as they do for single puts.
        requests.forEach(r -> refuseDirectAuditWrites(r.typeName()));
        java.util.List<PutResult> results = inner.transact(requests);
        for (int i = 0; i < results.size(); i++) {
            auditWrite(results.get(i).created() ? "create" : "update",
                    requests.get(i).typeName(), results.get(i).id());
        }
        return results;
    }

    @Override
    public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        refuseDirectAuditWrites(request.typeName());
        PutResult result = inner.putIfAbsent(identity, request);
        if (result.created()) {
            auditWrite("create", request.typeName(), result.id());
        }
        return result;
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        refuseDirectAuditWrites(request.typeName());
        PutResult result = inner.putConditional(identity, request);
        auditWrite(result.created() ? "create" : "update", request.typeName(), result.id());
        return result;
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        delete(typeName, id, expectedVersion, Handling.Authority.TENANT_USERS);
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion,
            Handling.Authority caller) {
        if ("AuditEntry".equals(typeName)) {
            // §15.1: the trail is exempt from the tenant's chosen discipline
            throw new PolicyViolationException(
                    "the audit trail is unconditionally append-only — retention is the only removal");
        }
        if (policies.disciplineFor(typeName) == TenantPolicies.Discipline.APPEND_ONLY) {
            throw new PolicyViolationException(
                    "append-only write discipline forbids deleting " + typeName
                            + " — correct by supersession or entered-in-error (§15.2)");
        }
        inner.delete(typeName, id, expectedVersion, caller);
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
        if ("AuditEntry".equals(typeName)) {
            return;
        }
        // A tenant's audit level is a preference about VOLUME — how much of
        // ordinary traffic to keep. A disclosure record is a requirement: who
        // saw an identity, and why they said they needed it. Answering both
        // with one dial is what left an identifying read untraceable at
        // audit=writes, with the purpose stated to nobody (#114).
        //
        // So a read that asked for an identity is recorded whatever the level,
        // and nothing else changes: a request with no purpose is ordinary
        // traffic and follows the tenant's choice. The store already asserts
        // that some audit facts are not the tenant's to choose — the trail is
        // append-only against everyone including us, and the actor comes from
        // the authority rather than from the caller.
        if (policies.auditsReads() || cloud.jengu.dbo.core.api.Disclosure.purpose() != null) {
            record(interaction, typeName, targetId, null);
        }
    }

    void record(String interaction, String typeName, String targetId, String rule) {
        inner.put(PutRequest.create("AuditEntry",
                AuditModel.entry(Caller.current(), interaction, typeName, targetId, "ok", rule)));
    }

    /**
     * §15.1 open upward: applications contribute business-level events. The
     * caller supplies WHAT happened (code, target, coded detail — ids and
     * codes, never names); the machinery asserts WHO and WHEN.
     *
     * @return the created entry's id
     */
    @Override
    public String recordCustom(String code, String targetType, String targetId,
            java.util.Map<String, String> detail) {
        return recordCustom(code, targetType, targetId, detail, null);
    }

    /**
     * The same, carrying what a domain contributed in its own words — bytes a
     * face wrote, which this layer stores and never reads. What stays this
     * layer's is WHO and WHEN; what a caller said happened is the caller's,
     * and what it said it in is the face's.
     */
    @Override
    public String recordCustom(String code, String targetType, String targetId,
            java.util.Map<String, String> detail, byte[] contributed) {
        return recordCustom(code, targetType, targetId, detail, contributed, null);
    }

    /**
     * The same, effectively-once when the caller says which event this is
     * (#120).
     *
     * <p>An appliance forwards its audit at-least-once, because a transport
     * that guarantees less loses events and one that guarantees more does not
     * exist. The receiving side is what makes the delivery effectively-once,
     * and it does it by writing under the id the forwarder generated: the
     * second delivery finds the first and hands it back.
     *
     * <p>Without a stable id there is nothing to be idempotent about and the
     * entry is simply appended — which is right for an event this store made
     * itself, where every recording IS a distinct interaction.
     */
    @Override
    public String recordCustom(String code, String targetType, String targetId,
            java.util.Map<String, String> detail, byte[] contributed, String forwarded) {
        return recordForwarded(code, targetType, targetId, detail, contributed, forwarded).id();
    }

    @Override
    public cloud.jengu.dbo.rest.AuditProjection.Recorder.Entry recordForwarded(
            String code, String targetType, String targetId,
            java.util.Map<String, String> detail, byte[] contributed, String forwarded) {
        PutRequest request = PutRequest.create("AuditEntry",
                AuditModel.entry(Caller.current(), "custom",
                        targetType != null ? targetType : "none",
                        targetId, "ok", null, code,
                        detail != null ? detail : java.util.Map.of(), contributed, forwarded));
        if (forwarded == null || forwarded.isBlank()) {
            return new cloud.jengu.dbo.rest.AuditProjection.Recorder.Entry(
                    inner.put(request).id(), true);
        }
        cloud.jengu.dbo.core.api.PutResult result = inner.putIfAbsent(
                cloud.jengu.dbo.core.api.IdentityRef.identifier(
                        AuditModel.FORWARDED_SYSTEM, forwarded),
                request);
        return new cloud.jengu.dbo.rest.AuditProjection.Recorder.Entry(
                result.id(), result.created());
    }

    private static void refuseDirectAuditWrites(String typeName) {
        if ("AuditEntry".equals(typeName)) {
            throw new PolicyViolationException(
                    "the audit trail is written by the machinery — contribute via the audit recorder");
        }
    }
}
