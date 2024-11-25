package io.dbobjects.jsonstorage;

import io.dbobjects.ObjectMapper;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.Storage;
import io.dbobjects.storage.StorageConfig;
import io.dbobjects.storage.StorageEvent;
import io.dbobjects.storage.StorageObject;
import io.dbobjects.storage.StorageObjectContext;
import io.dbobjects.storage.StorageObjectIdentifier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.dbobjects.storage.PayloadInfo.of;
import static io.dbobjects.util.StringUtil.removeWs;
import static io.dbobjects.util.StringUtil.truncate;

@RequiredArgsConstructor
@Slf4j
public abstract class JsonStorage<T> implements Storage<T> {

    private Logger logger;
    private String key;

    @NonNull
    @Getter
    private final Class<T> typeClass;

    @Getter
    private final int typeVersion;

    public String getType() {
        return typeClass.getName();
    }

    @Getter
    @NonNull
    private final String domainName;

    @Setter(AccessLevel.PROTECTED)
    @Getter
    private Function<byte[], Object> envObjectBuilder;

    private StorageConfig storageConfig;

    @Getter(AccessLevel.PROTECTED)
    private ObjectMapper objectMapper;

    public void setStorageConfig(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected String put(String id, T entity) {
        var event = storageConfig.getDatabase().acceptEvent(domainName, StorageEvent.EventType.U,
            of(id, getType(), typeVersion, toBytes(objectMapper.serialize(entity))));
        return event.getPayloadInfo().getId();
    }

    protected String putByIdentifier(StorageObjectIdentifier identifier, T entity, Consumer<StorageObject> trigger) {
        var maybeStorageObject = getByIdentifier(identifier);
        maybeStorageObject.ifPresentOrElse(trigger, () -> trigger.accept(null));
        return put(maybeStorageObject.map(so -> so.getPayloadInfo().getId()).orElse(null), entity);
    }

    protected String putRaw(String id, String entityStr) {
        var event = storageConfig.getDatabase().acceptEvent(domainName, StorageEvent.EventType.U,
            of(id, getType(), typeVersion, toBytes(entityStr)));
        return event.getPayloadInfo().getId();
    }

    private byte[] toBytes(String str) {
        return Optional.ofNullable(str).map(s -> s.getBytes(StandardCharsets.UTF_8)).orElse(null);
    }

    private String fromBytes(byte[] bytes) {
        return Optional.ofNullable(bytes).map(b -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    protected Optional<StorageObject> get(String id) {
        var nodeId = storageConfig.getNodeState().getNodeId();
        var domain = storageConfig.getDomain(domainName);
        var criteria = createCriteria()
            .withObjectId(id);
        return storageConfig.getDatabase().get(nodeId, domain, criteria);
    }

    /**
     * Retrieves storage object which contains any of given identifiers.
     * Method ignores invalid identifiers. Valid identifiers contain system and value.
     * If all identifiers are invalid then Optional.empty() is returned.
     *
     * @param identifiers list of identifiers.
     * @return storageObject if at least one of given valid identifiers matches to object identifier.
     */
    protected Optional<StorageObject> getByIdentifier(StorageObjectIdentifier... identifiers) {
        var criteria = createCriteria();
        Arrays.stream(identifiers).forEach(criteria::withIdentifier);
        if (criteria.getIdentifierCount() == 0) {
            return Optional.empty();
        } else {
            var nodeId = storageConfig.getNodeState().getNodeId();
            var domain = storageConfig.getDomain(domainName);
            return storageConfig.getDatabase().get(nodeId, domain, criteria);
        }
    }

    protected Optional<T> getByIdentifierAsObject(StorageObjectIdentifier... identifiers) {
        return getByIdentifier(identifiers).map(this::toObject);
    }

    protected Optional<T> getAsObject(String id) {
        return get(id).map(this::toObject);
    }

    protected Collection<StorageObject> doSelect(SearchCriteria criteria) {
        var nodeId = storageConfig.getNodeState().getNodeId();
        var domain = storageConfig.getDomain(domainName);
        criteria = Optional.ofNullable(criteria).orElse(createCriteria());
        return storageConfig.getDatabase().select(nodeId, domain, criteria);
    }

    protected Collection<T> selectAsObjects(SearchCriteria criteria) {
        return doSelect(criteria).stream().map(this::toObject).collect(Collectors.toList());
    }

    public SearchCriteria createCriteria() {
        return storageConfig.getDatabase()
            .createCriteria(storageConfig.getDomain(domainName), getType(), typeVersion);
    }

    public String getStorageKey() {
        if (this.key == null) {
            this.key = Storage.buildStorageKey(getDomainName(), getType());
        }
        return this.key;
    }

    public <M> M getEnvAs(Class<M> clazz, StorageObjectContext context) {
        if (context == null) {
            return null;
        }
        assert clazz != null;
        return getObjectMapper().deserialize(clazz, getObjectMapper().serialize(context.getEnv()));
    }

    protected Map<?, ?> toMap(byte[] o) {
        if (o == null) {
            return null;
        }
        return getObjectMapper().deserialize(Map.class, new String(o, StandardCharsets.UTF_8));
    }

    protected Map<?, ?> toMap(Object o) {
        if (o == null) {
            return null;
        }
        return getObjectMapper().deserialize(Map.class, getObjectMapper().serialize(o));
    }

    protected final Logger getLogger() {
        if (this.logger == null) {
            this.logger = LoggerFactory.getLogger(JsonStorage.class.getName() + "." + getStorageKey());
        }
        return this.logger;
    }

    protected void log(StorageObject o) {
        var pi = o.getPayloadInfo();
        getLogger().info(
            String.format("%20s | %20s | %-30s", pi.getId(), truncate(pi.getType(), 20, false),
                truncate(removeWs(pi.getPayload()), 30, true)));
    }

    protected void log(StorageEvent o) {
        var pi = o.getPayloadInfo();
        getLogger().info(
            String.format("%5s | %3s | %15s | %30s | %20s | %20s | %-30s", o.getEventId(),
                o.getEventType().name(),
                o.getProcessedAt(),
                truncate(removeWs(objectMapper.serialize(o.getContext())), 30, true),
                pi.getId(),
                truncate(pi.getType(), 20, false),
                truncate(removeWs(pi.getPayload()), 30, true)));
    }

    protected T toObject(StorageObject o) {
        return o == null || o.getPayloadInfo() == null || o.getPayloadInfo().getPayload() == null
            ? null : objectMapper.deserialize(typeClass, fromBytes(o.getPayloadInfo().getPayload()));

    }

    protected String remove(String id) {
        var event = storageConfig.getDatabase().acceptEvent(domainName, StorageEvent.EventType.D,
            of(id, getType(), typeVersion, null));
        return id;
    }

    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        if (envObjectBuilder != null) {
            return new StorageObjectContext().withEnv(toMap(envObjectBuilder.apply(payloadInfo.getPayload())));
        }
        return new StorageObjectContext();
    }

}
