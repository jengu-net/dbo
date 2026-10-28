package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.UnknownTypeException;
import cloud.jengu.dbo.core.api.feed.FeedChunk;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tenant's records and its configuration's account of them, in maps.
 *
 * <p>A selection stops at its limit and a page continues after an id, as the
 * store's do, and a type says how it is handled, as its registration does;
 * nothing else of a database is in question.
 */
final class ConfigurationInMemory {

    private final Map<String, StoredObject> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdentifier = new ConcurrentHashMap<>();
    private final Map<String, Handling> handled = new ConcurrentHashMap<>();
    private final ObjectStore store = proxy();

    ObjectStore store() {
        return store;
    }

    /** A type of the tenant's, handled as this. */
    void handles(String type, Handling handling) {
        handled.put(type, handling);
    }

    /** A record of a type, called by an identifier; its id. */
    String holds(String type, Identifier identity) {
        return store.putConditional(IdentityRef.identifier(identity.system(), identity.value()),
                PutRequest.create(type, new byte[0])).id();
    }

    /** The record stays, and no lookup by what it is called finds it any more. */
    void unindexes(String id) {
        byIdentifier.values().remove(id);
    }

    long count(String type) {
        return byId.values().stream().filter(stored -> stored.typeName().equals(type)).count();
    }

    boolean has(String id) {
        return byId.containsKey(id);
    }

    private ObjectStore proxy() {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "putConditional" -> put((IdentityRef.ByIdentifier) args[0],
                                    (PutRequest) args[1]);
                            case "get" -> Optional.ofNullable(byId.get((String) args[1]));
                            case "getByIdentifier" -> ((List<?>) args[1]).stream()
                                    .map(one -> (Identifier) one)
                                    .map(one -> byIdentifier.get(args[0] + "|" + one.system()
                                            + "|" + one.value()))
                                    .filter(java.util.Objects::nonNull).map(byId::get).toList();
                            case "delete" -> {
                                StoredObject gone = byId.remove((String) args[1]);
                                byIdentifier.values().remove(gone.id());
                                yield null;
                            }
                            case "select" -> matching((Criteria) args[0]).stream()
                                    .limit(((Criteria) args[0]).limitValue()).toList();
                            case "page" -> page((Criteria) args[0], (String) args[1]);
                            case "registrationOf" -> registrationOf((String) args[0]);
                            case "toString" -> "configuration in memory";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private TypeRegistration registrationOf(String type) {
        Handling handling = handled.get(type);
        if (handling == null) {
            throw new UnknownTypeException(type);
        }
        return new TypeRegistration(type, "fhir", IdentityClass.INTERNAL, Set.of(),
                handling, (name, payload) -> new cloud.jengu.dbo.core.api.Envelope(), List.of());
    }

    private synchronized PutResult put(IdentityRef.ByIdentifier identity, PutRequest request) {
        String named = request.typeName() + "|" + identity.identifier().system() + "|"
                + identity.identifier().value();
        String id = byIdentifier.computeIfAbsent(named,
                ignored -> cloud.jengu.dbo.core.UuidV7.newId());
        StoredObject was = byId.get(id);
        long version = was == null ? 1 : was.versionId() + 1;
        byId.put(id, new StoredObject(id, request.typeName(), version, Instant.now(),
                request.payload(), false, null));
        return new PutResult(id, version, was == null);
    }

    private FeedChunk<StoredObject> page(Criteria criteria, String after) {
        List<StoredObject> rest = matching(criteria).stream()
                .filter(stored -> after == null || stored.id().compareTo(after) > 0)
                .toList();
        List<StoredObject> page = rest.subList(0, Math.min(rest.size(), criteria.limitValue()));
        boolean drained = page.size() == rest.size();
        return new FeedChunk<>(page, drained ? null : page.get(page.size() - 1).id(), drained);
    }

    private List<StoredObject> matching(Criteria criteria) {
        List<StoredObject> found = new ArrayList<>();
        for (StoredObject stored : byId.values()) {
            if (!stored.typeName().equals(criteria.typeName())) {
                continue;
            }
            Map<String, List<EnvelopeValue>> paths = ConfigFiles.registrations().get(0)
                    .extractor().extract(stored.typeName(), stored.payload()).paths();
            if (criteria.equalsPredicates().stream().allMatch(eq ->
                    paths.getOrDefault(eq.path(), List.of()).contains(eq.value()))) {
                found.add(stored);
            }
        }
        found.sort(Comparator.comparing(StoredObject::id));
        return found;
    }
}
