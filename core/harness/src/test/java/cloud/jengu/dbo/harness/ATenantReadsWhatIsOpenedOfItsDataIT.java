package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.FleetRegister;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.RegisterVersusTrail;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tenant reads about its own data, and what it is told when the reading
 * turns out to be wrong.
 *
 * <p>Two halves of one property. The register is the deployment's statement,
 * made in advance and derived from the declaration so it cannot drift from what
 * the deployment actually does. The incident is what happens when the trail
 * says something the register did not: not a refusal, because an enrolled
 * processor holds the key and no cryptography stops a party that can decrypt
 * from decrypting — so detection is the honest guarantee, offered as one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ATenantReadsWhatIsOpenedOfItsDataIT {

    private static final String TENANT = "reads";
    /** Opens a slot, so it is on the register. */
    private static final String PROCESSOR = "fleet.reading.normalise";
    /** Opens nothing — a router, and not on the register at all. */
    private static final String ROUTER = "fleet.reading.route";

    private static final StepDeclaration ROUTING =
            StepDeclaration.of(ROUTER, "1.0", WorkModel.DOMAIN)
                    .taking("record", "https://meristem.example/shape/record");

    /** The performer's own signing half, which an opening it reports is signed with. */
    java.security.KeyPair signingOfThePerformer;

    PostgreSQLContainer<?> postgres;
    LocalDatabasePerTenantProvisioner provisioner;
    TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        Path dir = Files.createTempDirectory("dbo-reads");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("ATenantReadsWhatIsOpenedOfItsDataIT"),
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
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"opens":["record"],
                    "required":true,"substrate":"reading"},
                   {"code":"%s","slots":{"record":"Reference(Basic)"},"substrate":"reading"}]}"""
                .formatted(PROCESSOR, ROUTER));
        manager.manages(managementSpec);

        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","audit":{"level":"writes"},"types":[
                   {"name":"Basic","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);
        manager.fleetLane(TENANT, ROUTER, performer()).orElseThrow().introduce(ROUTING);

        // THE PERFORMER IS ENROLLED, and that is load-bearing rather than
        // setup. The access entry the comparison reads is written on the
        // SEALED path — a payload opened by a participant the tenant holds
        // keys for — so an unenrolled opener discloses nothing the trail
        // records and there is nothing to disagree with. Enrolling an
        // application-level processor per tenant is the part of step seven
        // that is not built; done by hand here, so what the register and the
        // incident do is proven even though what enrols them is not.
        java.security.KeyPair sealing =
                cloud.jengu.dbo.core.api.seal.KeyWrap.newParticipantKeyPair();
        signingOfThePerformer = cloud.jengu.dbo.core.api.seal.SigningKey.newKeyPair();
        manager.authority(TENANT).ensureClient("performing-bean", "performing-secret",
                List.of(cloud.jengu.dbo.auth.Scopes.WORK),
                cloud.jengu.dbo.core.api.seal.ParticipantKey.of(sealing.getPublic()),
                cloud.jengu.dbo.core.api.seal.SigningKey.of(
                        signingOfThePerformer.getPublic()));
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
    @DisplayName("the register says which slot of which step is opened, and a step that only "
            + "routes is not on it")
    @Proving(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA)
    void theRegisterIsWhatIsOpened() {
        List<FleetRegister.Row> register = manager.fleetRegister(TENANT);

        assertEquals(1, register.size(),
                "the register does not hold exactly the opened slots, so a tenant reading it "
                        + "cannot tell what is disclosed from what is merely carried: "
                        + register);
        FleetRegister.Row row = register.get(0);
        assertEquals(PROCESSOR, row.step());
        assertEquals("record", row.slot());
        // The declared form, which is what says whose data it is.
        assertEquals("Reference(Basic)", row.type());
        assertTrue(row.required(),
                "the row does not say the tenant cannot decline it, which is the first thing "
                        + "somebody deciding needs to know");
        assertEquals(TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED, row.posture(),
                "the row does not carry the posture, so what happens to work nobody has "
                        + "authorised yet is not readable where the decision is made");
    }

    @Test
    @Order(2)
    @DisplayName("a step the tenant declined contributes no rows, because declined and not "
            + "performed are one fact from its side")
    @Proving(DboPromises.PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA)
    void aDeclinedStepIsNotOnTheRegister() {
        // Asked of the derivation directly: the tenant above cannot decline
        // this step, because the deployment requires it — which is the point,
        // and is why the declining case is asked here rather than by writing a
        // declaration the store would rightly refuse.
        List<FleetRegister.Row> declined = FleetRegister.of(
                manager.fleetRegister(TENANT).isEmpty() ? List.of() : declaredSteps(),
                java.util.Set.of(PROCESSOR));

        assertTrue(declined.isEmpty(),
                "a declined step still appears on the register, so a tenant reads rows for "
                        + "processing that will never happen: " + declined);
    }

    @Test
    @Order(3)
    @DisplayName("a step that opens a payload the register does not declare is named as an "
            + "incident in the tenant's own account")
    @Proving(DboPromises.PROC_A_DISAGREEMENT_IS_AN_INCIDENT_NOT_A_REFUSAL)
    void anUndeclaredOpeningIsAnIncident() {
        assertTrue(manager.fleetDisagreements(TENANT).isEmpty(),
                "something already disagrees before anything has opened anything: "
                        + manager.fleetDisagreements(TENANT));

        // THE ROUTER OPENS. Its declaration says it opens nothing, so the
        // register has no row for it — and opening the run's input anyway is
        // exactly the case detection exists for. Nothing stops it, which is
        // the premise rather than a gap.
        Run routed = authorRun();
        var lane = manager.fleetLane(TENANT, ROUTER, performer()).orElseThrow();
        Run held = lane.claim(routed, Duration.ofMinutes(5)).orElseThrow();
        // SEALED, not in the clear: an enrolled participant is refused its
        // inputs in the clear even when it asks, which is the store being
        // right — a payload for somebody holding a key travels sealed to that
        // key.
        var work = lane.sealed(held);
        assertEquals(1, work.payload().size(),
                "the router was sent nothing to open, so there is nothing for the trail to "
                        + "disagree about");

        // AND THEN IT REPORTS THE OPENING, which is the only way the store can
        // know: it happened where the store cannot see, so what lands on the
        // document is what the participant says it did, signed with the key it
        // enrolled. That asymmetry is the whole reason a disagreement is an
        // incident rather than a refusal — the store is told, it does not
        // permit.
        String reference = held.inputs().get("record").one();
        String previous = work.manifest().head();
        String link = cloud.jengu.dbo.work.RunChain.accessLink(
                previous, held.key(), reference, "performing-bean");
        String signature = cloud.jengu.dbo.core.api.seal.SigningKey.sign(
                link.getBytes(StandardCharsets.UTF_8), signingOfThePerformer.getPrivate());
        lane.opened(held, reference, new cloud.jengu.dbo.work.RunChain.Link(
                "access", previous, link, "performing-bean", reference, signature));

        List<RegisterVersusTrail.Incident> said = manager.fleetDisagreements(TENANT);
        assertEquals(1, said.size(),
                "the store did not notice a payload opened that the register never declared: "
                        + said);
        RegisterVersusTrail.Incident incident = said.get(0);
        assertEquals(ROUTER, incident.step());
        assertEquals("record", incident.slot());
        assertEquals("performing-bean", incident.by(),
                "the incident does not name who opened it, which is the one thing a tenant "
                        + "has to act on: " + incident.says());
        assertTrue(incident.says().contains("register does not say"),
                "it does not say what was disagreed with: " + incident.says());
    }

    private List<TenantSpec.FleetStep> declaredSteps() {
        return List.of(new TenantSpec.FleetStep(PROCESSOR, Map.of("record", "Reference(Basic)"),
                java.util.Set.of("record"), true,
                TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED, "reading"));
    }

    private static Executor performer() {
        return new Executor("performing-bean", "1", "cloud.jengu.test", Scope.BASELINE);
    }

    private Run authorRun() {
        var engine = manager.runtime(TENANT).orElseThrow().engine();
        String record = engine.put(PutRequest.create("Basic",
                "{\"resourceType\":\"Basic\",\"code\":{\"text\":\"r\"}}"
                        .getBytes(StandardCharsets.UTF_8))).id();
        return new Runs(engine).of(ROUTING, RunKind.PIPELINE, ROUTER + "/" + record,
                Map.of("record", "Basic/" + record));
    }
}
