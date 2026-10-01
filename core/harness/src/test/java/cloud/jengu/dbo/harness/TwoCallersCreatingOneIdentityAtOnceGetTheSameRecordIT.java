package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4FhirVersion;
import cloud.jengu.dbo.postgres.PgObjectStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Creating a record if it is absent, from several callers at the same moment.
 *
 * <p>Two replicas ensuring one client, two bring-ups ensuring one record: the
 * callers do not know about each other, and each asks for the record by its
 * identity. Every one of them must come back with the same record, and exactly
 * one of them must have made it. A caller that lost the race to the insert is
 * not in conflict with anybody: the record it asked for exists, which is the
 * answer if-absent gives.
 *
 * <p><b>The race is made, not waited for.</b> Each round releases every caller
 * through one barrier onto a fresh identity, so the read-then-write window is
 * entered by all of them together, round after round.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoCallersCreatingOneIdentityAtOnceGetTheSameRecordIT {

    private static final String EID = "https://ee.ee/eid";
    private static final int CALLERS = 8;
    private static final int ROUNDS = 25;

    PgObjectStore store;

    @BeforeAll
    void up() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("TwoCallersCreatingOneIdentityAtOnceGetTheSameRecordIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        var declared = R4FhirVersion.INSTANCE.forTypes(List.of(
                FhirTypeConfig.identifier("Practitioner", EID)));
        store = new PgObjectStore(ds, declared.registrations());
        // The schema is made by the facade over this store, as a tenant's is.
        declared.store(store, "https://dbo.test/fhir");
    }

    @Test
    @DisplayName("callers creating one identity at the same moment all get the record one of "
            + "them made, and none of them is told it conflicts")
    void everyCallerGetsTheOneRecord() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(CALLERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String value = "kood-" + round;
                byte[] payload = ("{\"resourceType\":\"Practitioner\",\"identifier\":[{"
                        + "\"system\":\"" + EID + "\",\"value\":\"" + value + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                CyclicBarrier together = new CyclicBarrier(CALLERS);
                List<Future<PutResult>> asked = new ArrayList<>();
                for (int i = 0; i < CALLERS; i++) {
                    asked.add(callers.submit(() -> {
                        together.await();
                        return store.putIfAbsent(IdentityRef.identifier(EID, value),
                                PutRequest.create("Practitioner", payload));
                    }));
                }
                Set<String> ids = new HashSet<>();
                int made = 0;
                for (Future<PutResult> answer : asked) {
                    // A caller told it conflicts throws here, and the round
                    // fails naming the exception it was given.
                    PutResult result = answer.get();
                    ids.add(result.id());
                    made += result.created() ? 1 : 0;
                }
                assertEquals(1, ids.size(),
                        "round " + round + ": callers asking for one identity got different "
                                + "records " + ids);
                assertEquals(1, made,
                        "round " + round + ": " + made + " callers say they made the record, "
                                + "and exactly one did");
            }
        } finally {
            callers.shutdownNow();
        }
    }
}
