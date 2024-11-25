package io.dbobjects.db.postgres;

import io.dbobjects.ObjectMapper;
import io.dbobjects.storage.SearchCriteria;
import io.dbobjects.storage.StorageObjectIdentifier;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_TYPE;
import static io.dbobjects.db.postgres.DomainDbScripts.DATA_OBJECT_VERSION;

@RequiredArgsConstructor
@Slf4j
public class PostgresQuerySearchCriteria implements SearchCriteria {
    private final String queryPrefix;
    private final String domainName;
    private final String objectType;

    private final int objectVersion;
    private final ObjectMapper objectMapper;

    private Map<String, Collection<String>> idMap;
    private final Collection<StorageObjectIdentifier> identifiers = new ArrayList<>();
    private final Collection<OrderBy> orderBy = new ArrayList<>();
    private Integer offset;
    private Integer limit;
    private Map<String, Object> envFilter;

    private StringBuffer queryAppender;
    @Getter
    private Object[] preparedParameters;

    @Getter
    private String preparedQueryString;
    @Getter
    private boolean withReferences = false;


    @Override
    public SearchCriteria withObjectId(String objectId) {
        return withFieldValueIn("id", objectId);
    }

    protected SearchCriteria withFieldValueIn(String fieldName, String value) {
        if (fieldName != null && !fieldName.isBlank() && value != null && !value.isBlank()) {
            if (this.idMap == null) {
                this.idMap = new HashMap<>();
            }
            var objectIds = this.idMap.get(fieldName);
            if (objectIds == null) {
                objectIds = new ArrayList<>();
                this.idMap.put(fieldName, objectIds);
            }
            objectIds.add(value);
        }
        return this;
    }

    @Override
    public SearchCriteria withIdentifier(StorageObjectIdentifier identifier) {
        if (identifier == null || identifier.getSystem().isBlank() || identifier.getValue().isBlank()) {
            log.info("ignoring invalid identifier: {}", identifier);
        } else {
            identifiers.add(identifier);
        }
        return this;
    }

    @Override
    public SearchCriteria withPaginationInfo(Integer offset, Integer limit) {
        this.offset = offset;
        this.limit = limit;
        return this;
    }

    @Override
    public SearchCriteria orderBy(String envField, Direction direction) {
        if (envField != null && !envField.isEmpty()) {
            direction = (direction == null) ? Direction.asc : direction;
            orderBy.add(new OrderBy().setField(envField).setDirection(direction));
        }
        return this;
    }

    public SearchCriteria withValueIfExists(String dottedKey, String value) {
        if (value != null) {
            this.envFilter = putToMap(envFilter, dottedKey, value);
        }
        return this;
    }

    public SearchCriteria withValueIfExists(String dottedKey, Number value) {
        if (value != null) {
            this.envFilter = putToMap(envFilter, dottedKey, value);
        }
        return this;
    }

    public SearchCriteria withValueIfExists(String dottedKey, Boolean value) {
        if (value != null) {
            this.envFilter = putToMap(envFilter, dottedKey, value);
        }
        return this;
    }

    @Override
    public SearchCriteria withReferences() {
        this.withReferences = true;
        return this;
    }

    public SearchCriteria withGreaterThanIfExists(String dottedKey, boolean inclusive, Number value) {
        if (value != null) {
            this.envFilter = putToMap(envFilter, dottedKey, value);
        }
        return this;
    }

    @Override
    public int getIdentifierCount() {
        return identifiers.size();
    }

