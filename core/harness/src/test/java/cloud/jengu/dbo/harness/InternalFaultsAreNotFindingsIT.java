package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fault in the store is not a finding about the caller's document.
 *
 * <p>Both used to arrive as {@code severity: error, code: exception}, so an
 * operator could only tell them apart by reading a diagnostics string that
 * said {@code Cannot invoke "String.length()" because "s" is null} — the name
 * of a local variable in this codebase. What that produced is worse than
 * unhelpful: an internal fault attributed to whoever published the terminology.
 *
 * <p>{@code fatal} is FHIR's own word for it — "the action failed and no
 * further checking could be performed" — so no vocabulary had to be invented
 * and a caller can sort on the severity alone.
 *
 * <p>One case, not one per version, and that is worth stating because it looks
 * like an omission: every version's {@code ForTypes.store} resolves to the
 * SAME element facade — a personality serves through it rather than beside it,
 * and {@code R4Store} is never the facade a tenant is served by. Three cases
 * here would run one implementation three times and imply coverage that does
 * not exist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalFaultsAreNotFindingsIT {

    static FhirStoreFacade store;

    @BeforeAll
    void up() {
        PostgreSQLContainer<?> postgres = SharedPostgres.get();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(SharedPostgres.urlFor("InternalFaultsAreNotFindingsIT"));
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        FhirVersion.ForTypes declared = FhirVersions.installed().require("r4")
                .forTypes(List.of(FhirTypeConfig.internal("Patient")));
        store = declared.store(new PgObjectStore(pg, declared.registrations()), "");
    }

    @Test
    void aFaultSaysFatalAndAFindingSaysError() {
        String code = "the served facade";

        String fault = store.internalFault("something in here fell over");
        assertTrue(fault.contains("\"severity\":\"fatal\""),
                code + ": a fault in this store must be readable as one without parsing the "
                        + "diagnostics: " + fault);
        assertTrue(fault.contains("\"code\":\"exception\""),
                code + ": and still carry FHIR's code for an internal error: " + fault);

        String finding = store.operationOutcome("invalid", "the resource is missing a subject");
        assertTrue(finding.contains("\"severity\":\"error\""),
                code + ": a finding about the caller's document stays an error: " + finding);
        assertTrue(!finding.contains("\"severity\":\"fatal\""),
                code + ": and must not claim the store fell over: " + finding);
    }
}
