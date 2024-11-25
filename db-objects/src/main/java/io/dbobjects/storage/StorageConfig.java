package io.dbobjects.storage;

import io.dbobjects.Domain;
import io.dbobjects.ObjectMapper;
import io.dbobjects.db.Database;
import io.dbobjects.parallel.NodeState;

public interface StorageConfig {
    Database getDatabase();

    Domain getDomain(String domainName);

    ObjectMapper getObjectMapper();

    NodeState getNodeState();
}
