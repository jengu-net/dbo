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
import java.util.Set;
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

    /**
     * What each person type is identified by — the tenant's declaration,
     * carried past the transform that forgets it.
     *
     * <p>Empty for a type nothing declared, and that is an answer: it claims
     * nothing. A store built without it claims nothing anywhere, which fails
     * loudly at the first tenant that wanted uniqueness rather than quietly
     * staking exclusivity on every number that passes.
     */
    private final java.util.Map<String, Set<String>> identifiedBy;

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
        this(inner, vault, spec, coarsening, java.util.Map.of());
    }

    /**
     * @param identifiedBy per person type, the systems the tenant declared it
     *                     is identified by — from
     *                     {@link PdiSetup#identifiedBy}, taken before the
     *                     transform rewrites every person type to INTERNAL.
     *                     A type absent from the map, or present with no
     *                     systems, claims nothing.
     */
    public PdiObjectStore(ObjectStore inner, PersonVault vault, PdiSpec spec,
            Coarsening coarsening, java.util.Map<String, Set<String>> identifiedBy) {
        this.inner = inner;
        this.vault = vault;
        this.spec = spec;
        this.coarsening = coarsening;
        this.identifiedBy = java.util.Map.copyOf(identifiedBy);
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
        Optional<StoredObject> existing = recordOf(identity, request.typeName());
        if (existing.isPresent()) {
            return new PutResult(existing.get().id(), existing.get().versionId(), false);
        }
        return put(PutRequest.create(request.typeName(), request.payload()));
    }

    @Override
    public PutResult putConditional(IdentityRef identity, PutRequest request) {
        if (!spec.isPersonType(request.typeName())) {
            return inner.putConditional(identity, request);
        }
        Optional<StoredObject> existing = recordOf(identity, request.typeName());
        if (existing.isPresent()) {
            return put(PutRequest.update(request.typeName(), existing.get().id(),
                    existing.get().versionId(), request.payload()));
        }
        return put(PutRequest.create(request.typeName(), request.payload()));
    }

    private Optional<String> ownerOf(IdentityRef identity) {
        if (identity instanceof IdentityRef.ByIdentifier byId) {
            return vault.findByIdentifier(byId.identifier().system(), byId.identifier().value());
        }
        throw new IllegalArgumentException("person identity is identifier-based");
    }

    /**
     * The record OF THIS TYPE that the identified person is spoken about by.
     *
     * <p>A conditional write names an identity, and the identity names a
     * human. Which of that human's records it addresses is what the type says
     * — so the same national number reaches their Patient from a Patient write
     * and their Practitioner from a Practitioner one, and neither is the
     * other's business.
     */
    private Optional<StoredObject> recordOf(IdentityRef identity, String typeName) {
        return ownerOf(identity).stream()
                .flatMap(person -> vault.recordsOf(person, typeName).stream())
                .flatMap(record -> inner.get(typeName, record).stream())
                .findFirst();
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
        // A value identifies a HUMAN; the type says which of their records is
        // wanted. Going straight from the value to a record of that id was the
        // same step only because a person was a row.
        return identifiers.stream()
                .flatMap(i -> peopleHolding(i.system(), i.value()).stream())
                .distinct()
                .flatMap(person -> vault.recordsOf(person, typeName).stream())
                .distinct()
                .map(record -> inner.get(typeName, record))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        rows -> reassembled(typeName, rows)));
    }

    @Override
    public List<StoredObject> history(String typeName, String id) {
        // Versions of one record, which after a merge may name more than one
        // person: the earlier ones were sealed before the human was known.
        return reassembled(typeName, inner.history(typeName, id));
    }

    /**
     * Matching on an identifying element is an identifying access, held to the
     * same rule as reading one.
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
     * rather than from an index that cannot hold it.
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
                || !criteria.rangePredicates().isEmpty()
                || !criteria.shapePredicates().isEmpty()) {
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
            // plaintext is never at rest and never in a query, and
            // the same fingerprint goes to the trail so the lookup is answerable
            // later without the address ever being written down.
            cloud.jengu.dbo.core.api.Disclosure.matched(vault.fingerprintOf(value));
            List<StoredObject> found = new ArrayList<>();
            for (String person : vault.findAllByIdentifier(TELECOM_SYSTEM, value)) {
                for (String record : vault.recordsOf(person, criteria.typeName())) {
                    inner.get(criteria.typeName(), record)
                            .map(o -> reassembled(criteria.typeName(), o))
                            .ifPresent(found::add);
                }
            }
            return Optional.of(List.copyOf(found));
        }
        if ("identifier".equals(element)) {
            // A claimed identifier resolves only as its full (system, value)
            // pair. A bare value would ask every system at once — enumeration
            // wearing a smaller coat — so it falls through to the guard's
            // refusal instead of an answer.
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
            for (String person : peopleHolding(token.system(), token.code())) {
                for (String record : vault.recordsOf(person, criteria.typeName())) {
                    inner.get(criteria.typeName(), record)
                            .map(o -> reassembled(criteria.typeName(), o))
                            .ifPresent(found::add);
                }
            }
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
        return reassembled(criteria.typeName(), inner.select(criteria));
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
        return new FeedChunk<>(reassembled(criteria.typeName(), chunk.items()).stream()
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

    @Override
    public cloud.jengu.dbo.core.api.TypeRegistration registrationOf(String typeName) {
        return inner.registrationOf(typeName);
    }

    @Override
    public int reindexUnder(cloud.jengu.dbo.core.api.TypeRegistration replacement) {
        return inner.reindexUnder(replacement);
    }

    /**
     * Where a telecom value is indexed, which is inside the vault and nowhere
     * else. Not a coding system and never rendered — a client neither sees it
     * nor needs to.
     */
    private static final String TELECOM_SYSTEM = PersonVault.TELECOM_SYSTEM;

    // ------------------------------------------------------------- split / join

    @SuppressWarnings("unchecked")
    /**
     * Which human this record speaks about.
     *
     * <p>Two ways to know, both declared and neither inferred. A value under a
     * system the tenant declared identifying <b>is</b> that person — that is
     * what the declaration means, and the owner of the claim is the answer. A
     * record already bound to somebody stays theirs. Anything else is a human
     * nobody has identified yet, who gets a person of their own.
     *
     * <p>The conflict that remains is the one the promise is about: a record
     * already speaking about person A, given a value that identifies person B.
     * That is a request to merge two humans, and it is refused and surfaced
     * rather than decided here.
     */
    /** Whether any person type here is declared identified by this system. */
    private boolean identifiesSomebodyHere(String system) {
        return identifiedBy.values().stream().anyMatch(systems -> systems.contains(system));
    }

    private String personFor(String typeName, String recordId, List<String[]> namesTheHuman,
            List<String[]> claims) {
        Optional<String> bound = vault.personOf(typeName, recordId);
        Optional<String> byValue = Optional.empty();
        for (String[] sv : namesTheHuman) {
            Optional<String> owner = vault.findByIdentifier(sv[0], sv[1]);
            if (owner.isPresent()) {
                if (byValue.isPresent() && !byValue.get().equals(owner.get())) {
                    throw new IdentityConflictException(byValue.get(), owner.get(),
                            new Identifier(sv[0], sv[1]));
                }
                byValue = owner;
            }
        }
        if (byValue.isPresent() && bound.isPresent() && !byValue.get().equals(bound.get())) {
            // A record written before it carried the number that identifies
            // it, now carrying it. The person it had was provisional — nobody
            // could reach them by any value — so they were always this human,
            // and saying so is what the number just did. Absorbed rather than
            // refused, and rather than re-keyed: the earlier person keeps
            // their key, so the history sealed under it still opens and still
            // dies when this human is erased.
            //
            // The same rule a link follows, because it is the same situation
            // arriving through the other door.
            if (!vault.claimed(bound.get())) {
                vault.absorb(bound.get(), byValue.get());
            } else {
                throw new IdentityConflictException(byValue.get(), bound.get(),
                        new Identifier(namesTheHuman.get(0)[0], namesTheHuman.get(0)[1]));
            }
        }
        String person = byValue.or(() -> bound).orElseGet(cloud.jengu.dbo.core.UuidV7::newId);
        // One human may be spoken about by a Person and a Patient; they are
        // different capacities and both are theirs. What they may NOT be is
        // two Persons — a type the tenant declared identified by that system
        // has the value AS its identity there, so a second record of that type
        // holding it is a duplicate of the first rather than another capacity.
        // That is the conflict the promise is about, and it is surfaced.
        if (!claims.isEmpty()) {
            for (String other : vault.recordsOf(person, typeName)) {
                if (!other.equals(recordId)) {
                    throw new IdentityConflictException(other, recordId,
                            new Identifier(claims.get(0)[0], claims.get(0)[1]));
                }
            }
        }
        return person;
    }

    /**
     * Records this one says are the same human.
     *
     * <p>The second way a tenant declares who somebody is. An identifier says
     * it through a system the tenant named; a link says it about records
     * directly, and it is no more inferred than the other — the tenant wrote
     * it down. It matters because a record carrying no identifying value of
     * its own is otherwise a person nobody can reach: its key is its own, and
     * an erasure asked for the human it belongs to would not touch it.
     *
     * <p>A target nobody has identified is absorbed. A target that holds
     * claims of its own is NOT: that link asserts one human has two identities
     * — sometimes true, sometimes a mistake, and never something this store
     * can tell apart. It is refused and surfaced, which is what the promise
     * about merging asks for.
     */
    private void bindWhatItLinksTo(Map<String, Object> parsed, String personId) {
        if (!(parsed.get("link") instanceof List<?> links)) {
            return;
        }
        for (Object link : links) {
            if (!(link instanceof Map<?, ?> entry)
                    || !(entry.get("target") instanceof Map<?, ?> target)
                    || target.get("reference") == null) {
                continue;
            }
            String reference = String.valueOf(target.get("reference"));
            int slash = reference.lastIndexOf('/');
            if (slash < 0 || slash == reference.length() - 1) {
                continue;
            }
            String linkedType = reference.substring(0, slash);
            String linkedId = reference.substring(slash + 1);
            if (!spec.isPersonType(linkedType)) {
                continue;
            }
            Optional<String> theirs = vault.personOf(linkedType, linkedId);
            if (theirs.isEmpty()) {
                vault.bind(linkedType, linkedId, personId);
                continue;
            }
            String other = vault.survivorOf(theirs.get());
            if (other.equals(personId)) {
                continue;
            }
            if (vault.claimed(other)) {
                throw IdentityConflictException.wouldMerge(other, personId);
            }
            vault.absorb(other, personId);
        }
    }

    private byte[] isolate(String typeName, String recordId, byte[] payload) {
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
        // it is the lookup that breaks first once the plaintext is gone.
        // Two people share a phone and neither is wrong, so indexing and
        // exclusivity part company here — the one place they should.
        // WHO this record speaks about, decided before anything is claimed,
        // indexed or encrypted — every one of those is a fact about a person,
        // and the record is only where it was written down.
        List<String[]> present = identifying.get("identifier") instanceof List<?> list
                ? list.stream()
                        .filter(e -> e instanceof Map<?, ?> m
                                && m.get("system") != null && m.get("value") != null)
                        .map(e -> new String[] {
                                String.valueOf(((Map<?, ?>) e).get("system")),
                                String.valueOf(((Map<?, ?>) e).get("value"))})
                        .toList()
                : List.<String[]>of();
        Set<String> identifiedBy = identitySystemsOf(typeName);
        List<String[]> claims = present.stream()
                .filter(sv -> identifiedBy.contains(sv[0]))
                .toList();
        // WHO this is and WHAT it may claim are different questions, and the
        // type's declaration answers only the second.
        //
        // A tenant declaring a system identifying for Person has said that
        // system names people. A Patient carrying a value under it is that
        // person — carrying somebody's national number is not a weaker fact
        // about who they are because of the type it was written on. What the
        // declaration decides is whether this record's value is exclusive:
        // Patient declared internal claims nothing, so two of them may carry
        // it, and neither is refused.
        //
        // Filtering the binding by the record's own type instead left an
        // identity record bound to a person nobody could reach by the number
        // on it, so a lookup by that number answered an empty bundle — and
        // empty reads as nobody here, which is the answer this store exists
        // not to give.
        List<String[]> namesTheHuman = present.stream()
                .filter(sv -> identifiesSomebodyHere(sv[0]))
                .toList();
        String personId = personFor(typeName, recordId, namesTheHuman, claims);
        vault.bind(typeName, recordId, personId);
        bindWhatItLinksTo(parsed, personId);

        Object telecomList = identifying.get("telecom");
        if (telecomList instanceof List<?> contacts) {
            for (Object contact : contacts) {
                if (contact instanceof Map<?, ?> point && point.get("value") != null) {
                    vault.index(personId, TELECOM_SYSTEM, String.valueOf(point.get("value")));
                }
            }
        }
        // The rest are indexed and not claimed, the same parting telecom
        // makes above and for the same reason: finding a person by a value
        // replaces plaintext search once the plaintext is gone, and refusing a
        // SECOND person the same value is a uniqueness policy the tenant
        // declares. Claiming is a no-op where this person already owns it,
        // which is what makes a record writable back.
        for (String[] sv : present) {
            if (!identifiedBy.contains(sv[0])) {
                vault.index(personId, sv[0], sv[1]);
            }
        }
        if (!claims.isEmpty()) {
            vault.claim(personId, claims).ifPresent(owner -> {
                throw new IdentityConflictException(owner, personId,
                        new Identifier(claims.get(0)[0], claims.get(0)[1]));
            });
        }
        if (!identifying.isEmpty()) {
            byte[] key = vault.keyFor(personId, true).orElseThrow(
                    () -> new IllegalStateException("person " + personId
                            + " is shredded — no new identifying data ("
                            + cloud.jengu.dbo.promises.DboPromises.PDI_CRYPTO_SHREDDING.code()
                            + ")"));
            parsed.put("__pdiEnc", Base64.getEncoder().encodeToString(
                    vault.encrypt(key, Json.render(identifying).getBytes(StandardCharsets.UTF_8))));
            // Stamped beside the ciphertext so a read knows whose key opens it
            // without asking the vault which person this record is about. It
            // is the seam the page-at-a-time key resolution needs.
            parsed.put("__pdiPerson", personId);
        }
        return Json.render(parsed).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The systems this type is identified BY, as the tenant declared them.
     *
     * <p>Read from what was handed in rather than from the inner store's
     * registration: {@link PdiSetup} has already rewritten every person type
     * to INTERNAL with no systems by the time the inner store sees one, which
     * is the whole point of the transform — the payload reaches it stripped,
     * so envelope identity would claim nothing. Asking the inner store here
     * returns an empty set for every type and quietly claims nothing at all.
     */
    /**
     * Everybody this value belongs to: the one who claims it, or everybody
     * indexed under it.
     *
     * <p>Both tables, and that is not the fudge it would once have been. A
     * claim answers at most one person by construction and is consulted first.
     * The index answers several, and several is the truth for a value nobody
     * has exclusive title to — which the tenant said by not declaring the type
     * identified by it.
     *
     * <p>Reading only the claims left a record this store holds unfindable by
     * the number written on it, answered as an empty bundle rather than a
     * refusal. Empty reads as nobody here. Consulting the index alongside was
     * the wrong fix while the subject of both tables was a ROW — "several
     * rows" meant nothing a caller could interpret. The subject is a person
     * now, and several people holding one value is a fact with a meaning.
     */
    private List<String> peopleHolding(String system, String value) {
        return vault.findByIdentifier(system, value)
                .map(List::of)
                .orElseGet(() -> vault.findAllByIdentifier(system, value));
    }

    private Set<String> identitySystemsOf(String typeName) {
        return identifiedBy.getOrDefault(typeName, Set.of());
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
    /**
     * A page of records, with the vault asked once instead of per row.
     *
     * <p>Which person sealed a payload is written in the payload, so the whole
     * page's people are known before the vault is asked anything — and several
     * records of one human collapse to one entry. What was three queries a row
     * is two for the page: this one, and nothing else.
     */
    private List<StoredObject> reassembled(String typeName, List<StoredObject> objects) {
        if (!spec.isPersonType(typeName) || objects.isEmpty()) {
            return objects;
        }
        Set<String> people = new java.util.LinkedHashSet<>();
        for (StoredObject object : objects) {
            sealingPersonOf(object).ifPresent(people::add);
        }
        Map<String, PersonVault.Sealed> sealed = vault.sealedUnder(people);
        List<StoredObject> out = new ArrayList<>(objects.size());
        for (StoredObject object : objects) {
            out.add(reassembled(typeName, object, sealed));
        }
        return List.copyOf(out);
    }

    /** The person named in the payload as having sealed it, if it says. */
    @SuppressWarnings("unchecked")
    private Optional<String> sealingPersonOf(StoredObject object) {
        Object parsed = Json.parse(new String(object.payload(), StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> fields && fields.get("__pdiPerson") != null) {
            return Optional.of(String.valueOf(fields.get("__pdiPerson")));
        }
        return Optional.empty();
    }

    private StoredObject reassembled(String typeName, StoredObject object) {
        return reassembled(typeName, object, Map.of());
    }

    private StoredObject reassembled(String typeName, StoredObject object,
            Map<String, PersonVault.Sealed> known) {
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
            // for, and nothing records why an identity was seen.
            throw new cloud.jengu.dbo.core.api.DisclosureRefusedException(typeName);
        }
        if (mode == cloud.jengu.dbo.core.api.Disclosure.Mode.ENCRYPTED) {
            // The carrier form: what was stored, handed back as stored. The
            // ciphertext block stays where it is and nothing here reads it.
            return object;
        }
        Object enc = parsed.remove("__pdiEnc");
        // Whose this is, taken from the record rather than asked of the vault.
        // A record written before it was stamped, or one that arrived by
        // replication, still answers — from the map, once.
        Object stamped = parsed.remove("__pdiPerson");
        if (enc == null) {
            return object;
        }
        // The stamp names what SEALED these bytes and never changes; who they
        // are now can, because a merge moves a record without rewriting its
        // history. So the key comes from the stamp and everything about the
        // human comes from whoever they turned out to be.
        String sealedBy = stamped != null ? String.valueOf(stamped)
                : vault.personOf(typeName, object.id()).orElse(object.id());
        PersonVault.Sealed state = known.containsKey(sealedBy) ? known.get(sealedBy)
                : vault.sealedUnder(List.of(sealedBy))
                        .getOrDefault(sealedBy, PersonVault.Sealed.unknown(sealedBy));
        Optional<byte[]> key = mode == cloud.jengu.dbo.core.api.Disclosure.Mode.INCLUDE
                && !state.restricted() && state.key() != null
                ? Optional.of(state.key())
                : Optional.empty();
        if (key.isPresent()) {
            Map<String, Object> identifying = (Map<String, Object>) Json.parse(new String(
                    vault.decrypt(key.get(), Base64.getDecoder().decode(String.valueOf(enc))),
                    StandardCharsets.UTF_8));
            parsed.putAll(identifying);
        }
        if (key.isEmpty() && (state.restricted() || state.key() == null)) {
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
        // what this is, and which the audit entry then carries.
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
