package io.dbobjects.storage;

import io.dbobjects.ObjectMapper;

/**
 * Storage defines a way and functionality for storing (and accessing) specific type of objects
 */
public interface Storage<T> {

    String getDomainName();

    void setStorageConfig(StorageConfig storageConfig);

    void setObjectMapper(ObjectMapper objectMapper);

    String getStorageKey();

    StorageObjectContext buildContextFor(PayloadInfo payloadInfo);

    static String buildStorageKey(String domainName, String type) {
        return domainName + "." + type;
    }
}
