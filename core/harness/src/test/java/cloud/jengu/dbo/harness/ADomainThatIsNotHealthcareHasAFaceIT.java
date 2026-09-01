package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.GrainCodec;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.tenant.FaceRequirements;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.testmodel.GadgetFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine holds no knowledge of healthcare, and a face for something else
 * is what turns that from a claim into something a build can fail on.
 *
 * <p>The engine half was already proven: one store serves gadget registrations
 * and FHIR ones through the same primitives. What could not be shown was the
 * <b>contract</b> — every statement about what a face owes was made by reading
 * the FHIR one and finding nothing domain-specific in it, which is evidence of
 * absence in one direction only.
 *
 * <p>So a second face exists, for gadgets. What it costs is the interesting
 * output: three capabilities and eight methods, none of which names a
 * standard. A face needing forty would have meant the cost of a second face
 * was exactly what the architecture claimed to keep low.
 *
 * <p>And what it does <b>not</b> provide is the other half. Gadgets have no
 * clinical vocabulary and nothing about one is personal, so there is no grain
 * codec and no coarsening — absent rather than stubbed, because a stub is a
 * lie the engine cannot tell apart from a broken implementation. A tenant
 * asking this face for personal-data isolation is refused at bring-up, by
 * name, which is what those absences exist to produce.
 */
class ADomainThatIsNotHealthcareHasAFaceIT {

    private static final DomainFace GADGETS = GadgetFace.face();

    @Test
    @DisplayName("a face for a domain that is not healthcare declares what it provides, "
            + "and names no standard doing it")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void aSecondFaceExistsAndIsSmall() {
        assertEquals(Set.of(Payloads.class, PayloadFraming.class, RecordProjection.class),
                GADGETS.capabilities(),
                "a second face should cost what the contract says it costs; if this set "
                        + "grows, the cost of a face is growing with it");
        assertEquals("gadgets", GADGETS.name());
    }

    @Test
    @DisplayName("what it does not provide is absent, and asking is an ordinary answer "
            + "rather than a failure")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void absenceIsDeclaredRatherThanStubbed() {
        assertTrue(GADGETS.capability(Coarsening.class).isEmpty(),
                "nothing about a gadget is personal, so a coarsening here would be a stub "
                        + "the engine cannot tell apart from a broken implementation");
        assertTrue(GADGETS.capability(GrainCodec.class).isEmpty(),
                "gadgets have no vocabulary to reassemble");

        DomainFace.CapabilityMissingException refused = assertThrows(
                DomainFace.CapabilityMissingException.class,
                () -> GADGETS.require(Coarsening.class));
        assertTrue(refused.getMessage().contains("gadgets")
                        && refused.getMessage().contains("Coarsening"),
                "a refusal has to name the face and what it was missing, or a caller "
                        + "cannot tell unsupported from misconfigured: " + refused.getMessage());
    }

    @Test
    @DisplayName("a tenant this face can serve comes up; one asking for what it lacks is "
            + "refused at bring-up rather than mid-request")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void theRefusalIsAgainstThisTenantsRequirements() {
        TenantSpec ordinary = new TenantSpec("tehas", GadgetFace.NAME,
                List.of(new FhirTypeConfig("Gadget", IdentityClass.IDENTIFIER,
                        Set.of(cloud.jengu.dbo.testmodel.GadgetModel.SERIAL_SYSTEM),
                        Handling.operational())),
                false, null, null, null, List.of(), List.of());
        FaceRequirements.refuseUnservable(ordinary, GADGETS);

        TenantSpec wantingPdi = new TenantSpec("tehas-pdi", GadgetFace.NAME,
                ordinary.types(), true, null, null, null, List.of(), List.of());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> FaceRequirements.refuseUnservable(wantingPdi, GADGETS));

        assertTrue(refused.getMessage().contains("Coarsening")
                        && refused.getMessage().contains("pdi"),
                "the refusal must name the capability and the part of the spec that asks "
                        + "for it, or a spec author fixes it one refusal at a time: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("the capabilities it declares are real: a gadget parses, validates, "
            + "frames and projects")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    @SuppressWarnings("unchecked")
    void theDeclaredCapabilitiesActuallyWork() {
        Payloads<Object> payloads = (Payloads<Object>) GADGETS.require(Payloads.class);
        byte[] gadget = ("{\"kind\":\"Gadget\",\"serial\":\"S-1\",\"vendor\":\"acme\","
                + "\"name\":\"widget\",\"weightGrams\":\"420\"}").getBytes(StandardCharsets.UTF_8);

        Object parsed = payloads.read("Gadget", gadget);
        assertEquals("Gadget", payloads.typeOf(parsed));
        assertEquals(List.of(), payloads.validate("Gadget", parsed));

        byte[] missingVendor = ("{\"kind\":\"Gadget\",\"serial\":\"S-2\",\"name\":\"widget\","
                + "\"weightGrams\":\"1\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(payloads.validate("Gadget", payloads.read("Gadget", missingVendor))
                        .stream().anyMatch(problem -> problem.contains("vendor")),
                "validation that accepts anything is not validation");

        PayloadFraming.Frame frame = GADGETS.require(PayloadFraming.class)
                .frame("search", new PayloadFraming.Facts(2L, "/gadgets?vendor=acme", null));
        String prologue = new String(frame.prologue(), StandardCharsets.UTF_8);
        assertTrue(prologue.contains("\"kind\":\"search\"") && prologue.contains("\"total\":2"),
                "the document is the domain's own shape, not a Bundle: " + prologue);

        String projected = GADGETS.require(RecordProjection.class)
                .project(new RecordProjection.Record("Run", "run-1", 1L, new byte[0],
                        List.of("gadgets")))
                .orElseThrow();
        assertTrue(projected.contains("\"kind\":\"job\""),
                "a run is a job here, not a Task — which is the whole reason this "
                        + "obligation belongs to a face: " + projected);
    }
}
