package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.fleet.Credentials;
import cloud.jengu.dbo.fleet.FleetReader;
import cloud.jengu.dbo.fleet.Node;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two nodes of one deployment, in the middle of a rolling upgrade: the one leg
 * of US-DBO-FLEET-HEALTH the sample world cannot walk, because it is one node.
 *
 * <p>Everything else an operator reads and steers from outside the containers
 * is walked in the story, on Rowling Land. What is left is the network map
 * across nodes, and that needs two runtimes each built with a step catalogue
 * of its own — the same step at two versions — which is a deployment no
 * shared world is.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ARollingUpgradeReadsAsOneStepIT {

    /** What the deployment gave its nodes for the questions it asks them. */
    private static final String OPS = "kaarel-holds-this-one";

    private static final String NORTH = "pohja";
    private static final String SOUTH = "louna";
    private static final String ASSAY = "dbo.lab.assay";

    /** Installed in the northern node only, so the map has something to union. */
    private static final StepDeclaration ASSAY_V2 =
            StepDeclaration.of(ASSAY, "2.1", WorkModel.DOMAIN).containing("open", "close", "reopen");
    /** The same step, an older build, on the southern node: a rolling upgrade. */
    private static final StepDeclaration ASSAY_V1 =
            StepDeclaration.of(ASSAY, "1.4", WorkModel.DOMAIN).containing("open", "close", "reopen");

    static PostgreSQLContainer<?> postgres;
    static Node north;
    static Node south;
    static TenantRuntimeManager northRuntime;
    static TenantRuntimeManager southRuntime;
    static LocalDatabasePerTenantProvisioner northProv;
    static LocalDatabasePerTenantProvisioner southProv;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        northProv = provisioner("north");
        southProv = provisioner("south");
        northRuntime = nodeServing(northProv, Steps.of(ASSAY_V2), NORTH);
        southRuntime = nodeServing(southProv, Steps.of(ASSAY_V1), SOUTH);
        north = new Node("pohja-node", base(northRuntime), OPS);
        south = new Node("louna-node", base(southRuntime), OPS);
        northRuntime.authority(NORTH).ensureClient("kaarel", "read-secret", List.of("fleet"));
    }

    private static LocalDatabasePerTenantProvisioner provisioner(String name) {
        return new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ARollingUpgradeReadsAsOneStepIT" + name),
                postgres.getUsername(), postgres.getPassword());
    }

    private static TenantRuntimeManager nodeServing(LocalDatabasePerTenantProvisioner prov,
            Steps installed, String tenant) throws Exception {
        Path dir = Files.createTempDirectory("dbo-fleet-" + tenant);
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        TenantRuntimeManager manager = new TenantRuntimeManager(dir, prov, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null),
                FhirVersions.installed(), installed);
        manager.serveRuntimeState(OPS);
        Files.writeString(dir.resolve(tenant + ".json"), """
                {"code":"%s","face":"r4","types":[
                  {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(tenant));
        UntilServed.scan(manager, tenant);
        return manager;
    }

    @AfterAll
    void down() {
        for (TenantRuntimeManager m : new TenantRuntimeManager[] {northRuntime, southRuntime}) {
            if (m != null) {
                m.close();
            }
        }
        for (LocalDatabasePerTenantProvisioner p
                : new LocalDatabasePerTenantProvisioner[] {northProv, southProv}) {
            if (p != null) {
                p.close();
            }
        }
    }

    @Test
    @DisplayName("the network map is the union of what the nodes carry, so a rolling upgrade "
            + "reads as one step at two versions rather than as two answers")
    @Proving(DboPromises.PROC_NETWORK_MAP)
    void theMapIsOneAnswerAcrossNodes() {
        Map<String, Map<String, List<String>>> map = new FleetReader(List.of(north, south),
                Credentials.of(Map.of(NORTH, new Credentials.Credential("kaarel", "read-secret"))),
                Duration.ofSeconds(5)).read(FleetReader.RunFilter.ANY).map();

        assertEquals(Map.of("2.1", List.of("pohja-node"), "1.4", List.of("louna-node")),
                map.get(ASSAY),
                "one step at two versions on two nodes, each naming where it is: " + map);
    }

    private static URI base(TenantRuntimeManager manager) {
        return URI.create("http://127.0.0.1:" + manager.port());
    }
}
