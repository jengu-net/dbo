package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fatal with one sentence for every cause is one a caller cannot act on.
 *
 * <p>A consumer met twenty-two of these in a single run, against four stores,
 * every one of them saying {@code query failed} and nothing else. There is no
 * way to tell a malformed request from a store fault by that, so the only
 * rational response is to retry the same thing on the next cycle, for ever —
 * and an order placed successfully never progresses while nothing looks
 * broken from either side.
 */
@Tag("integration")
class AFatalNamesWhatKindOfWrongItIsIT {

    @Test
    @DisplayName("a query that fails in the database says which kind of wrong it was, and "
            + "does not carry what the statement was working on")
    @Proving(DboPromises.CORE_PARAMETERIZED_SQL)
    void aFatalCarriesTheStateAndNotTheValues() {
        // A database of this class's own. A store builds its own schema when
        // it is constructed, so the fault has to be MADE rather than found:
        // the table is taken away afterwards, which is what a half-applied
        // migration and a hand-edited database both leave behind — and it is
        // the kind of fault that used to read as "query failed" and nothing.
        String url = SharedPostgres.urlFor("AFatalNamesWhatKindOfWrongItIsIT");
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(SharedPostgres.get().getUsername());
        source.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.internal("ServiceRequest")));
        PgObjectStore store = new PgObjectStore(source, declared.registrations());

        String table = cloud.jengu.dbo.core.api.Domains.tables(
                declared.registrations().get(0).domain()) + "_data";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url,
                     SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + table + " CASCADE");
        } catch (java.sql.SQLException cannot) {
            throw new IllegalStateException("could not take the table away: " + table, cannot);
        }

        IllegalStateException fatal = assertThrows(IllegalStateException.class,
                () -> store.get("ServiceRequest", "01920000-0000-7000-8000-000000000000"));

        // 42P01 is undefined_table. The point is not the number: it is that a
        // five-character code from a closed set separates a deployment's
        // missing schema from a caller's bad request, which one sentence for
        // every cause cannot.
        assertTrue(fatal.getMessage().contains("SQLSTATE"),
                "a fatal has to say which kind of wrong it is, or every one of them reads "
                        + "the same and a caller can only retry: " + fatal.getMessage());
        assertTrue(fatal.getMessage().contains("42P01"),
                "the state has to be the database's own, not a guess: " + fatal.getMessage());

        // And not the database's sentence. It names the values the statement
        // was working on, and in this store those are somebody's identifiers.
        assertFalse(fatal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("select"),
                "the refusal carries the statement, so a search URL's values can reach a "
                        + "caller and a log through it: " + fatal.getMessage());
    }
}
