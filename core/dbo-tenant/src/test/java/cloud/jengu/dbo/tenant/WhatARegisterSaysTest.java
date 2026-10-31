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
    @DisplayName("a row says whose process it is and who carries it out, and saying either "
            + "moves what the tenant authorised — while a row saying neither is authorised "
            + "by the value it always was")
    @Proving(DboPromises.PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE)
    void whoseProcessAndWhoDoesItAreAuthorisedToo() {
        // What a tenant wrote down before rows named their authority. Pinned,
        // because a digest that moved under every tenant on upgrade would read
        // as every row unapproved, and withhold work that was agreed to.
        assertEquals("RRvksb4I5ACcIIr1", CHECKED.digest(),
                "the provider's own row no longer digests as it did");

        FleetRegister.Row delegated = new FleetRegister.Row(CHECKED.step(), CHECKED.slot(),
                CHECKED.type(), CHECKED.required(), CHECKED.posture(),
                FleetRegister.Authority.PROVIDER, null, List.of("support-desk", "archive"));
        assertFalse(CHECKED.digest().equals(delegated.digest()),
                "a partner handling the tenant's data went unnoticed by its authorisation");
        assertEquals(delegated.digest(), new FleetRegister.Row(CHECKED.step(), CHECKED.slot(),
                        CHECKED.type(), CHECKED.required(), CHECKED.posture(),
                        FleetRegister.Authority.PROVIDER, null,
                        List.of("archive", "support-desk")).digest(),
                "the same partners in another order read as a different row");

        FleetRegister.Row zones = new FleetRegister.Row("care.records", null, null, true,
                TenantSpec.FleetStep.Posture.APPLIED, FleetRegister.Authority.ZONE, "rl",
                List.of());
        FleetRegister.Row otherZone = new FleetRegister.Row("care.records", null, null, true,
                TenantSpec.FleetStep.Posture.APPLIED, FleetRegister.Authority.ZONE, "ee",
                List.of());
        assertFalse(zones.digest().equals(otherZone.digest()),
                "one jurisdiction's obligation was authorised as another's");
    }

    @Test
    @DisplayName("a step the provider hands to partners names them on every row it opens, "
            + "and one that opens nothing may not name any")
    @Proving(DboPromises.PROC_A_PROVIDERS_PARTNERS_ARE_NAMED_ON_THE_REGISTER)
    void aProvidersPartnersAreNamedOnTheRows() {
        String provider = """
                {"code":"mom","face":"r5",
                 "types":[{"name":"Organization","identity":"identifier",
                           "systems":["urn:orgs"],"handling":"operational"}],
                 "fleetSteps":[{"code":"fleet.directory.check",
                   "slots":{"org":"Reference(Organization)","note":"Basic"},
                   "opens":["org","note"]%s}]}""";
        List<FleetRegister.Row> itself = FleetRegister.of(
                TenantSpec.parse(provider.formatted("")).fleetSteps(), Set.of());
        List<FleetRegister.Row> handed = FleetRegister.of(TenantSpec.parse(provider.formatted(
                ",\"delegatesTo\":[\"support-desk\"]")).fleetSteps(), Set.of());

        assertEquals(2, handed.size());
        assertTrue(handed.stream().allMatch(row -> row.subProcessors()
                        .equals(List.of("support-desk"))),
                "a row the partner handles does not name it: " + handed);
        assertFalse(itself.get(0).digest().equals(handed.get(0).digest()),
                "a partner added behind a row left what the tenant authorised standing");

        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse("""
                {"code":"mom","face":"r5",
                 "types":[{"name":"Organization","identity":"internal",
                           "handling":"operational"}],
                 "fleetSteps":[{"code":"fleet.directory.route",
                   "slots":{"org":"Reference(Organization)"},
                   "delegatesTo":["support-desk"]}]}""")),
                "a step that opens nothing named a partner no register could show");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse("""
                {"code":"support-desk","face":"r5","partnerOf":"support-desk",
                 "types":[{"name":"Basic","identity":"internal",
                           "handling":"operational"}]}""")),
                "a tenant was taken as its own partner");
    }

    @Test
    @DisplayName("work a tenant hands to the provider is a row of the tenant's own, standing "
            + "only once authorised, and only work that lasts is handed, only to the provider")
    @Proving(DboPromises.PROC_WORK_HANDED_TO_THE_PROVIDER_IS_ON_THE_TENANTS_REGISTER)
    void workHandedToTheProviderIsARowOfTheTenantsOwn() {
        String clinic = """
                {"code":"clinic","face":"r5",
                 "types":[{"name":"CommunicationRequest","identity":"internal",
                           "handling":"operational"}],
                 "steps":[{"code":"care.incidents.work",
                   "slots":{"incident":"Reference(CommunicationRequest)"}%s}]}""";
        List<FleetRegister.Row> kept = FleetRegister.handed(TenantSpec.parse(
                clinic.formatted(",\"lasts\":true")).steps());
        List<FleetRegister.Row> handed = FleetRegister.handed(TenantSpec.parse(
                clinic.formatted(",\"lasts\":true,\"handsTo\":\"provider\"")).steps());

        assertTrue(kept.isEmpty(), "work the tenant keeps reached its register: " + kept);
        assertEquals(List.of(new FleetRegister.Row("care.incidents.work", "incident",
                        "Reference(CommunicationRequest)", false,
                        TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED,
                        FleetRegister.Authority.TENANT, null, List.of())), handed,
                "handing the work over did not put the tenant's own row on its register");
        FleetRegister.Row row = handed.get(0);
        assertFalse(row.digest().equals(new FleetRegister.Row(row.step(), row.slot(),
                        row.type(), row.required(), row.posture()).digest()),
                "a row handing the tenant's work over authorised as the provider's own");
        assertEquals(handed, FleetRegister.unapproved(handed, Set.of()),
                "work handed over stood without the tenant authorising it");
        assertTrue(FleetRegister.unapproved(handed, Set.of(row.digest())).isEmpty(),
                "authorising the row left it unapproved");

        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(
                        clinic.formatted(",\"handsTo\":\"provider\""))),
                "work that does not last was handed to the provider");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(
                        clinic.formatted(",\"lasts\":true,\"handsTo\":\"support-desk\""))),
                "work was handed to somebody other than the provider");
        TenantSpec handing = TenantSpec.parse(
                clinic.formatted(",\"lasts\":true,\"handsTo\":\"provider\""));
        TenantRuntimeManager.workIsHandedToAProvider(handing, "mom");
        assertTrue(assertThrowsIllegal(() ->
                        TenantRuntimeManager.workIsHandedToAProvider(handing, null)),
                "work was handed over on a deployment with no provider to take it");
        assertTrue(assertThrowsIllegal(() ->
                        TenantRuntimeManager.workIsHandedToAProvider(handing, "clinic")),
                "the provider handed its own work to itself");
    }

    @Test
    @DisplayName("each step a holder of handed work may start is a row of the tenant's own, "
            + "authorised as itself, and only a step of the tenant's performed in one sitting "
            + "is named")
    @Proving(DboPromises.PROC_A_TENANT_SAYS_WHAT_A_HOLDER_OF_HANDED_WORK_MAY_START)
    void eachStepAHolderMayStartIsARowOfItsOwn() {
        String clinic = """
                {"code":"clinic","face":"r5",
                 "types":[{"name":"CommunicationRequest","identity":"internal",
                           "handling":"operational"}],
                 "steps":[{"code":"care.incidents.work",
                   "slots":{"incident":"Reference(CommunicationRequest)"}%s},
                  {"code":"care.incidents.diagnose",
                   "slots":{"incident":"Reference(CommunicationRequest)"}%s},
                  {"code":"care.incidents.order",
                   "slots":{"incident":"Reference(CommunicationRequest)"}}]}""";
        String handing = ",\"lasts\":true,\"handsTo\":\"provider\",\"holdersStart\":%s";
        List<FleetRegister.Row> one = FleetRegister.handed(TenantSpec.parse(clinic.formatted(
                handing.formatted("[\"care.incidents.diagnose\"]"), "")).steps());
        List<FleetRegister.Row> two = FleetRegister.handed(TenantSpec.parse(clinic.formatted(
                handing.formatted("[\"care.incidents.diagnose\",\"care.incidents.order\"]"),
                "")).steps());

        FleetRegister.Row diagnose = new FleetRegister.Row("care.incidents.diagnose", null,
                null, false, TenantSpec.FleetStep.Posture.NOT_UNTIL_APPROVED,
                FleetRegister.Authority.TENANT, null, List.of(), "care.incidents.work");
        assertEquals(diagnose, one.get(1),
                "a step a holder may start did not reach the register as the tenant's own");
        assertEquals(2, one.size(), "rows other than the handed work and its part: " + one);
        Set<String> authorised = Set.of(one.get(0).digest(), one.get(1).digest());
        assertEquals(List.of("care.incidents.order"), FleetRegister.unapproved(two, authorised)
                        .stream().map(FleetRegister.Row::step).toList(),
                "naming another part stood inside rows the tenant had already authorised");
        assertFalse(diagnose.digest().equals(new FleetRegister.Row(diagnose.step(), null, null,
                        false, diagnose.posture(), FleetRegister.Authority.TENANT, null,
                        List.of(), "care.incidents.other").digest()),
                "a part authorised under one piece of handed work stood under another");
        assertEquals("care.incidents.work", RegisterHandler.rows(one).get(1).get("partOf"),
                "the register did not say whose part the step is");

        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(clinic.formatted(
                        handing.formatted("[\"care.incidents.nowhere\"]"), ""))),
                "a holder was let start a step the tenant does not declare");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(clinic.formatted(
                        handing.formatted("[\"care.incidents.diagnose\"]"),
                        ",\"lasts\":true"))),
                "a holder was let start work that lasts");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(clinic.formatted(
                        handing.formatted("[\"care.incidents.diagnose\"]"),
                        ",\"lasts\":true,\"handsTo\":\"provider\""))),
                "a holder was let start work handed to the provider itself");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(clinic.formatted(
                        handing.formatted("[\"care.incidents.work\"]"), ""))),
                "a holder was let start the work they hold");
        assertTrue(assertThrowsIllegal(() -> TenantSpec.parse(clinic.formatted(
                        ",\"lasts\":true,\"holdersStart\":[\"care.incidents.diagnose\"]",
                        ""))),
                "work the tenant keeps named what a holder may start");
    }

    private static boolean assertThrowsIllegal(Runnable parsing) {
        try {
            parsing.run();
            return false;
        } catch (IllegalArgumentException refused) {
            return true;
        }
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
