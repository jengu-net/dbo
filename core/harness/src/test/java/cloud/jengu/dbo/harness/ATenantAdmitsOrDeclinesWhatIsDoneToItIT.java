package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tenant will and will not have done to its data.
 *
 * <p>The part that makes the rest legitimate. A deployment performing steps
 * for every tenant is only tolerable if a tenant can see what those steps
 * open and say no to the ones it has not agreed to — and if the few it cannot
 * refuse are refusable only by not being a tenant here, said out loud at the
 * declaration rather than discovered.
 *
 * <p>Admitted by saying nothing is the ordinary case: a tenant listing every
 * step it accepts would turn an agreement signed by joining into a per-step
 * click, and a list nobody maintains is a list that silently stops matching
 * what the deployment does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantAdmitsOrDeclinesWhatIsDoneToItIT {

    private static final String ADMITS = "admits";
    private static final String DECLINES = "declines";
    private static final String OPTIONAL_STEP = "fleet.admitting.normalise";
    private static final String REQUIRED_STEP = "fleet.admitting.retain";

    private static final StepDeclaration NORMALISE =
            StepDeclaration.of(OPTIONAL_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;
    Path dir;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-admits");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantAdmitsOrDeclinesWhatIsDoneToItIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));

        Path managementSpec = Files.createTempDirectory("dbo-management").resolve("registry.json");
        Files.writeString(managementSpec, """
                {"code":"registry","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}],
                 "fleetSteps":[
                   {"code":"%s","slots":{"record":"Basic"},"opens":["record"],
                    "substrate":"admitting"},
                   {"code":"%s","slots":{"record":"Basic"},"required":true,
                    "substrate":"admitting"}]}"""
                .formatted(OPTIONAL_STEP, REQUIRED_STEP));
        manager.manages(managementSpec);

        // One tenant says nothing, which admits both. One declines the
        // optional step, which is the whole of what a tenant has to write.
        Files.writeString(dir.resolve(ADMITS + ".json"), tenant(ADMITS, ""));
        Files.writeString(dir.resolve(DECLINES + ".json"),
                tenant(DECLINES, ",\"declines\":[\"" + OPTIONAL_STEP + "\"]"));
        UntilServed.scan(manager, ADMITS, DECLINES);
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
    @DisplayName("a tenant that said nothing has its work offered, and one that declined the "
            + "step has its work left where it is")
    @Proving(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT)
    void decliningMeansNotOffered() throws Exception {
        Run admitted = authorRun(ADMITS);
        Run refusedIt = authorRun(DECLINES);

        manager.stepJoiner().orElseThrow().joinOnce(200);

        assertTrue(queuedIds().contains(FleetWork.idFor(ADMITS, admitted.id())),
                "the tenant that admitted the step by saying nothing had its work left behind, "
                        + "so admitting now takes a declaration after all: " + queuedIds());
        assertFalse(queuedIds().contains(FleetWork.idFor(DECLINES, refusedIt.id())),
                "the tenant that declined the step had its work offered to it anyway, so "
                        + "declining is a note in a file rather than a rule: " + queuedIds());
    }

    @Test
    @Order(2)
    @DisplayName("declining a step the deployment requires is refused by name, saying where "
            + "the requirement is written")
    @Proving(DboPromises.PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT)
    void aRequiredStepCannotBeDeclined() throws Exception {
        Files.writeString(dir.resolve(DECLINES + ".json"),
                tenant(DECLINES, ",\"declines\":[\"" + REQUIRED_STEP + "\"]"));
        manager.scanOnce();

        String said = String.valueOf(manager.troubles().get(DECLINES));
        assertTrue(said.contains(REQUIRED_STEP),
                "the refusal does not name the step that cannot be declined: " + said);
        assertTrue(said.contains("registry"),
                "it does not say where the requirement is written, so a tenant cannot read the "
                        + "set of them before joining: " + said);
        assertTrue(said.contains("joining"),
                "it reads as a setting rather than as an agreement, which is the one thing a "
                        + "required step is not: " + said);
    }

    private static String tenant(String code, String extra) {
        return """
                {"code":"%s","face":"r4","types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]%s}"""
                .formatted(code, extra);
    }

    private Run authorRun(String tenant) {
        var engine = manager.runtime(tenant).orElseThrow().engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return new Runs(engine).of(NORMALISE, RunKind.PIPELINE, OPTIONAL_STEP + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    private java.util.List<String> queuedIds() throws Exception {
        try (Connection c = manager.stepSubstrates().get(OPTIONAL_STEP).getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT workflow_uuid FROM dbos.workflow_status");
                ResultSet rs = ps.executeQuery()) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        }
    }
}
