package io.dbobjects.context;

import io.dbobjects.application.StateUpdater;
import io.dbobjects.db.postgres.PostgresDatabase;
import io.dbobjects.parallel.NodeContext;
import lombok.Setter;

import java.util.Optional;

public class PostgresTestDatabase extends PostgresDatabase {

    @Setter
    private TestNodeConfig testNodeConfig;

    public PostgresTestDatabase(NodeContext nodeContext,
                                StateUpdater stateUpdater) {
        super(nodeContext, stateUpdater);
    }

    @Override
    public String getImplementationType() {
        return Optional.ofNullable(testNodeConfig.dbImplType()).orElse(super.getImplementationType());
    }

    @Override
    public int getImplementationVersion() {
        return Optional.ofNullable(testNodeConfig.dbImplVer()).orElse(super.getImplementationVersion());
    }
}
