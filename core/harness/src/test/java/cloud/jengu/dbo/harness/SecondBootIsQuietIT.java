package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Provisioning a tenant that already exists is quiet.
 *
 * <p>The code always handled it — catch {@code 42P04}, attach — but Postgres
 * logs an ERROR whenever it raises one, whether or not the client catches it.
 * So every boot after the first put "database already exists" into the server
 * log, and on a pre-17 major "unrecognized configuration parameter" beside it,
 * for conditions that mean everything is fine. A developer scanning that log
 * for a genuine provisioning failure has to learn which ERRORs to ignore,
 * which is a skill nobody should need.
 *
 * <p>Asserted on the log itself, because the log is where the harm lands: the
 * Java-side behaviour was already correct and already tested.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecondBootIsQuietIT {

    static PostgreSQLContainer<?> postgres;
    static LocalDatabasePerTenantProvisioner provisioner;

    @BeforeAll
    void up() {
        postgres = SharedPostgres.get();
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("SecondBootIsQuietIT"),
                postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    void down() {
        if (provisioner != null) {
            provisioner.close();
        }
    }

    @Test
    void reprovisioningAnExistingTenantAddsNothingToTheServerLog() throws Exception {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"vaikne","face":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        assertNotNull(provisioner.provision(spec).dataSource());

        // The second boot: the database exists, and asking first is what keeps
        // the server log clean. Only this test's slice of the shared
        // container's log is judged, and only for this tenant's database.
        int mark = postgres.getLogs().length();
        assertNotNull(provisioner.provision(spec).dataSource());
        String sinceMark = postgres.getLogs()
                .substring(Math.min(mark, postgres.getLogs().length()));
        List<String> noise = sinceMark.lines()
                .filter(line -> line.contains("tenant_vaikne")
                        || line.contains("transaction_timeout"))
                .filter(line -> line.contains("ERROR"))
                .toList();
        assertEquals(List.of(), noise,
                "a re-attach the code handles must not raise an error the server logs");
    }
}
