package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.TenantState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant's mandatory steps classify incidents; they never gate (#71).
 *
 * <p>This system is asynchronous by design: work buffers on the queue when
 * nothing serves a step, and a participant arriving later drains it. So a
 * mandatory step nothing has contributed must NOT take the tenant offline —
 * that would convert a graceful degradation into a self-inflicted outage.
 * What the spec's {@code mandatorySteps} decides is whether the absence is an
 * <b>incident</b>: named on the operator surface, cleared by the scan after
 * the step arrives, while every undeclared step's absence is no incident at
 * all — free to appear with its participant and disappear with it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MandatoryStepsClassifyIncidentsIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    /**
     * The catalogue as the manager sees it — swappable, the way the OSGi
     * registry's view changes when a module is installed mid-flight.
     */
    static volatile Steps contributed =
            Steps.of(StepDeclaration.of("lab.result.validate", "1.0", "r4"));

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-mandatorysteps");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("MandatoryStepsClassifyIncidentsIT"),
                postgres.getUsername(), postgres.getPassword());
        Steps live = new Steps() {
            @Override
            public Optional<StepDeclaration> byId(String id) {
                return contributed.byId(id);
            }

            @Override
            public Set<String> ids() {
                return contributed.ids();
            }
        };
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null, null,
                FhirVersions.installed(), live);

        // its mandatory step is contributed — no incident
        Files.writeString(dir.resolve("terve.json"), """
                {"code":"terve","fhirVersion":"r4",
                 "mandatorySteps":["lab.result.validate"],"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        // names a step nobody contributed — serves anyway, with an open incident
        Files.writeString(dir.resolve("ootel.json"), """
                {"code":"ootel","fhirVersion":"r4",
                 "mandatorySteps":["lab.result.sign"],"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, "terve", "ootel");
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

    /** Buffering is the design: a missing executor must not become an outage. */
    @Test
    @DisplayName("a tenant serves even when a mandatory step is missing — runs queue, the tenant stays up")
    void aTenantServesEvenWhenAMandatoryStepIsMissing() {
        Map<String, TenantState.State> states = manager.tenantStates().stream()
                .collect(Collectors.toMap(TenantState::code, TenantState::state));
        assertEquals(TenantState.State.SERVING, states.get("ootel"),
                "nothing contributes 'lab.result.sign', and that must buffer work rather "
                        + "than take the tenant offline: " + states);
    }

    /** The list classifies: mandatory-and-missing is an incident, by name. */
    @Test
    @DisplayName("a missing mandatory step is an incident on the operator surface, and only a mandatory one")
    void aMissingMandatoryStepIsAnIncidentByName() {
        Map<String, Set<String>> incidents = manager.stepIncidents();
        assertEquals(Set.of("lab.result.sign"), incidents.get("ootel"),
                "the incident names the step, because 'something is missing' alone is not "
                        + "actionable: " + incidents);
        assertFalse(incidents.containsKey("terve"),
                "a tenant whose mandatory steps are contributed has no open incident — and "
                        + "the absence of every UNDECLARED step is no incident at all: "
                        + incidents);
    }

    /** The incident clears when the step arrives; nothing is told, the scan sees it. */
    @Test
    @DisplayName("the incident clears on the scan after the step is contributed")
    void theIncidentClearsWhenTheStepArrives() {
        // The module arrives — the registry's view changes, nothing is told.
        contributed = Steps.of(
                StepDeclaration.of("lab.result.validate", "1.0", "r4"),
                StepDeclaration.of("lab.result.sign", "1.0", "r4"));
        manager.scanOnce();
        assertFalse(manager.stepIncidents().containsKey("ootel"),
                "the step is contributed, so the incident is over: " + manager.stepIncidents());

        // and it reopens if the contribution goes away again
        contributed = Steps.of(StepDeclaration.of("lab.result.validate", "1.0", "r4"));
        manager.scanOnce();
        assertEquals(Set.of("lab.result.sign"), manager.stepIncidents().get("ootel"),
                "a participant leaving reopens the incident — classification follows what "
                        + "is contributed NOW: " + manager.stepIncidents());
    }

    /** A typo must be refused at parse, not left silently unmatched forever. */
    @Test
    @DisplayName("a malformed mandatory step id is refused when the spec is parsed")
    void aMalformedMandatoryStepIdIsRefusedAtParse() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"vigane","fhirVersion":"r4",
                         "mandatorySteps":["not-a-step-id"],"types":[
                          {"name":"Patient","identity":"internal","handling":"operational"}]}"""));
        assertTrue(refusal.getMessage().contains("not-a-step-id"),
                "the refusal names the entry: " + refusal.getMessage());
    }
}
