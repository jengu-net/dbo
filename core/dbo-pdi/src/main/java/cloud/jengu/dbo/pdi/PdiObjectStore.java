package cloud.jengu.dbo.pdi;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The isolation decorator (§14.2): person-type payloads reach the inner
 * engine with their identifying elements ENCRYPTED IN PLACE under the
 * person's key ({@code __pdiEnc}) — one payload, one engine transaction,
 * and every downstream copy (history, envelopes, feed, archives, sync)
 * carries ciphertext by construction. Authorized reads reassemble; a
 * shredded or restricted person reads back pseudonymous, with the
 * ciphertext block stripped.
 *
 * <p>Person-type identity moves vault-side (HMAC claims) so
 * no-implicit-merge survives the split. Non-person types pass through
 * untouched.
 */
public final class PdiObjectStore implements ObjectStore {

    private final ObjectStore inner;
    private final PersonVault vault;
    private final PdiSpec spec;
    private final Coarsening coarsening;

    public PdiObjectStore(ObjectStore inner, PersonVault vault, PdiSpec spec) {
        this(inner, vault, spec, Coarsening.NONE);
    }

    /**
     * @param coarsening supplied by the face — the engine declares that an
     *                   element is generalised, the face knows how. Without
     *                   one, a generalised element is simply absent.
     */
    public PdiObjectStore(ObjectStore inner, PersonVault vault, PdiSpec spec,
            Coarsening coarsening) {
        this.inner = inner;
        this.vault = vault;
        this.spec = spec;
        this.coarsening = coarsening;
    }

    public PersonVault vault() {
        return vault;
    }

    // ------------------------------------------------------------- writes

    @Override
    public PutResult put(PutRequest request) {
        return put(request, cloud.jengu.dbo.core.api.Handling.Authority.TENANT_USERS);
    }

    /**
     * Forwards the caller's authority through the vault. Dropping it here
     * would be the quiet kind of hole: the shield would see the
     * least-privileged default while the caller believed otherwise.
     */
    @Override
    public java.util.List<PutResult> transact(java.util.List<PutRequest> requests) {
        // Each person-type request goes through the vault exactly as a single
        // put would — the unit is atomic below, the isolation is per request.
        return inner.transact(requests.stream().map(request -> {
            if (!spec.isPersonType(request.typeName())) {
                return request;
            }
            String id = request.id() != null ? request.id()
                    : cloud.jengu.dbo.core.UuidV7.newId();
            return new PutRequest(request.typeName(), id, request.expectedVersion(),
                    isolate(request.typeName(), id, request.payload()));
        }).toList());
    }

    @Override
    public PutResult put(PutRequest request, cloud.jengu.dbo.core.api.Handling.Authority caller) {
        if (!spec.isPersonType(request.typeName())) {
            return inner.put(request, caller);
        }
        // creates need the id BEFORE encryption — mint it here so the person
        // key exists for the very first version (the engine upserts by id)
        String id = request.id() != null ? request.id() : cloud.jengu.dbo.core.UuidV7.newId();
        return inner.put(new PutRequest(request.typeName(), id, request.expectedVersion(),
                isolate(request.typeName(), id, request.payload())), caller);
    }

