package io.dbobjects.context;

import io.dbobjects.storage.Storage;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Collection;

@Setter
@Getter
@Accessors(fluent = true)
public class TestNodeConfig {
    private String nodeId;
    private String appCode;
    private int appVer;
    Collection<? extends Storage<?>> storages;
    private Integer dbImplVer;
    private String dbImplType;
}
