package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.tenant.FleetRegister;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.UnapprovedProcessing;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to work under a row nobody has authorised.
 *
 * <p>Three postures and three different answers, and the difference between
 * them is the one place in this design where refusal is real. Approval is known
 * <b>before</b> anything is sealed, so a row that says <i>not until approved</i>
 * can actually prevent the processing by having the work never offered.
 * Everywhere downstream the processor already holds the key and only detection
 * is possible.
 *
 * <p>The default is that work RUNS and the fact is named, because a halting
 * default would turn an unanswered register into an outage caused by nobody
 * clicking. That cost is accepted rather than argued away, which is why the
 * incident has to be worth acting on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnUnauthorisedRowObeysItsPostureIT {

    private static final String TENANT = "postures";
    private static final String NAMED = "fleet.posture.named";
    private static final String WITHHELD = "fleet.posture.withheld";
    private static final String APPLIED = "fleet.posture.applied";

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path dir;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-postures");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AnUnauthorisedRowObeysItsPostureIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        manager.processor(new TenantRuntimeManager.Processor("fleet-processor",
                ParticipantKey.of(KeyWrap.newParticipantKeyPair().getPublic()),
                SigningKey.of(SigningKey.newKeyPair().getPublic())));

        Path managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, """
                {"code":"registry","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "substrate":"postures"},
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "posture":"not-until-approved","substrate":"postures"},
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "posture":"applied","substrate":"postures"}]}"""
                .formatted(NAMED, WITHHELD, APPLIED));
        manager.manages(managementSpec);

        // AUTHORISING NOTHING, which is the state every one of these is about.
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
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
    @DisplayName("a row that says not until approved has the tenant's work withheld from the "
            + "step entirely, because approval is known before anything is sealed")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void notUntilApprovedWithholdsTheWork() throws Exception {
        Run named = authorRun(NAMED);
        Run withheld = authorRun(WITHHELD);
        Run applied = authorRun(APPLIED);
        manager.stepJoiner().orElseThrow().joinOnce(200);

        List<String> offered = queuedIds();
        assertTrue(offered.contains(FleetWork.idFor(TENANT, named.id())),
                "the default posture withheld the work, so an unanswered register is an outage "
                        + "caused by nobody clicking: " + offered);
        assertTrue(offered.contains(FleetWork.idFor(TENANT, applied.id())),
                "work under a row applied by agreement was withheld: " + offered);
        assertFalse(offered.contains(FleetWork.idFor(TENANT, withheld.id())),
                "work reached a step whose row says not until approved, so the one refusal this "
                        + "design can actually make was not made: " + offered);
    }

    @Test
    @Order(2)
    @DisplayName("the row that ran unauthorised stands as an incident naming what is opened, "
            + "and the row that was withheld does not — nothing happened under it")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void whatRanIsNamedAndWhatDidNotIsNot() {
        List<UnapprovedProcessing.Incident> standing = manager.unapprovedProcessing(TENANT);
        List<String> steps = standing.stream().map(UnapprovedProcessing.Incident::step).toList();

        assertTrue(steps.contains(NAMED),
                "work went through an unauthorised row and nothing says so, which is the one "
                        + "thing the default posture rests on: " + standing);
        assertFalse(steps.contains(WITHHELD),
                "a row whose work was never offered is reported as processing that happened, "
                        + "so a tenant is told about something that did not: " + standing);
        assertFalse(steps.contains(APPLIED),
                "a row applied under the agreement is reported as unauthorised: " + standing);

        UnapprovedProcessing.Incident said = standing.stream()
                .filter(one -> NAMED.equals(one.step())).findFirst().orElseThrow();
        assertEquals("record", said.slot());
        assertEquals("Basic", said.type());
        assertTrue(said.since().isPresent(),
                "the incident cannot say when this started, so it reads the same on day one "
                        + "and day ninety and nobody acts on it: " + said.says());
        assertTrue(said.says().contains(TENANT) && said.says().contains("has not authorised"),
                "the line a tenant reads does not say whose data or what is missing: "
                        + said.says());
    }

    @Test
    @Order(3)
    @DisplayName("authorising the rows clears the incident and releases the withheld step, "
            + "per row rather than per register")
    @Proving(DboPromises.PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE)
    void authorisingClearsItPerRow() throws Exception {
        // ONLY THE WITHHELD ROW, so this shows the grain: authorising one row
        // releases that step and leaves the other incident standing.
        String onlyOne = manager.fleetRegister(TENANT).stream()
                .filter(row -> WITHHELD.equals(row.step()))
                .map(FleetRegister.Row::digest).findFirst().orElseThrow();
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "authorised":["%s"]}""".formatted(TENANT, onlyOne));
        manager.scanOnce();

        Run nowAllowed = authorRun(WITHHELD);
        manager.stepJoiner().orElseThrow().joinOnce(200);

        assertTrue(queuedIds().contains(FleetWork.idFor(TENANT, nowAllowed.id())),
                "the row was authorised and its work is still withheld, so authorising is a "
                        + "note in a file rather than a release: " + queuedIds());
        assertTrue(manager.unapprovedProcessing(TENANT).stream()
                        .anyMatch(one -> NAMED.equals(one.step())),
                "authorising one row cleared an incident about a different one, so the grain "
                        + "is the register after all");
    }

    private Run authorRun(String step) {
        var engine = manager.runtime(TENANT).orElseThrow().engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        StepDeclaration declared = StepDeclaration.of(step, "1.0", WorkModel.DOMAIN)
                .taking("record", "https://meristem.example/shape/record");
        return new Runs(engine).of(declared, RunKind.PIPELINE, step + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private List<String> queuedIds() throws Exception {
        try (Connection c = manager.stepSubstrates().get(NAMED).getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT workflow_uuid FROM dbos.workflow_status");
                ResultSet rs = ps.executeQuery()) {
            List<String> ids = new java.util.ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        }
    }
}
