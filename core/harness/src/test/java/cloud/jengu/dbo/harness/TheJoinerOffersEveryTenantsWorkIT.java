package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.work.FleetWork;
import cloud.jengu.dbo.stream.StepJoiner;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work authored in a tenant reaches the step the deployment performs, and
 * work of the tenant's own steps does not.
 *
 * <p>This is the first step of the design that moves anything. What it must
 * show is not that an item arrives — that is the easy half — but the three
 * things that decide whether the design is safe to build on: the filter IS
 * the two levels, the joiner claims nothing, and offering the same run twice
 * offers it once.
 *
 * <p>Its own world because a joiner is a deployment-wide observer over every
 * tenant, and the steps it performs are declared in a management descriptor a
 * deployment is configured with.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheJoinerOffersEveryTenantsWorkIT {

    /** What the DEPLOYMENT performs, from the shared catalogue. */
    private static final String FLEET_STEP = SharedTenants.Fleet.JOINED.code();
    /** What the TENANT performs, which is none of the deployment's business. */
    private static final String OWN_STEP = "clinic.review.read";

    private static final StepDeclaration SWEEP =
            StepDeclaration.of(FLEET_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");
    private static final StepDeclaration REVIEW =
            StepDeclaration.of(OWN_STEP, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    static SharedTenants.Tenant tenant;
    static String TENANT;
    Runs runs;
    String fleetRunId;

    @BeforeAll
    void up() {
        // ON THE SHARED RUNTIME, and the point of the change is what is NOT
        // here: no container, no provisioner, no manager, no management spec
        // and no teardown. This class used to build a deployment of its own
        // because the shared one declares no fleet step — which was true, and
        // was true of eight other classes wanting the same one configuration.
        SharedTenants.deploymentPerforms();
        tenant = SharedTenants.of(SharedTenants.Shape.R4_INTERNAL);
        TENANT = tenant.code();
        runs = new Runs(tenant.engine());
    }

    @Test
    @Order(1)
    @DisplayName("a run of a step the deployment performs is offered into that step's queue, "
            + "naming the tenant that authored it")
    @Proving(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK)
    void aRunReachesItsStepsQueue() throws Exception {
        fleetRunId = runFor(SWEEP).id();

        StepJoiner joiner = SharedTenants.manager().stepJoiner().orElseThrow(() ->
                new AssertionError("the deployment declared a step and built no joiner, so "
                        + "nothing reads any tenant's work"));
        assertTrue(joiner.joinOnce(100) >= 1, "the joiner read the tenant's feed and offered "
                + "nothing, so a run of a declared step went nowhere");

        assertEquals(1, queuedFor(FLEET_STEP),
                "the run is not in its step's queue, so a deployment that declared a step "
                        + "performs no work of it");
        assertTrue(queuedIds().contains(FleetWork.idFor(TENANT, fleetRunId)),
                "the item does not name the tenant and the run it is of, which is all a "
                        + "performer gets: " + queuedIds());
    }

    @Test
    @Order(2)
    @DisplayName("a run of the tenant's OWN step reaches no queue, because the filter at the "
            + "join is what the two levels are made of")
    @Proving(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK)
    void aTenantsOwnRunIsLeftWhereItBelongs() throws Exception {
        runFor(REVIEW);
        SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100);

        assertEquals(0, queuedFor(OWN_STEP),
                "a tenant's own run was lifted into a deployment queue, so a step the "
                        + "deployment neither declares nor may read is being performed by it");
        assertEquals(1, queuedFor(FLEET_STEP),
                "and the declared step's queue grew from a run that is not of it");
    }

    @Test
    @Order(3)
    @DisplayName("the same run offered again is offered once, because no transaction spans "
            + "reading a tenant and writing a step")
    @Proving(DboPromises.PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK)
    void offeringTwiceOffersOnce() throws Exception {
        // What a restart looks like from the substrate's side: the cursor is
        // put back and the same runs are read again. Nothing here pretends the
        // ack was not lost — it re-reads on purpose, because that is the case
        // the workflow id exists for.
        new cloud.jengu.dbo.postgres.PgChangeFeed(tenantSource(), WorkModel.DOMAIN)
                .resetConsumer(StepJoiner.CONSUMER, null);

        int offered = SharedTenants.manager().stepJoiner().orElseThrow().joinOnce(100);

        // Asserted FIRST, because a reset that quietly did nothing would make
        // the count below true for the wrong reason: no second item because
        // nothing was read at all. This has to re-read to prove anything.
        assertTrue(offered >= 1,
                "the cursor was put back and the joiner read nothing, so this proves nothing "
                        + "about offering the same run twice");
        assertEquals(1, queuedFor(FLEET_STEP),
                "re-reading a tenant's feed wrote a second item for one run, so a restart "
                        + "duplicates every run in flight");
    }

    private cloud.jengu.dbo.work.Run runFor(StepDeclaration step) {
        String record = tenant.engine()
                .put(PutRequest.create("Basic",
                        "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                                .getBytes(StandardCharsets.UTF_8))).id();
        return runs.of(step, RunKind.PIPELINE, step.id() + "/" + record,
                Map.of("record", "Basic/" + record));
    }

    /** This tenant's own database, for putting the joiner's cursor back. */
    private javax.sql.DataSource tenantSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(tenant.databaseUrl());
        ds.setUser(SharedPostgres.username());
        ds.setPassword(SharedPostgres.password());
        return ds;
    }

    private int queuedFor(String stepCode) throws Exception {
        try (Connection c = substrate().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM dbos.workflow_status WHERE queue_name = ?")) {
            ps.setString(1, FleetWork.queueFor(stepCode));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> queuedIds() throws Exception {
        try (Connection c = substrate().getConnection();
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

    private javax.sql.DataSource substrate() {
        return SharedTenants.manager().stepSubstrates().get(FLEET_STEP);
    }
}
