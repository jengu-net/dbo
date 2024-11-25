package io.dbobjects;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class DomainConfiguration {
    private String name;
    private int version = 1;
    private String dbType = "postgres12";
    private String schemaName;
    private String dataSourceName;
    private String dbJdbcUrl;
    private String dbJdbcUser;
    private String dbJdbcPassword;
    private boolean enableLocalEventHandler = true;
    private boolean enableMasterNodeEventHandler = true;
    private boolean enableBlockingDomainTaskHandler = true;
    private int masterNodeEventHandlerBatchSize = 100;
    private int masterNodeMaxIdleTime = 4000;
}
