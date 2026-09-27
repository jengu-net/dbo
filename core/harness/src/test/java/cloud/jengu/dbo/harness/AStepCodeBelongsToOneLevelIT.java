package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step code is the deployment's or a tenant's, and the sweep says so.
 *
 * <p><b>Why this is worth a world of its own.</b> The claim is about what a
 * deployment-wide pass REFUSES, and it needs two declarations that contradict
 * each other — a management descriptor naming a step the deployment performs
 * and a tenant offering the same code. A shared world cannot carry the second
 * without carrying the contradiction for everybody in it.
 *
 * <p><b>And why it has to hold before anything joins work.</b> A code at both
 * levels is not a configuration mistake that surfaces as a bad answer. It is a
 * run the tenant's own lane offers by conditional write AND the deployment's
 * queue hands to a consumer, with both sides correct about a run only one of
 * them should ever have seen — the failure this store already describes for
 * two sites of one tenant, where the deadline passing and the report being in
 * flight are both true. A declaration is a far cheaper place to see it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AStepCodeBelongsToOneLevelIT {

    private static final String CONTESTED = "fleet.registry.normalise";

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path dir;
    String management;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-one-level");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AStepCodeBelongsToOneLevelIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        // The deployment says what it performs, in the one file that may say
        // it — declared by configuration rather than by the watched directory,
        // which is why the sweep that refuses tenants cannot refuse this.
        Path managementSpec = Files.createTempDirectory("dbo-management")
                .resolve("registry.json");
        Files.writeString(managementSpec, """
                {"code":"registry","face":"r4","types":[
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "fleetSteps":[{"code":"%s",
                   "slots":{"record":"Reference(Observation)"},"opens":["record"]}]}"""
                .formatted(CONTESTED));
        management = manager.manages(managementSpec);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @Order(1)
    @DisplayName("a tenant offering a code the deployment performs does not come up, and the "
            + "sweep says which code and which management tenant")
    @Proving(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL)
    void aTenantMayNotOfferAStepTheDeploymentPerforms() throws Exception {
        Files.writeString(dir.resolve("claims-it.json"), """
                {"code":"claims-it","face":"r4","types":[
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"%s","slots":{"record":"Reference(Observation)"}}]}"""
                .formatted(CONTESTED));
        manager.scanOnce();

        assertFalse(manager.codes().contains("claims-it"),
                "it came up, so two schedulers now reach for one run and both are right: "
                        + manager.codes());

        String said = String.valueOf(manager.troubles().get("claims-it"));
        assertTrue(said.contains(CONTESTED),
                "the refusal does not name the code that collides, which is the one thing "
                        + "somebody has to rename: " + said);
        assertTrue(said.contains("registry"),
                "it does not name the management tenant, so the other side of the collision "
                        + "is somewhere the reader has to go looking for: " + said);
        assertTrue(said.contains("steps") && said.contains("fleetSteps"),
                "it does not name both keys, so which of two files to open is a guess: "
                        + said);
    }

    @Test
    @Order(2)
    @DisplayName("a tenant offering a step of its own is untouched by the rule, because the "
            + "refusal is about one code and not about declaring steps")
    @Proving(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL)
    void aTenantsOwnStepIsStillItsOwn() throws Exception {
        Files.writeString(dir.resolve("offers-its-own.json"), """
                {"code":"offers-its-own","face":"r4","types":[
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"clinic.review.read","slots":{"record":"Reference(Observation)"}}]}""");
        UntilServed.scan(manager, "offers-its-own");

        assertTrue(manager.codes().contains("offers-its-own"),
                "a tenant declaring a step nobody else claims did not come up, so the rule is "
                        + "refusing the act of declaring rather than the collision: "
                        + manager.troubles());
    }

    @Test
    @Order(3)
    @DisplayName("renaming the tenant's step lets it come up, so the refusal names something "
            + "somebody can actually act on")
    @Proving(DboPromises.PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL)
    void renamingItIsTheWayOut() throws Exception {
        Files.writeString(dir.resolve("claims-it.json"), """
                {"code":"claims-it","face":"r4","types":[
                   {"name":"Observation","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"clinic.registry.normalise",
                   "slots":{"record":"Reference(Observation)"}}]}""");
        UntilServed.scan(manager, "claims-it");

        assertTrue(manager.codes().contains("claims-it"),
                "the tenant did what the refusal asked and still does not serve, which makes "
                        + "the refusal a dead end: " + manager.troubles());
        assertFalse(manager.troubles().containsKey("claims-it"),
                "it serves and is still on the trouble ledger, so an operator reading that "
                        + "ledger is chasing something that is fixed: " + manager.troubles());
    }
}
