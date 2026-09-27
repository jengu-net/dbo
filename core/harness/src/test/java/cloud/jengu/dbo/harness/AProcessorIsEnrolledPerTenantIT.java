package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.FleetRegister;
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
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor is enrolled on every tenant it performs for, and each tenant
 * authorises what it read.
 *
 * <p>Per tenant rather than once for the fleet, and that is the decision rather
 * than a detail: a payload is sealed to an enrolled participant, so enrolling
 * at fleet level would mean something re-seals a tenant's payload and therefore
 * holds tenant keys — the one thing the carrier rule exists to exclude.
 *
 * <p>And all at once. One record per tenant covers every step on that tenant's
 * register, because a tenant answering per step could never be sure it had
 * finished, and a deployment could widen what it opens by adding a row nobody
 * noticed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AProcessorIsEnrolledPerTenantIT {

    private static final String ONE = "enrolone";
    private static final String TWO = "enroltwo";
    private static final String PROCESSOR = "fleet-processor";
    private static final String STEP = "fleet.enrolling.normalise";
    private static final String SECOND_STEP = "fleet.enrolling.review";

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path dir;
    Path managementSpec;
    KeyPair sealing;
    KeyPair signing;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-enrol");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AProcessorIsEnrolledPerTenantIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        // The processor's own pair, generated where the processor is. Only the
        // public halves are handed over, which is what makes handing them over
        // safe: a copy of what a tenant records opens nothing.
        sealing = KeyWrap.newParticipantKeyPair();
        signing = SigningKey.newKeyPair();
        manager.processor(new TenantRuntimeManager.Processor(PROCESSOR,
                ParticipantKey.of(sealing.getPublic()), SigningKey.of(signing.getPublic())));

        managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, descriptor(STEP));
        manager.manages(managementSpec);

        for (String tenant : List.of(ONE, TWO)) {
            Files.writeString(dir.resolve(tenant + ".json"), """
                    {"code":"%s","face":"r4","types":[
                       {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                    .formatted(tenant));
        }
        UntilServed.scan(manager, ONE, TWO);
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
    @DisplayName("each tenant holds the processor's own enrolment, with both public halves and "
            + "nothing that opens or signs")
    @Proving(DboPromises.PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT)
    void enrolledOnEveryTenant() {
        for (String tenant : List.of(ONE, TWO)) {
            var authority = manager.authority(tenant);
            assertEquals(Optional.of(ParticipantKey.of(sealing.getPublic())),
                    authority.participantKey(PROCESSOR),
                    "tenant '" + tenant + "' holds no key to seal this processor's work to, so "
                            + "its payloads would travel in the clear or not at all");
            assertEquals(Optional.of(SigningKey.of(signing.getPublic())),
                    authority.signingKey(PROCESSOR),
                    "tenant '" + tenant + "' cannot check an opening this processor reports, so "
                            + "its trail could not be trusted to say who opened what");
        }
    }

    @Test
    @Order(2)
    @DisplayName("one enrolment covers every step on the register, so a second step needs no "
            + "second act from the tenant")
    @Proving(DboPromises.PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT)
    void oneEnrolmentCoversEveryStep() throws Exception {
        assertEquals(1, manager.fleetRegister(ONE).size());

        Files.writeString(managementSpec, descriptor(STEP, SECOND_STEP));
        manager.manages(managementSpec);

        assertEquals(2, manager.fleetRegister(ONE).size(),
                "the second step is not on the register, so this proves nothing about covering "
                        + "it: " + manager.fleetRegister(ONE));
        assertEquals(Optional.of(SigningKey.of(signing.getPublic())),
                manager.authority(ONE).signingKey(PROCESSOR),
                "a step was added and the tenant's enrolment no longer answers for it, so "
                        + "enrolment has become one act per step after all");
    }

    @Test
    @Order(3)
    @DisplayName("a tenant that authorised the register it read sees no change, and one that "
            + "read a different register sees one")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void authorisingIsOneComparison() throws Exception {
        assertEquals(Optional.empty(), manager.fleetRegisterChanged(ONE),
                "a tenant that never read a register is being told whether it changed, and "
                        + "never having read one is a different answer");

        // EVERY ROW IT READ, in one act. That is what authorising is: all at
        // once from the tenant's side, and individually named so the store can
        // still say which single row is new when the deployment adds one.
        String asItIs = manager.fleetRegister(ONE).stream()
                .map(row -> '"' + row.digest() + '"')
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(dir.resolve(ONE + ".json"), """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "authorised":[%s]}"""
                .formatted(ONE, asItIs));
        manager.scanOnce();

        assertEquals(Optional.of(false), manager.fleetRegisterChanged(ONE),
                "a tenant that authorised exactly what is happening is told it changed: "
                        + manager.fleetRegister(ONE));

        // The deployment widens what it opens. The tenant's copy stops
        // matching, which is the whole mechanism — a change it can see in one
        // comparison rather than by reading rows.
        Files.writeString(managementSpec, descriptor(STEP, SECOND_STEP, "fleet.enrolling.third"));
        manager.manages(managementSpec);

        assertEquals(Optional.of(true), manager.fleetRegisterChanged(ONE),
                "the deployment added a row it opens and the tenant's authorisation still "
                        + "matches, so a deployment can widen what it reads unnoticed");
    }

    @Test
    @Order(4)
    @DisplayName("moving a row's posture changes the register too, so a deployment cannot "
            + "approve its own widening")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void thePostureIsPartOfWhatWasAuthorised() {
        var asDeclared = manager.fleetRegister(ONE);
        String before = FleetRegister.digestOf(asDeclared);

        var moved = asDeclared.stream()
                .map(row -> new FleetRegister.Row(row.step(), row.slot(), row.type(),
                        row.required(),
                        cloud.jengu.dbo.tenant.TenantSpec.FleetStep.Posture.APPLIED))
                .toList();

        assertFalse(before.equals(FleetRegister.digestOf(moved)),
                "a row moved from one posture to another leaves the register's value unchanged, "
                        + "so a deployment could move a row from 'not until approved' to "
                        + "'processed and named' without the tenant's copy ceasing to match — "
                        + "which is a deployment approving its own widening");
        assertTrue(before.equals(FleetRegister.digestOf(asDeclared)),
                "the same rows give two values, so no tenant could ever authorise anything");
    }

    private static String descriptor(String... steps) {
        StringBuilder declared = new StringBuilder();
        for (String step : steps) {
            declared.append(declared.isEmpty() ? "" : ",")
                    .append("{\"code\":\"").append(step)
                    .append("\",\"slots\":{\"record\":\"Reference(Basic)\"},\"opens\":[\"record\"],")
                    .append("\"substrate\":\"enrolling\"}");
        }
        return """
                {"code":"registry","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[%s]}""".formatted(declared);
    }
}
