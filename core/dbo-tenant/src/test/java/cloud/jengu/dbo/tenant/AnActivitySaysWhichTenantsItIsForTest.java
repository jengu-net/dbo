package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisioning that suits one kind of tenant, applied to another by omission.
 *
 * <p>This is the test that was missing. Subscription dispatching was performed
 * for every tenant against its face's record domain, and a face root keeps its
 * records in the definitions domain — so it polled a relation that does not
 * exist, once a second, for the life of the deployment. Everything passed. The
 * poll loop swallowed the failure, so the only trace anywhere was the
 * database's own error log, and a genuine feed failure on an ordinary tenant
 * would have looked exactly the same.
 *
 * <p>So what is asserted here is not that dispatching works. It is that an
 * activity does not run for a tenant it does not apply to, and that the
 * selector dbo registers its own dispatching with is one a face root fails.
 */
class AnActivitySaysWhichTenantsItIsForTest {

    private static final String HOLDS_RECORDS =
            "(" + TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN + "=true)";

    private static TenantFacts ordinary() {
        return TenantFacts.of(TenantSpec.parse("""
                {"code":"hogwarts","face":"r5","zone":"rl",
                 "types":[{"name":"Patient","identity":"internal","handling":"operational"}]}"""), true);
    }

    private static TenantFacts faceRoot() {
        return TenantFacts.of(TenantSpec.parse("""
                {"code":"fhir-r5","face":"r5","faceRoot":true,
                 "types":[{"name":"StructureDefinition","identity":"canonical","handling":"operational"}]}"""), false);
    }

    private static TenantActivities.Provisioned provisioned(TenantFacts facts) {
        return new TenantActivities.Provisioned(facts, null, null, "r5");
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("an activity does not run for a tenant its filter does not match")
    void theSelectorDecidesAndTheCallSiteDoesNot() {
        TenantActivities activities = new TenantActivities();
        List<String> ran = new ArrayList<>();
        activities.register(TenantPoint.DISPATCH, HOLDS_RECORDS, "dispatch", tenant -> {
            ran.add(tenant.facts().code());
            return null;
        });

        activities.runAt(TenantPoint.DISPATCH, provisioned(ordinary()), (n, e) -> { });
        assertEquals(List.of("hogwarts"), ran, "the tenant that holds records was skipped");

        activities.runAt(TenantPoint.DISPATCH, provisioned(faceRoot()), (n, e) -> { });
        assertEquals(List.of("hogwarts"), ran,
                "the face root had an activity performed on it that is not for it — which is "
                        + "the defect: it polls a record domain a face root does not have");
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("a face root and a projection say they hold no records on their face")
    void theFactThatDecidesItIsPublished() {
        assertTrue((Boolean) ordinary().properties()
                .get(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN));
        assertFalse((Boolean) faceRoot().properties()
                .get(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN),
                "a face root keeps its records in the definitions domain, and saying otherwise "
                        + "is what sends a reader at a relation that does not exist");
        // A projection is not a face root and is the same on this axis: it
        // takes its definitions from one, so it holds none of its own.
        assertFalse((Boolean) TenantFacts.of(TenantSpec.parse("""
                {"code":"rl-on-r4","face":"r4",
                 "dependencies":[{"name":"fhir-r4","face":true,"types":["ValueSet"]}],
                 "types":[{"name":"ValueSet","identity":"canonical","handling":"replicated"}]}"""), false)
                .properties().get(TenantFacts.HOLDS_RECORDS_IN_FACE_DOMAIN));
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("an activity with no filter applies to every tenant, and means it")
    void sayingNothingIsAChoiceRatherThanAnOversight() {
        TenantActivities activities = new TenantActivities();
        List<String> ran = new ArrayList<>();
        activities.register(TenantPoint.DISPATCH, null, "everything", tenant -> {
            ran.add(tenant.facts().code());
            return null;
        });
        activities.runAt(TenantPoint.DISPATCH, provisioned(ordinary()), (n, e) -> { });
        activities.runAt(TenantPoint.DISPATCH, provisioned(faceRoot()), (n, e) -> { });
        assertEquals(List.of("hogwarts", "fhir-r5"), ran);
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("a filter nobody can parse is refused where it is registered")
    void anUnreadableFilterIsNotAFilterThatMatchesNothing() {
        TenantActivities activities = new TenantActivities();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> activities.register(TenantPoint.DISPATCH, "(this is not a filter",
                        "broken", tenant -> null));
        assertTrue(refused.getMessage().contains("broken"),
                "the refusal does not name the registration, and a deployment with several "
                        + "would not know which one to fix");
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("a failing activity is named, and the ones after it still run")
    void afailureIsReportedRatherThanSwallowed() {
        TenantActivities activities = new TenantActivities();
        List<String> ran = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        activities.register(TenantPoint.DISPATCH, null, "throws", tenant -> {
            throw new IllegalStateException("no");
        });
        activities.register(TenantPoint.DISPATCH, null, "after", tenant -> {
            ran.add("after");
            return null;
        });
        activities.runAt(TenantPoint.DISPATCH, provisioned(ordinary()),
                (name, e) -> failures.add(name));
        assertEquals(List.of("throws"), failures,
                "the failure was swallowed, which is how the original went unseen");
        assertEquals(List.of("after"), ran,
                "one activity's failure stopped another, so a tenant's provisioning depends "
                        + "on the order somebody registered things in");
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("an observer of content is not started for a tenant that holds none")
    void anObserverIsNotPointedAtAStreamThatIsNotThere() {
        assertEquals("r5", TenantDomain.CONTENT.on(ordinary(), "r5"));
        assertEquals(null, TenantDomain.CONTENT.on(faceRoot(), "r5"),
                "a face root would have had a reader started against a relation that does "
                        + "not exist, which is the defect wearing a different hat");
        // The streams every tenant carries are fixed and do not depend on this.
        assertEquals("work", TenantDomain.WORK.on(faceRoot(), "r5"));
        assertEquals("audit", TenantDomain.AUDIT.on(faceRoot(), "r5"));
        assertEquals("identity", TenantDomain.IDENTITY.on(faceRoot(), "r5"));
    }

    @Test
    @Proving(DboPromises.TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES)
    @DisplayName("an observer without a durable consumer name is refused")
    void anObserverWithoutAPositionIsACallbackWearingTheName() {
        TenantObservations observations = new TenantObservations();
        assertThrows(IllegalArgumentException.class,
                () -> observations.register(TenantDomain.WORK, null, null, "nameless",
                        (tenant, items) -> { }));
    }
}
