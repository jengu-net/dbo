package io.dbobjects.db.postgres.liquibase;

import liquibase.change.custom.CustomSqlChange;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;
import lombok.extern.slf4j.Slf4j;

import static io.dbobjects.db.postgres.Constants.DBO_TYPE;
import static io.dbobjects.db.postgres.Constants.DBO_VERSION;

@Slf4j
public class DatabaseDboVersionUpdate implements CustomSqlChange {
    @Override
    public SqlStatement[] generateStatements(Database database) {
        log.info("setting DBO type/version to {}/{}", DBO_TYPE, DBO_VERSION);
        return new SqlStatement[]{
                cs("CREATE OR REPLACE FUNCTION dbo_version() RETURNS BIGINT AS",
                        "$$",
                        String.format("SELECT %s;", DBO_VERSION),
                        "$$",
                        "LANGUAGE SQL IMMUTABLE;"),
                cs("CREATE OR REPLACE FUNCTION dbo_type() RETURNS VARCHAR AS",
                        "$$",
                        String.format("SELECT '%s';", DBO_TYPE),
                        "$$",
                        "LANGUAGE SQL IMMUTABLE;"),
        };
    }

    private SqlStatement cs(String... sqlRows) {
        return new RawSqlStatement(String.join("\n ", sqlRows));
    }

    @Override
    public String getConfirmationMessage() {
        return String.format(
                "DBO database version updated to <%s:%s>. New sql functions: dbo_version() and dbo_type().",
                DBO_TYPE, DBO_VERSION);
    }

    @Override
    public void setUp() {

    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {

    }

    @Override
    public ValidationErrors validate(Database database) {
        return null;
    }
}
