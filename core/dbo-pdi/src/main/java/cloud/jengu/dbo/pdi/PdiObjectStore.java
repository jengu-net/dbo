package cloud.jengu.dbo.pdi;

import cloud.jengu.dbo.core.api.Criteria;
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
     * least-privileged default while the caller believed otherwise (dbo#41).
     */
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

    @Override
    public List<StoredObject> select(Criteria criteria) {
        return inner.select(criteria).stream()
                .map(o -> reassembled(criteria.typeName(), o))
                .toList();
    }

    @Override
    public long count(Criteria criteria) {
        return inner.count(criteria);
    }

    @Override
    public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
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

    @SuppressWarnings("unchecked")
    private StoredObject reassembled(String typeName, StoredObject object) {
        if (typeName == null || !spec.isPersonType(typeName)) {
            return object;
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(
                new String(object.payload(), StandardCharsets.UTF_8));
        Object enc = parsed.remove("__pdiEnc");
        if (enc == null) {
            return object;
        }
        Optional<byte[]> key = vault.restricted(object.id())
                ? Optional.empty()
                : vault.keyFor(object.id(), false);
        if (key.isPresent()) {
            Map<String, Object> identifying = (Map<String, Object>) Json.parse(new String(
                    vault.decrypt(key.get(), Base64.getDecoder().decode(String.valueOf(enc))),
                    StandardCharsets.UTF_8));
            parsed.putAll(identifying);
        }
        if (key.isEmpty()) {
            // Shredded or restricted, which is not the same as merely lacking
            // authority. A coarse value is a DISCLOSURE control — what somebody
            // without the right to see an identity gets instead — and it has no
            // business surviving an erasure. Leaving a birth year behind after
            // Article 17 would be retaining personal data in a weaker form and
            // calling it gone.
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
                object.deleted(), object.payloadVersion());
    }

    // ------------------------------------------------------------- rights

    /**
     * §14.5 access/portability: the person's record, reassembled, plus every
     * record of the given types that references them (already pseudonymous).
     */
    public String exportPerson(String typeName, String personId,
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
