package io.dbobjects.db;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class NodeConfig {
    private String defaultSchemaName = "public";
}