    @Override
    public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        if (!spec.isPersonType(request.typeName())) {
            return inner.putIfAbsent(identity, request);
        }
        Optional<String> existing = ownerOf(identity);
        if (existing.isPresent()) {
            StoredObject current = inner.get(request.typeName(), existing.get()).orElseThrow();
            return new PutResult(current.id(), current.versionId(), false);
        }
        return put(PutRequest.create(request.typeName(), request.payload()));
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        if (!spec.isPersonType(request.typeName())) {
            return inner.putConditional(identity, request);
        }
        Optional<String> existing = ownerOf(identity);
        if (existing.isPresent()) {
            StoredObject current = inner.get(request.typeName(), existing.get()).orElseThrow();
            return put(PutRequest.update(request.typeName(), current.id(),
                    current.versionId(), request.payload()));
        }
        return put(PutRequest.create(request.typeName(), request.payload()));
    }

    private Optional<String> ownerOf(IdentityRef identity) {
        if (identity instanceof IdentityRef.ByIdentifier byId) {
            return vault.findByIdentifier(byId.identifier().system(), byId.identifier().value());
        }
        throw new IllegalArgumentException("person identity is identifier-based");
    }

    // ------------------------------------------------------------- reads

    @Override
    public Optional<StoredObject> get(String typeName, String id) {
        return inner.get(typeName, id).map(o -> reassembled(typeName, o));
    }

    @Override
    public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
        if (!spec.isPersonType(typeName)) {
            return inner.getByIdentifier(typeName, identifiers);
        }
        return identifiers.stream()
                .map(i -> vault.findByIdentifier(i.system(), i.value()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .distinct()
                .map(id -> inner.get(typeName, id))
                .filter(Optional::isPresent)
                .map(o -> reassembled(typeName, o.get()))
                .toList();
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        return inner.history(typeName, id).stream()
                .map(o -> reassembled(typeName, o))
                .toList();
    }

    /**
     * Matching on an identifying element is an identifying access, held to the
     * same rule as reading one (#115).
     *
     * <p>Under the membrane those elements never reach the inner payload, so a
     * query on one matches nothing and comes back empty — indistinguishable
     * from <i>nobody here is called that</i>. The refusal exists because the
     * silence is the defect: it is the identifying access that leaves no trace,
     * since no read happened.
     *
     * <p>Applied to every predicate a search can carry, not only equality. A
     * guard on {@code eq} that let {@code startsWith} through would be a guard
     * in name only.
     */
    private void guardIdentifyingSearch(Criteria criteria) {
        if (!spec.isPersonType(criteria.typeName())) {
            return;
        }
        // Only a CALLER's search. The store resolves identities on its own
        // account too — an authority matching a login to authenticate somebody,
        // a bring-up, a sync lane — and those run before any caller exists and
        // are not disclosures to anybody. Guarding them broke authentication
        // outright: the token endpoint answered 500 because minting a token
        // resolves a Person by identifier, which is exactly the shape this
        // refuses.
        //
        // Caller-presence is the honest discriminator rather than a flag,
        // because PDI requires the tenant authority to exist at all, so every
        // request that reaches this store through the serving surface has one
        // and every internal path does not.
        if (cloud.jengu.dbo.core.api.Caller.current() == null) {
            return;
        }
        java.util.List<String> paths = new java.util.ArrayList<>();
        criteria.equalsPredicates().forEach(p -> paths.add(p.path()));
        criteria.notEqualsPredicates().forEach(p -> paths.add(p.path()));
        criteria.startsWithPredicates().forEach(p -> paths.add(p.path()));
        criteria.rangePredicates().forEach(p -> paths.add(p.path()));
        // `missing` deliberately not guarded: asking whether a person HAS a
        // telecom is a question about the record's completeness, not about who
        // they are, and it can be answered from the coarse form.
        for (String path : paths) {
            String element = spec.identifyingElementFor(criteria.typeName(), path);
            if (element == null) {
                continue;
            }
            if (cloud.jengu.dbo.core.api.Disclosure.purpose() == null) {
                throw new cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException(
                        criteria.typeName(), element);
            }
            // A purpose was stated and this store still cannot match on the
            // element. Exact lookup covers the indexed ones — see
            // identifyingLookup — and a name is not among them: matching people
            // by name is its own problem and an empty answer would have said
            // nobody matches, which is a different thing.
            throw cloud.jengu.dbo.core.api.IdentifyingSearchRefusedException.notMatchable(
                    criteria.typeName(), element);
        }
    }

    /**
     * An exact lookup on an indexed identifying value, answered from the vault
     * rather than from an index that cannot hold it (#115).
     *
     * <p>Deliberately narrow: ONE predicate, equality, on an element the vault
     * indexes. That is a lookup — "who is behind this address", "which record
     * claims this identifier" — and not a search. A query combining an identifying value with other predicates is
     * refused rather than half-answered, because narrowing a result set the
     * store cannot fully evaluate is how a wrong answer gets a confident shape.
     *
     * <p>Returns empty when this is not such a lookup, and the caller falls
     * through to the guard.
     */
    private Optional<List<StoredObject>> identifyingLookup(Criteria criteria) {
        if (!spec.isPersonType(criteria.typeName())
                || criteria.equalsPredicates().size() != 1
                || !criteria.notEqualsPredicates().isEmpty()
                || !criteria.startsWithPredicates().isEmpty()
                || !criteria.rangePredicates().isEmpty()) {
            return Optional.empty();
        }
        Criteria.Eq only = criteria.equalsPredicates().get(0);
        String element = spec.identifyingElementFor(criteria.typeName(), only.path());
        if (element == null || cloud.jengu.dbo.core.api.Disclosure.purpose() == null) {
            return Optional.empty();
        }
        if ("telecom".equals(element)) {
            String value = only.value() instanceof EnvelopeValue.Token token ? token.code()
                    : only.value() instanceof EnvelopeValue.Str str ? str.value() : null;
            if (value == null) {
                return Optional.empty();
            }
            // The value is hashed on the way in and compared as a hash — the
            // plaintext is never at rest and never in a query (ADR 0056 §5), and
            // the same fingerprint goes to the trail so the lookup is answerable
            // later without the address ever being written down.
            cloud.jengu.dbo.core.api.Disclosure.matched(vault.fingerprintOf(value));
            List<StoredObject> found = new ArrayList<>();
            for (String personId : vault.findAllByIdentifier(TELECOM_SYSTEM, value)) {
                inner.get(criteria.typeName(), personId)
                        .map(o -> reassembled(criteria.typeName(), o))
                        .ifPresent(found::add);
            }
            return Optional.of(List.copyOf(found));
        }
        if ("identifier".equals(element)) {
            // A claimed identifier resolves only as its full (system, value)
            // pair. A bare value would ask every system at once — enumeration
            // wearing a smaller coat — so it falls through to the guard's
            // refusal instead of an answer (#136).
            if (!(only.value() instanceof EnvelopeValue.Token token)
                    || token.system() == null || token.system().isBlank()
                    || token.code() == null || token.code().isBlank()) {
                return Optional.empty();
            }
            cloud.jengu.dbo.core.api.Disclosure.matched(vault.fingerprintOf(token.code()));
            // Claims, not the shared-value index: an identity system names at
            // most one person, and the claim table's uniqueness is what makes
            // this answer at-most-one by construction. Asking by type scopes
            // the answer for free — a value claimed by a Patient answers
            // nothing to a Practitioner question.
            List<StoredObject> found = new ArrayList<>();
            vault.findByIdentifier(token.system(), token.code())
                    .flatMap(personId -> inner.get(criteria.typeName(), personId))
                    .map(o -> reassembled(criteria.typeName(), o))
                    .ifPresent(found::add);
            return Optional.of(List.copyOf(found));
        }
        return Optional.empty();
    }

    @Override
    public List<StoredObject> select(Criteria criteria) {
        Optional<List<StoredObject>> lookup = identifyingLookup(criteria);
        if (lookup.isPresent()) {
            return lookup.get();
        }
        guardIdentifyingSearch(criteria);
        return inner.select(criteria).stream()
                .map(o -> reassembled(criteria.typeName(), o))
                .toList();
    }

    @Override
    public long count(Criteria criteria) {
        Optional<List<StoredObject>> lookup = identifyingLookup(criteria);
        if (lookup.isPresent()) {
            return lookup.get().size();
        }
        guardIdentifyingSearch(criteria);
        return inner.count(criteria);
    }

    @Override
    public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
        Optional<List<StoredObject>> lookup = identifyingLookup(criteria);
        if (lookup.isPresent()) {
            // One terminal chunk, no continuation. An exact lookup is bounded
            // by construction — the values it matches on are shared by a
            // household, not by a population — so there is nothing to page
            // through and no cursor that would mean anything.
            //
            // The cursor argument is ignored rather than refused: a caller can
            // only have one from a differently shaped query, and answering the
            // question it asked beats a refusal about bookkeeping. Nothing
            // loops, because no next cursor goes back.
            return new FeedChunk<>(lookup.get(), null, true);
        }
        guardIdentifyingSearch(criteria);
        FeedChunk<StoredObject> chunk = inner.page(criteria, cursor);
        return new FeedChunk<>(chunk.items().stream()
                .map(o -> reassembled(criteria.typeName(), o))
                .toList(), chunk.nextCursor(), chunk.drained());
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        inner.delete(typeName, id, expectedVersion);
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion,
            cloud.jengu.dbo.core.api.Handling.Authority caller) {
        inner.delete(typeName, id, expectedVersion, caller);
    }

    @Override
    public int rebuildEnvelopes(String typeName) {
        return inner.rebuildEnvelopes(typeName);
    }

    /**
     * Where a telecom value is indexed, which is inside the vault and nowhere
     * else. Not a coding system and never rendered — a client neither sees it
     * nor needs to.
     */
    private static final String TELECOM_SYSTEM = "urn:dbo:pdi:telecom";

    // ------------------------------------------------------------- split / join

    @SuppressWarnings("unchecked")
    private byte[] isolate(String typeName, String personId, byte[] payload) {
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(
                new String(payload, StandardCharsets.UTF_8));
        Map<String, Object> identifying = new LinkedHashMap<>();
        for (String element : spec.identifyingElements(typeName)) {
            Object value = parsed.remove(element);
            if (value == null) {
                continue;
            }
            identifying.put(element, value);
            if (spec.dispositionOf(typeName, element) == PdiSpec.Disposition.GENERALISE) {
                // The coarse value is computed here and left in the clear,
                // because a reader without the key has no plaintext to derive
                // it from at read time. The full value still rides encrypted.
                Object coarse = coarsening.coarsen(typeName, element, value);
                if (coarse != null) {
                    parsed.put(element, coarse);
                }
            }
        }
        // Telecom goes into the index and is NEVER claimed: "who is behind
        // this address" is an ordinary provisioning and sign-in question, and
        // it is the lookup that breaks first once the plaintext is gone (#115).
        // Two people share a phone and neither is wrong, so indexing and
        // exclusivity part company here — the one place they should.
        Object telecomList = identifying.get("telecom");
        if (telecomList instanceof List<?> contacts) {
            for (Object contact : contacts) {
                if (contact instanceof Map<?, ?> point && point.get("value") != null) {
                    vault.index(personId, TELECOM_SYSTEM, String.valueOf(point.get("value")));
                }
            }
        }
        // identity claims from the ORIGINAL identifier list, vault-side
        Object identifierList = identifying.get("identifier");
        if (identifierList instanceof List<?> list) {
            List<String[]> claims = list.stream()
                    .filter(e -> e instanceof Map<?, ?> m && m.get("system") != null && m.get("value") != null)
                    .map(e -> new String[] {
                            String.valueOf(((Map<?, ?>) e).get("system")),
                            String.valueOf(((Map<?, ?>) e).get("value"))})
                    .toList();
            List<String[]> claimed = claims;
            vault.claim(personId, claims).ifPresent(owner -> {
                throw new IdentityConflictException(owner, personId,
                        new Identifier(claimed.get(0)[0], claimed.get(0)[1]));
            });
        }
        if (!identifying.isEmpty()) {
            byte[] key = vault.keyFor(personId, true).orElseThrow(
                    () -> new IllegalStateException("person " + personId + " is shredded — no new identifying data"));
            parsed.put("__pdiEnc", Base64.getEncoder().encodeToString(
                    vault.encrypt(key, Json.render(identifying).getBytes(StandardCharsets.UTF_8))));
        }
        return Json.render(parsed).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Whether this person's identity is GONE rather than merely undisclosed.
     *
     * <p>Restricted or shredded: there is no key to be had by anybody. That is
     * a different fact from a caller who was told to omit, and the difference
     * decides whether a coarse value survives — after an erasure it must not,
     * and under omission it must.
     */
    private boolean erased(String id) {
        return vault.restricted(id) || vault.keyFor(id, false).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private StoredObject reassembled(String typeName, StoredObject object) {
        if (typeName == null || !spec.isPersonType(typeName)) {
            return object;
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(
                new String(object.payload(), StandardCharsets.UTF_8));
        cloud.jengu.dbo.core.api.Disclosure.Mode mode =
                cloud.jengu.dbo.core.api.Disclosure.mode();
        if (mode == cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE
                && cloud.jengu.dbo.core.api.Disclosure.purpose() == null) {
            // Refused before anything is decrypted, and refused rather than
            // downgraded: a caller handed a pseudonymous resource where it
            // asked for a whole one carries on as though it had what it asked
            // for, and nothing records why an identity was seen (#114).
            throw new cloud.jengu.dbo.core.api.DisclosureRefusedException(typeName);
        }
        if (mode == cloud.jengu.dbo.core.api.Disclosure.Mode.ENCRYPTED) {
            // The carrier form: what was stored, handed back as stored. The
            // ciphertext block stays where it is and nothing here reads it.
            return object;
        }
        Object enc = parsed.remove("__pdiEnc");
        if (enc == null) {
            return object;
        }
        Optional<byte[]> key = mode == cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE
                && !vault.restricted(object.id())
                ? vault.keyFor(object.id(), false)
                : Optional.empty();
        if (key.isPresent()) {
            Map<String, Object> identifying = (Map<String, Object>) Json.parse(new String(
                    vault.decrypt(key.get(), Base64.getDecoder().decode(String.valueOf(enc))),
                    StandardCharsets.UTF_8));
            parsed.putAll(identifying);
        }
        if (key.isEmpty() && erased(object.id())) {
            // Shredded or restricted, which is not the same as merely lacking
            // authority — and not the same as being told to omit. A coarse
            // value is a DISCLOSURE control — what somebody without the right
            // to see an identity gets instead — and it has no business
            // surviving an erasure. Leaving a birth year behind after Article
            // 17 would be retaining personal data in a weaker form and calling
            // it gone.
            //
            // Under OMIT the coarse value STAYS: nothing was erased, the caller
            // simply has no right to the whole. That is the difference the
            // three modes exist to draw, and conflating them would make a
            // pseudonymous read indistinguishable from a shredded person.
            for (String element : spec.identifyingElements(typeName)) {
                if (spec.dispositionOf(typeName, element) == PdiSpec.Disposition.GENERALISE) {
                    parsed.remove(element);
                }
            }
        }
        // shredded or restricted: the ciphertext block is stripped — the read
        // is a clean pseudonymous resource
        return new StoredObject(object.id(), object.typeName(), object.versionId(),
                object.lastUpdated(), Json.render(parsed).getBytes(StandardCharsets.UTF_8),
                object.deleted(), object.payloadVersion(), object.origin(),
                object.shadowing());
    }

    // ------------------------------------------------------------- rights

    /**
     * §14.5 access/portability: the person's record, reassembled, plus every
     * record of the given types that references them (already pseudonymous).
     */
    public String exportPerson(String typeName, String personId,
            List<LinkedType> linkedTypes) {
        // The subject's own data, handed to the subject: Article 20 is not
        // satisfied by a pseudonymous file, and ciphertext they hold no key for
        // satisfies it even less. So this states its purpose rather than
        // inheriting whatever the request was doing — PATRQT, which is exactly
        // what this is, and which the audit entry then carries (#114).
        cloud.jengu.dbo.core.api.Disclosure.Mode restore =
                cloud.jengu.dbo.core.api.Disclosure.mode();
        String restorePurpose = cloud.jengu.dbo.core.api.Disclosure.purpose();
        cloud.jengu.dbo.core.api.Disclosure.set(
                cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE, "PATRQT");
        try {
            return exportedPerson(typeName, personId, linkedTypes);
        } finally {
            cloud.jengu.dbo.core.api.Disclosure.set(restore, restorePurpose);
        }
    }

    private String exportedPerson(String typeName, String personId,
            List<LinkedType> linkedTypes) {
        StoredObject person = get(typeName, personId)
                .orElseThrow(() -> new IllegalArgumentException("no such person"));
        StringBuilder sb = new StringBuilder("{\"person\":")
                .append(new String(person.payload(), StandardCharsets.UTF_8))
                .append(",\"records\":[");
        boolean first = true;
        for (LinkedType linked : linkedTypes) {
            for (StoredObject record : inner.select(Criteria.of(linked.typeName())
                    .referencing(linked.refPath(), typeName, personId))) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(new String(record.payload(), StandardCharsets.UTF_8));
            }
        }
        return sb.append("]}").toString();
    }

    /** A record type + the reference path pointing at the person. */
    public record LinkedType(String typeName, String refPath) {}
}
