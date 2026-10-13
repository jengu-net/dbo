package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a step's {@code starts} says, read where it is written.
 */
class AStepSaysWhenItStartsItselfTest {

    private static TenantSpec clinic(String step) {
        return TenantSpec.parse("""
                {"code":"clinic","face":"r5",
                 "types":[{"name":"ServiceRequest","identity":"internal",
                           "handling":"operational"}],
                 "steps":[%s]}""".formatted(step));
    }

    private static String refusal(String step) {
        return assertThrows(IllegalArgumentException.class, () -> clinic(step)).getMessage();
    }

    @Test
    @Proving(DboPromises.PROC_A_STEP_SAYS_WHEN_IT_STARTS_ITSELF)
    @DisplayName("a step starts on a calendar rule over nothing, on a matching change into its "
            + "slot, or at a moment a matching record names")
    void aStepSaysWhenItStartsItself() {
        TenantSpec.Starts clock = clinic("""
                {"code":"clinic.orders.sweep",
                 "starts":{"every":"0 */5 * * * *","missed":"once","keep":"P30D",
                           "where":"origin"}}""").steps().get(0).starts();
        assertEquals("0 */5 * * * *", clock.every());
        assertEquals(TenantSpec.Starts.Missed.ONCE, clock.missed());
        assertEquals(Duration.ofDays(30), clock.keep());
        assertEquals(TenantSpec.Starts.Where.ORIGIN, clock.where());
        assertNull(clock.type());

        TenantSpec.Starts change = clinic("""
                {"code":"clinic.orders.assign","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest?status=active&performer:missing=true",
                           "keep":"P7D","where":"everywhere"}}""").steps().get(0).starts();
        assertEquals("ServiceRequest", change.type());
        assertNull(change.missed());

        TenantSpec.Starts due = clinic("""
                {"code":"clinic.orders.chase","slots":{"order":"ServiceRequest"},
                 "starts":{"due":"ServiceRequest?status=active","at":"authoredOn",
                           "after":"PT24H","keep":"P7D","where":"places"}}""")
                .steps().get(0).starts();
        assertEquals("authoredOn", due.at());
        assertEquals(Duration.ofHours(24), due.after());
        assertEquals(TenantSpec.Starts.Where.PLACES, due.where());

        assertNull(clinic("""
                {"code":"clinic.orders.read","slots":{"order":"ServiceRequest"}}""")
                .steps().get(0).starts());
    }

    @Test
    @Proving(DboPromises.PROC_A_CADENCE_CHANGES_WHERE_THE_TENANT_STANDS)
    @DisplayName("a cadence alone changes where the tenant stands; a first self-starting step, "
            + "or a starts changed with what the step takes, is a rebuild")
    void aCadenceAloneIsTakenWhereTheTenantStands() {
        String hourly = """
                {"code":"clinic.orders.sweep",
                 "starts":{"every":"0 0 * * * *","missed":"skip","keep":"P1D",
                           "where":"origin"}}""";
        TenantSpec serving = clinic(hourly);
        assertEquals(SpecChange.Kind.HOT, SpecChange.between(serving,
                clinic(hourly.replace("0 0 * * * *", "0 30 * * * *"))).kind());

        TenantSpec asked = clinic("""
                {"code":"clinic.orders.read","slots":{"order":"ServiceRequest"}}""");
        assertEquals(SpecChange.Kind.REWIRE, SpecChange.between(asked, clinic("""
                {"code":"clinic.orders.read","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest","keep":"P1D","where":"origin"}}"""))
                .kind(), "a tenant starting its first step was not wired for it");

        assertEquals(SpecChange.Kind.REWIRE, SpecChange.between(serving, clinic("""
                {"code":"clinic.orders.sweep","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest","keep":"P1D","where":"origin"}}"""))
                .kind(), "what the step takes changed with when it starts");
    }

    @Test
    @Proving(DboPromises.PROC_A_STEP_SAYS_WHEN_IT_STARTS_ITSELF)
    @DisplayName("a starts that cannot hold is refused where it was written, naming what is "
            + "missing")
    void aStartsThatCannotHoldIsRefused() {
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"keep":"P1D","where":"origin"}}""").contains("exactly one"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"every":"0 * * * * *","change":"ServiceRequest","missed":"skip",
                           "keep":"P1D","where":"origin"}}""").contains("2 occasions"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"every five minutes","missed":"skip",
                 "keep":"P1D","where":"origin"}}""").contains("six fields"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"*/30 * * * * *","missed":"skip",
                 "keep":"P1D","where":"origin"}}""").contains("once a minute"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"0 * * * * *","keep":"P1D",
                 "where":"origin"}}""").contains("'missed'"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest","missed":"once","keep":"P1D",
                           "where":"origin"}}""").contains("has no ticks"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"Encounter?status=finished","keep":"P1D",
                           "where":"origin"}}""").contains("'Encounter'"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"due":"ServiceRequest","keep":"P1D","where":"origin"}}""")
                .contains("'at'"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"order":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest","at":"authoredOn","keep":"P1D",
                           "where":"origin"}}""").contains("only a 'due' step"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"0 * * * * *","missed":"skip",
                 "where":"origin"}}""").contains("'keep'"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"0 * * * * *","missed":"skip",
                 "keep":"P1D"}}""").contains("'where'"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"every":"0 * * * * *","missed":"skip",
                 "keep":"P1D","where":"somewhere"}}""").contains("origin"));
        assertTrue(refusal("""
                {"code":"a.b.c","starts":{"change":"ServiceRequest","keep":"P1D",
                 "where":"origin"}}""").contains("no slots"));
        assertTrue(refusal("""
                {"code":"a.b.c","slots":{"one":"ServiceRequest","two":"ServiceRequest"},
                 "starts":{"change":"ServiceRequest","keep":"P1D","where":"origin"}}""")
                .contains("2 slots"));
    }
}