    @Override
    public void prepare() {
        //int paramCounter = 1;
        var paramCounterRef = new AtomicInteger(0);
        queryAppender = new StringBuffer(queryPrefix);
        Collection<Object> parameters = new ArrayList<>();

        if (objectType != null && !objectType.isBlank()) {
            queryAppender.append(" AND ").append(DATA_OBJECT_TYPE).append(" LIKE $").append(paramCounterRef.incrementAndGet());
            parameters.add(objectType);
        }

        if (objectVersion >= 0) {
            queryAppender.append(" AND ").append(DATA_OBJECT_VERSION).append(" = $").append(paramCounterRef.incrementAndGet());
            parameters.add(objectVersion);
        }

        if (idMap != null) {
            idMap.forEach((fieldName, objectIds) -> {
                if (objectIds != null && !objectIds.isEmpty()) {
                    if (false && objectIds.size() == 1) {
                        var objectId = objectIds.iterator().next();
                        queryAppender.append(" AND ").append(fieldName).append(" LIKE").append(" $").append(paramCounterRef.incrementAndGet());
                        parameters.add(objectId);
                    } else {
                        queryAppender.append(" AND ").append(fieldName).append(" IN (");
                        var isFirst = new AtomicBoolean(true);
                        objectIds.forEach(id -> {
                            if (isFirst.get()) {
                                isFirst.set(false);
                            } else {
                                queryAppender.append(",");
                            }
                            queryAppender.append("$").append(paramCounterRef.incrementAndGet());
                            parameters.add(id);
                        });
                        queryAppender.append(")");
                    }
                }
            });
        }

        if (envFilter != null) {
            Map<String, Object> filter = new HashMap<String, Object>();
            filter.put("env", envFilter);
            queryAppender.append(" AND context @> ").append(" $").append(paramCounterRef.incrementAndGet());
            parameters.add(new JsonObject(filter));
        }

        if (!identifiers.isEmpty()) {
            // and context @? '$.identifiers[*] ? (1 == 2 || @ == "?" || @ == "?")'
            queryAppender.append(" AND context @? '$.identifiers[*] ? (1 == 2");
            identifiers.forEach(id -> {
                queryAppender.append(" || @ == ").append(objectMapper.serialize(id.asCodeString()));
            });
            queryAppender.append(")'");
        }

        if (!orderBy.isEmpty()) {
            queryAppender.append(" ORDER BY");
            var isFirst = new AtomicBoolean(true);
            orderBy.forEach(o -> {
                if (isFirst.get()) {
                    isFirst.set(false);
                } else {
                    queryAppender.append(" ,");
                }
                queryAppender.append(" context -> ").append(dottedQueryToPostgresText("env." + o.getField())).append(" ").append(o.getDirection().toString());
            });
        }

        if (limit != null && limit > 0) {
            queryAppender.append(" LIMIT $").append(paramCounterRef.incrementAndGet());
            parameters.add(limit);
        }
        if (offset != null) {
            queryAppender.append(" OFFSET $").append(paramCounterRef.incrementAndGet());
            parameters.add(offset);
        }
        this.preparedQueryString = queryAppender.toString();
        this.preparedParameters = parameters.toArray();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> putToMap(Map<String, Object> map, String dottedKey, Object value) {
        Map<String, Object> result = (map != null) ? map : new HashMap<>();
        String[] keys = dottedKey.split("\\.");
        Map<String, Object> current = result;
        for (int i = 0; i < keys.length; i++) {
            boolean currentIsArray = keys[i].endsWith("[*]");
            var key = currentIsArray ? keys[i].substring(0, keys[i].length() - "[*]".length()) : keys[i];
            var lastKey = i == keys.length - 1;
            if (lastKey) {  // last piece of keys
                if (currentIsArray) {
                    if (!(value instanceof Map)) {
                        log.warn("arrays of strings is not supported: {}:{}", dottedKey, value);
                    } else {
                        var array = current.get(key);
                    }
                } else {
                    current.put(key, value);
                }
            } else { // we have subItems
                if (currentIsArray) {
                    Collection<Map<String, Object>> currentArray = (Collection<Map<String, Object>>)
                            ((Map<String, Object>) current).computeIfAbsent(key, k1 -> {
                                var newArray = new ArrayList<>();
                                newArray.add(new HashMap<>());
                                return newArray;
                            });
                    current = currentArray.iterator().next();
                } else {
                    current = (Map<String, Object>) current.computeIfAbsent(key, k1 -> new HashMap<>());
                }
            }
        }
        return result;
    }

    private String dottedQueryToPostgresText(String dottedField) {
        String[] keys = dottedField.split("\\.");
        var sb = new StringBuffer();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(i + 1 < keys.length ? " -> " : " ->> ");
            }
            sb.append("'").append(keys[i]).append("'");
        }
        return sb.toString();
    }
}
