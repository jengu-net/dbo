package cloud.jengu.dbo.work;

import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The work domain's records in a map, and its feed in a list.
 *
 * <p>What a run's rules are decided on is the record {@link Runs} writes and
 * reads, and that is real here: the payload is the one a tenant would hold and
 * the envelope is the one {@link WorkModel} extracts from it, so a question
 * asked by envelope value is answered from what the record says. What is not
 * here is a database, which none of these rules is about.
 */
final class WorkInMemory {

    private final Map<String, StoredObject> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdentifier = new ConcurrentHashMap<>();
    private final List<FeedItem> written = java.util.Collections.synchronizedList(
            new ArrayList<>());
    private final Map<String, Integer> cursors = new ConcurrentHashMap<>();
    private final Map<String, TypeRegistration> types = new ConcurrentHashMap<>();

    WorkInMemory() {
        WorkModel.registrations().forEach(type -> types.put(type.typeName(), type));
    }

    ObjectStore store() {
        return (ObjectStore) Proxy.newProxyInstance(ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "get" -> Optional.ofNullable(byId.get((String) args[1]));
                            case "getByIdentifier" -> byIdentifier((String) args[0],
                                    (List<?>) args[1]);
                            case "putIfAbsent" -> putIfAbsent((IdentityRef) args[0],
                                    (PutRequest) args[1]);
                            case "put" -> put((PutRequest) args[0]);
                            case "select" -> matching((Criteria) args[0]);
                            case "count" -> (long) matching((Criteria) args[0]).size();
                            case "page" -> new FeedChunk<>(matching((Criteria) args[0]), null,
                                    true);
                            case "toString" -> "work in memory";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    ChangeFeed feed() {
        return (ChangeFeed) Proxy.newProxyInstance(ChangeFeed.class.getClassLoader(),
                new Class<?>[] {ChangeFeed.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "readFor" -> {
                                int from = cursors.getOrDefault((String) args[0], 0);
                                List<FeedItem> items;
                                synchronized (written) {
                                    items = List.copyOf(written.subList(from,
                                            Math.min(written.size(), from + (int) args[1])));
                                }
                                yield new FeedChunk<>(items, Integer.toString(from + items.size()),
                                        true);
                            }
                            case "ack" -> {
                                cursors.merge((String) args[0], Integer.parseInt((String) args[1]),
                                        Math::max);
                                yield null;
                            }
                            case "lag" -> (long) (written.size()
                                    - cursors.getOrDefault((String) args[0], 0));
                            case "cursorOf" -> String.valueOf(cursors.get((String) args[0]));
                            case "toString" -> "work feed in memory";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private List<StoredObject> byIdentifier(String type, List<?> identifiers) {
        List<StoredObject> found = new ArrayList<>();
        for (Object one : identifiers) {
            Identifier identifier = (Identifier) one;
            String id = byIdentifier.get(type + "|" + identifier.system() + "|"
                    + identifier.value());
            if (id != null) {
                found.add(byId.get(id));
            }
        }
        return found;
    }

    private synchronized PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
        Identifier identifier = ((IdentityRef.ByIdentifier) identity).identifier();
        String named = request.typeName() + "|" + identifier.system() + "|"
                + identifier.value();
        String existing = byIdentifier.get(named);
        if (existing != null) {
            return new PutResult(existing, byId.get(existing).versionId(), false);
        }
        String id = cloud.jengu.dbo.core.UuidV7.newId();
        byIdentifier.put(named, id);
        return written(id, request.typeName(), 1, request.payload(), true);
    }

    private synchronized PutResult put(PutRequest request) {
        StoredObject current = byId.get(request.id());
        if (request.expectedVersion() != null && current != null
                && current.versionId() != request.expectedVersion()) {
            throw new VersionConflictException(request.typeName(), request.id(),
                    request.expectedVersion(), current.versionId());
        }
        return written(request.id(), request.typeName(),
                current == null ? 1 : current.versionId() + 1, request.payload(),
                current == null);
    }

    private PutResult written(String id, String type, long version, byte[] payload,
            boolean created) {
        Instant now = Instant.now();
        byId.put(id, new StoredObject(id, type, version, now, payload, false, null, null, null));
        written.add(new FeedItem(written.size() + 1L, id, type, version,
                created ? ChangeKind.CREATED : ChangeKind.UPDATED, now, payload, false, "1"));
        return new PutResult(id, version, created);
    }

    private List<StoredObject> matching(Criteria criteria) {
        List<StoredObject> found = new ArrayList<>();
        for (StoredObject stored : byId.values()) {
            if (!stored.typeName().equals(criteria.typeName())) {
                continue;
            }
            Map<String, List<EnvelopeValue>> paths = envelope(stored).paths();
            boolean matches = criteria.equalsPredicates().stream().allMatch(eq ->
                            paths.getOrDefault(eq.path(), List.of()).contains(eq.value()))
                    && criteria.anyOfPredicates().stream().allMatch(any ->
                            paths.getOrDefault(any.path(), List.of()).stream()
                                    .anyMatch(any.values()::contains))
                    && criteria.notEqualsPredicates().stream().noneMatch(not ->
                            paths.getOrDefault(not.path(), List.of()).contains(not.value()));
            if (matches) {
                found.add(stored);
            }
        }
        return found;
    }

    private Envelope envelope(StoredObject stored) {
        return types.get(stored.typeName()).extractor().extract(stored.typeName(),
                stored.payload());
    }
}
