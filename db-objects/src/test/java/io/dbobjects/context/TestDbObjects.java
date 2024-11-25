package io.dbobjects.context;

import io.dbobjects.DBOApplicationConfig;
import io.dbobjects.DBOApplicationContext;
import io.dbobjects.DbObjects;
import io.dbobjects.parallel.Node;
import io.dbobjects.storage.Storage;

import java.util.Collection;

public class TestDbObjects extends DbObjects {
    public TestDbObjects(TestNodeConfig cfg, DBOApplicationContext appCtx,
                         DBOApplicationConfig appConfig,
                         Collection<? extends Storage<?>> storages) {
        super(wrap(appCtx, cfg), appConfig, storages);
    }

    public static DBOApplicationContext wrap(DBOApplicationContext ctx, TestNodeConfig testCfg) {
        final var oldDbBuilder = ctx.getDatabaseBuilder();
        ctx.setDatabaseBuilder(
                (nc, su) -> ((PostgresTestDatabase) oldDbBuilder.apply(nc, su)).setTestNodeConfig(testCfg));
        return ctx;
    }

    protected void initTestDatabase(TestNodeConfig cfg) {
        ((PostgresTestDatabase) super.getDatabase()).setTestNodeConfig(cfg);
    }

    public Node getNode() {
        return super.getNode();
    }

}
