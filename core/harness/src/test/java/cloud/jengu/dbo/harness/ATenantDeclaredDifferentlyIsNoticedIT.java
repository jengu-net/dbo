package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A live tenant's declaration was read once, at mount, and never again:
 * changing it did nothing at all — not applied, not refused, not reported —
 * and the only way to change a tenant was to withdraw it and declare it again,
 * which drops its surfaces and its dependents' streams to alter one field.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantDeclaredDifferentlyIsNoticedIT {

    static final String CLINIC = "redeclared-clinic";

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-redeclared");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantDeclaredDifferentlyIsNoticedIT"),
                postgres.getUsername(), postgres.getPassword());
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    private static String spec(String face, String... types) {
        StringBuilder declared = new StringBuilder();
        for (String type : types) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(type)
                    .append("\",\"identity\":\"internal\",\"handling\":\"operational\"}");
        }
        return "{\"code\":\"" + CLINIC + "\",\"face\":\"" + face + "\",\"types\":["
                + declared + "]}";
    }

    private void declare(String contents) throws Exception {
        Files.writeString(dir.resolve(CLINIC + ".json"), contents);
    }

    @Test
    @Order(1)
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void aTenantServingWhatWasDeclaredIsNobodysQuestion() throws Exception {
        declare(spec("r4", "Observation"));
        UntilServed.scan(manager, CLINIC);

        assertTrue(manager.redeclarations().isEmpty(),
                "nothing was redeclared and the deployment says otherwise: "
                        + manager.redeclarations());
    }

    /** A type it does not have yet: rebuilt in place, never a retraction. */
    @Test
    @Order(2)
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void aTypeItDoesNotHaveYetIsARebuild() throws Exception {
        declare(spec("r4", "Observation", "Condition"));
        manager.scanOnce();

        assertEquals(1, manager.redeclarations().size());
        assertTrue(manager.redeclarations().get(CLINIC).startsWith("rebuilt in place"),
                manager.redeclarations().get(CLINIC));
        assertTrue(manager.redeclarations().get(CLINIC).contains("types"),
                manager.redeclarations().get(CLINIC));
        assertTrue(manager.codes().contains(CLINIC),
                "noticing a change is not a reason to stop serving");
    }

    /**
     * The face decides how everything already stored is read, so it cannot be
     * changed under a serving tenant. Refused by name — which is different
     * from today's answer, silence.
     */
    @Test
    @Order(3)
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void aFaceItCannotChangeUnderneathIsRefusedByName() throws Exception {
        declare(spec("r5", "Observation"));
        manager.scanOnce();

        String said = manager.redeclarations().get(CLINIC);
        assertTrue(said.startsWith("cannot be applied to a serving tenant"), said);
        assertTrue(said.contains("face"), said);
        assertTrue(manager.troubles().getOrDefault(CLINIC, "").contains("face"),
                "a change nothing can apply has to reach an operator: " + manager.troubles());
        assertTrue(manager.codes().contains(CLINIC),
                "and the tenant keeps serving what it was built from");
    }

    /** Declaring it back the way it serves is the change going away. */
    @Test
    @Order(4)
    @Proving(DboPromises.TEN_A_REDECLARATION_IS_NOTICED)
    void declaringItBackClearsIt() throws Exception {
        declare(spec("r4", "Observation"));
        manager.scanOnce();

        assertTrue(manager.redeclarations().isEmpty(), manager.redeclarations().toString());
        assertFalse(manager.troubles().containsKey(CLINIC), manager.troubles().toString());
    }
}
