package io.dbobjects.db.postgres.liquibase;

import io.dbobjects.DboProperties;
import liquibase.change.custom.CustomSqlChange;
import liquibase.database.Database;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;

@Slf4j(topic = "liquibase.changelog.ChangeSet")
public class DomainsUpdate implements CustomSqlChange {

    private DboProperties dboProperties;
    @Setter
    private String templatePath;
    private String domainsUpdateSqlTemplate;

    @Override
    public SqlStatement[] generateStatements(Database database) {
        ArrayList<SqlStatement> statements = new ArrayList<>();
        if (dboProperties.getApplicationDomains() != null) {
            dboProperties.getApplicationDomains().forEach(dn -> statements.add(new RawSqlStatement(fmt(dn))));
        }
        return statements.toArray(new SqlStatement[statements.size()]);
    }

    private String fmt(String domainName) {
        return String.format(domainsUpdateSqlTemplate,
                dboProperties.getApplicationName().toUpperCase(),
                dboProperties.getApplicationVersion(), domainName.toUpperCase());
    }

    private SqlStatement cs(String... sqlRows) {
        return new RawSqlStatement(String.join("\n ", sqlRows));
    }

    @Override
    public String getConfirmationMessage() {
        return String.format("initialized domains <%s> for application <%s:v%s>",
                dboProperties.getApplicationDomains(), dboProperties.getApplicationName(),
                dboProperties.getApplicationVersion());
    }

    @Override
    public void setUp() throws SetupException {

    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        this.dboProperties = DboProperties.instance();
        resourceAccessor.describeLocations().forEach(s -> log.debug(">>> {}", s));
        try {
            var tmplResource = resourceAccessor.get(templatePath);
            if (!tmplResource.exists()) {
                throw new IllegalStateException("did not find template from " + tmplResource.getUri());
            } else {
                log.info("found template resource {}", tmplResource);
            }
            try (var is = tmplResource.openInputStream()) {
                Scanner s = new Scanner(is).useDelimiter("\\A");
                this.domainsUpdateSqlTemplate = s.hasNext() ? s.next() : "";
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ValidationErrors validate(Database database) {
        return null;
    }
}
