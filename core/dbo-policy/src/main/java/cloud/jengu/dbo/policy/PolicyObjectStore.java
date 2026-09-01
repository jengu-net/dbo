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
        cloud.jengu.dbo.rest.AuditProjection.Recorder,
        cloud.jengu.dbo.core.api.AuditReplay {

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
        if (result.isPresent() && !withinReach(typeName, id)) {
            // Absent, not forbidden: a 403 for an id in another organisation
            // confirms the id exists, which is exactly what a compartment is
            // supposed to keep from leaking.
            result = Optional.empty();
        }
        auditRead("read", typeName, id);
        return result;
    }

    /**
     * Whether this record is inside the caller's organisational reach.
     *
     * <p>Three ways to be inside, in the order they decide: the request is
     * unbounded (tenant-wide grants, or no caller seam at all — internal
     * machinery); the type declares no organisation path, so it is shared
     * rather than somebody's; or the record's declared organisation edge
     * points at one of the organisations the token was minted for.
     *
     * <p>{@code Organization} itself has no edge to consult — its membership
     * IS its id, so the reach set is checked directly.
     */
    private boolean withinReach(String typeName, String id) {
        java.util.Set<String> reach = cloud.jengu.dbo.core.api.Reach.organisations();
        if (reach == null) {
            return true;
        }
        if ("Organization".equals(typeName)) {
            return reach.contains(id);
        }
        String path = policies.organisationPathFor(typeName);
        if (path == null) {
            return true;
        }
        return !inner.select(Criteria.of(typeName).idEquals(id)
                .referencingAny(path, "Organization", reach).limit(1)).isEmpty();
    }

    /**
     * The same reach, applied to a whole query rather than one record: the
     * criteria gain the compartment predicate, so what comes back is already
     * only the caller's — a filtered page rather than a page and a filter,
     * because a post-filtered page would leak the compartment's size through
     * the gaps in its numbering.
     */
    private Criteria reached(Criteria criteria) {
        java.util.Set<String> reach = cloud.jengu.dbo.core.api.Reach.organisations();
        if (reach == null) {
            return criteria;
        }
        String path = policies.organisationPathFor(criteria.typeName());
        if (path != null) {
            criteria.referencingAny(path, "Organization", reach);
        }
        return criteria;
    }

    @Override
    public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
        List<StoredObject> result = inner.getByIdentifier(typeName, identifiers).stream()
                .filter(o -> withinReach(typeName, o.id()))
                .toList();
        auditRead("search", typeName, null);
        return result;
    }

    /**
     * A select inside the caller's reach. {@code Organization} needs the
     * post-filter shape — its membership is its id, not an edge — and the
     * result sets there are tree-sized, so filtering after the query costs a
     * dozen comparisons rather than a second query form.
     */
    private List<StoredObject> selectReached(Criteria criteria) {
        java.util.Set<String> reach = cloud.jengu.dbo.core.api.Reach.organisations();
        if (reach != null && "Organization".equals(criteria.typeName())) {
            return inner.select(criteria).stream()
                    .filter(o -> reach.contains(o.id()))
                    .toList();
        }
        return inner.select(reached(criteria));
    }

    private FeedChunk<StoredObject> organisationFiltered(FeedChunk<StoredObject> chunk) {
        java.util.Set<String> reach = cloud.jengu.dbo.core.api.Reach.organisations();
        if (reach == null) {
            return chunk;
        }
        List<StoredObject> kept = chunk.items().stream()
                .filter(o -> !"Organization".equals(o.typeName()) || reach.contains(o.id()))
                .toList();
        return kept.size() == chunk.items().size() ? chunk
                : new FeedChunk<>(kept, chunk.nextCursor(), chunk.drained());
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        if (!withinReach(typeName, id)) {
            auditRead("history", typeName, id);
            return List.of();
        }
        List<StoredObject> result = inner.history(typeName, id);
        auditRead("history", typeName, id);
        return result;
    }

    @Override
    public List<StoredObject> select(Criteria criteria) {
        List<StoredObject> result = selectReached(criteria);
        auditRead("search", criteria.typeName(), null);
        return result;
    }

    @Override
    public long count(Criteria criteria) {
        if ("Organization".equals(criteria.typeName())) {
            return selectReached(criteria).size();
        }
        return inner.count(reached(criteria));
    }

    @Override
    public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
        FeedChunk<StoredObject> result = inner.page(reached(criteria), cursor);
        result = organisationFiltered(result);
        auditRead("search", criteria.typeName(), null);
        return result;
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
        // audit=writes, with the purpose stated to nobody.
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
     *.
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

    /**
     * §7.8: replays an entry another appliance recorded, and audits nothing
     * for having done so.
     *
     * <p>The admitted path the refusal names. It goes to {@code inner} the
     * way the recorder does — the refusal above is the wrapper's, and what
     * gets through it does so by being this method rather than by being a
     * caller who claimed something. Nothing here can express any other write:
     * the type is fixed, the claim is the source's identity, and there is no
     * argument for a version to overwrite.
     */
    @Override
    public boolean replayAuditEntry(String sourceAppliance, String sourceEntryId,
            long sourceVersion, byte[] payload, java.time.Instant recordedAt) {
        String claim = sourceAppliance + "/" + sourceEntryId;
        // putIfAbsent, not put: a lane delivers at least once and the claim is
        // what makes the second delivery find the first. A replayed
        // entry is also never updated — appending is the only thing that
        // happens to a trail, here as everywhere else.
        PutResult result = inner.putIfAbsent(
                IdentityRef.identifier(AuditModel.FORWARDED_SYSTEM, claim),
                // The source's version travels beside its time: the store
                // keeps a replayed moment only for a write that says which
                // version it is replaying, and one without the other would
                // quietly become the arrival time again.
                new PutRequest("AuditEntry", null, null,
                        AuditModel.recordedElsewhere(payload, sourceAppliance, claim),
                        sourceVersion, recordedAt, true));
        return result.created();
    }

    private static void refuseDirectAuditWrites(String typeName) {
        if ("AuditEntry".equals(typeName)) {
            // The message names the one way through, because a reader who
            // meets this refusal is asking exactly that question — and an
            // admission nobody can find from the refusal is one somebody
            // reinvents beside it.
            throw new PolicyViolationException(
                    "the audit trail is written by the machinery — contribute via the audit "
                            + "recorder, or replay another appliance's entry through "
                            + "AuditReplay.replayAuditEntry (§7.8)");
        }
    }
}
