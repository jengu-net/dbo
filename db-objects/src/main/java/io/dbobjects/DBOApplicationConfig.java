package io.dbobjects;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString()
public class DBOApplicationConfig {
    private String applicationCode;
    private boolean eventHandlerEnabled;
    private String defaultSchemaName = "dbo_schema";
    private String defaultDatabaseName = "dbo_db";

    private String dbUsername;
    @ToString.Exclude
    private String dbPassword;
    private String dbHost;
    private int dbPort;

    private String eventingServerHost;
    private String eventingGlobalErrorTopic;

    private String nodeId;
    private int applicationVersion;
    private long domainObjectProcessingBatchSize = 200;
}
