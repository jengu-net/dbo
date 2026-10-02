package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the register door says, apart from who may ask it.
 *
 * <p>No database. The register is derived from two declarations and the
 * incidents from the tenant's own records; what is decided here is how the
 * door renders what it was handed, and that the value a tenant authorises
 * moves with every field it would decide on.
 */
class WhatARegisterSaysTest {

    private static final FleetRegister.Row CHECKED = new FleetRegister.Row(
            "fleet.directory.check", "org", "Organization", false,
            TenantSpec.FleetStep.Posture.PROCESSED_AND_NAMED);

    @Test
    @DisplayName("a register never authorised says nothing about whether it changed, and one "
            + "authorised says which")
    @Proving(DboPromises.PROC_THE_REGISTER_IS_READ_AT_A_DOOR)
    void neverAuthorisedIsNotUnchanged() {
        Map<String, Object> never = RegisterHandler.register(reading(Optional.empty()));
        assertFalse(never.containsKey("changedSinceAuthorised"),
                "a tenant that never authorised a register is told whether it changed: " + never);
        assertEquals(FleetRegister.digestOf(List.of(CHECKED)), never.get("digest"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) never.get("rows");
        assertEquals("processed-and-named", rows.get(0).get("posture"),
                "the posture is not written the way a declaration writes it: " + rows);
        assertEquals(CHECKED.digest(), rows.get(0).get("digest"),
                "a row does not carry the value a tenant authorises it by: " + rows);

        assertEquals(Boolean.TRUE,
                RegisterHandler.register(reading(Optional.of(true))).get("changedSinceAuthorised"));
    }

    @Test
    @DisplayName("moving a row's posture changes the register too, so a deployment cannot "
            + "approve its own widening")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void thePostureIsPartOfWhatWasAuthorised() {
        List<FleetRegister.Row> asDeclared = List.of(CHECKED);
        String before = FleetRegister.digestOf(asDeclared);
        List<FleetRegister.Row> moved = List.of(new FleetRegister.Row(CHECKED.step(),
                CHECKED.slot(), CHECKED.type(), CHECKED.required(),
                TenantSpec.FleetStep.Posture.APPLIED));
        assertFalse(before.equals(FleetRegister.digestOf(moved)),
                "a row moved from one posture to another leaves the register's value unchanged, "
                        + "so a deployment could move a row from 'not until approved' to "
                        + "'processed and named' without the tenant's copy ceasing to match — "
                        + "which is a deployment approving its own widening");
        assertTrue(before.equals(FleetRegister.digestOf(asDeclared)),
                "the same rows give two values, so no tenant could ever authorise anything");
    }

    private static RegisterHandler.Reading reading(Optional<Boolean> changed) {
        return new RegisterHandler.Reading() {
            @Override
            public List<FleetRegister.Row> register() {
                return List.of(CHECKED);
            }

            @Override
            public Set<String> declined() {
                return Set.of();
            }

            @Override
            public Optional<Boolean> changed() {
                return changed;
            }

            @Override
            public List<FleetRegister.Row> unapproved() {
                return List.of(CHECKED);
            }

            @Override
            public List<UnapprovedProcessing.Incident> unapprovedProcessing() {
                return List.of();
            }

            @Override
            public List<RegisterVersusTrail.Incident> disagreements() {
                return List.of();
            }
        };
    }
}
