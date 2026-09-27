package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.TenantSpec;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step the deployment performs is declared in one file, and refused in the
 * others.
 *
 * <p>Two levels of step exist because two different parties define them. A
 * tenant declares what it offers; a deployment declares what it does to every
 * tenant that admits it. The second is the one a tenant has to be able to read
 * before joining, so it is one document rather than an audit — and the first
 * thing that has to be true of it is that only one file may carry it.
 *
 * <p>Declaring is not running. Nothing consumes one of these yet, which is
 * deliberate: the invariant that a code belongs to one level has to be in
 * place before anything joins work, or the store acquires two schedulers over
 * one run in between.
 */
class ADeploymentDeclaresItsOwnStepsTest {

    private static final String DECLARED = """
            {"code":"%s","face":"r5","types":[
               {"name":"Basic","identity":"internal","handling":"operational"}],
             "fleetSteps":[
               {"code":"fleet.retention.sweep","slots":{"record":"Reference(Basic)"}},
               {"code":"fleet.coding.normalise","slots":{"record":"Reference(Basic)"},
                "opens":["record"],"required":true,
                "posture":"not-until-approved","substrate":"normalising"}]}""";

    @Test
    @DisplayName("the management descriptor carries what a deployment does for every tenant, "
            + "and says of each slot whether it is opened or only carried")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void theManagementDescriptorCarriesThem() {
        TenantSpec mom = TenantSpec.parse(DECLARED.formatted("mom"));

        assertEquals(2, mom.fleetSteps().size(), "both declarations were not read: "
                + mom.fleetSteps());
        TenantSpec.FleetStep router = mom.fleetSteps().get(0);
        TenantSpec.FleetStep processor = mom.fleetSteps().get(1);

        // A router and a processor, told apart by what they open rather than
        // by a category somebody assigned: requiring one that only reads an
        // envelope is operational, and requiring one that decrypts is not.
        assertTrue(router.opens().isEmpty(), "a step that opens nothing is a router");
        assertTrue(!router.isProcessor() && processor.isProcessor(),
                "a step that opens a slot and one that only carries it read the same, so a "
                        + "register generated from this document could not say which of them "
                        + "discloses anything");

        assertEquals(TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED, router.posture(),
                "an unstated posture is not the default, so a deployment that said nothing "
                        + "would be doing something nobody chose");
        assertEquals(TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED, processor.posture());
        assertEquals("normalising", processor.substrate(),
                "the placement is declared beside the step, so 'this one gets its own "
                        + "database' is said where the step is");
        assertTrue(processor.required() && !router.required());
    }

    @Test
    @DisplayName("an ordinary tenant declaring one is refused, naming the tenant, the key and "
            + "the steps")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void aTenantDeclaringOneIsRefused() {
        TenantSpec hogwarts = TenantSpec.parse(DECLARED.formatted("hogwarts"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.onlyTheDeploymentDeclaresFleetSteps(hogwarts));

        String said = refused.getMessage();
        assertTrue(said.contains("hogwarts") && said.contains("fleetSteps")
                        && said.contains("fleet.coding.normalise"),
                "the refusal does not say whose file, which key or which steps, so somebody "
                        + "has a file to change and no idea which line: " + said);
        assertTrue(said.contains("'steps'"),
                "it refuses without saying where a step this tenant offers DOES go, which is "
                        + "the thing the author was probably reaching for: " + said);
    }

    @Test
    @DisplayName("the management descriptor is the one file this is legal in, so the same "
            + "declaration passes the rule there")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void theSameDeclarationIsLegalInTheManagementDescriptor() {
        // The rule is applied by the sweep that turns declarations into
        // tenants, and the management tenant never travels that road: it is
        // declared by configuration, which is the same reason the sweep that
        // retracts tenants cannot retract it. So what is proven here is that
        // the declaration itself is well-formed — the refusal is about who is
        // asking, not about what was written.
        TenantSpec mom = TenantSpec.parse(DECLARED.formatted("mom"));

        assertEquals(List.of("fleet.retention.sweep", "fleet.coding.normalise"),
                mom.fleetSteps().stream().map(TenantSpec.FleetStep::code).toList());
    }

    @Test
    @DisplayName("a step that opens a slot it does not take is refused, because a register "
            + "row about nothing is worse than no row")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void openingASlotItDoesNotTakeIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"mom","face":"r5","types":[
                           {"name":"Basic","identity":"internal","handling":"operational"}],
                         "fleetSteps":[{"code":"fleet.coding.x","slots":{"record":"Reference(Basic)"},
                           "opens":["somethingElse"]}]}"""));

        assertTrue(refused.getMessage().contains("somethingElse")
                        && refused.getMessage().contains("record"),
                "the refusal names neither the slot it cannot open nor the ones it takes: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("a posture that is not one of the three is refused by name, with the three")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void anUnknownPostureIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TenantSpec.parse("""
                        {"code":"mom","face":"r5","types":[
                           {"name":"Basic","identity":"internal","handling":"operational"}],
                         "fleetSteps":[{"code":"fleet.coding.x","slots":{"record":"Reference(Basic)"},
                           "posture":"whenever"}]}"""));

        assertTrue(refused.getMessage().contains("whenever")
                        && refused.getMessage().contains("not-until-approved"),
                "a posture nobody can spell is the one field where guessing is worst, and "
                        + "this refusal does not say what the choices are: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("slots are not checked against the declaring tenant's own types, because the "
            + "types belong to the tenants whose work it performs")
    @Proving(DboPromises.PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE)
    void slotsAreOverOtherTenantsTypes() {
        // The management tenant holds Organization and nothing else in the
        // sample world. A fleet step over Patient is the ordinary case, not an
        // error: it performs work for tenants that hold one.
        TenantSpec mom = TenantSpec.parse("""
                {"code":"mom","face":"r5","types":[
                   {"name":"Organization","identity":"internal","handling":"operational"}],
                 "fleetSteps":[{"code":"fleet.coding.x","slots":{"subject":"Patient"}}]}""");

        assertTrue(mom.fleetSteps().get(0).slots().get("subject").equals("Patient"),
                "a fleet step was checked against the management tenant's own types, which "
                        + "would refuse every real declaration: the work is other tenants'");
    }
}
