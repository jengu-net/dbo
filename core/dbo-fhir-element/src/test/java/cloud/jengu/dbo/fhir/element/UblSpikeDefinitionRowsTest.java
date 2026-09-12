package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.definitions.DefinitionInvariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE, not a promise: does the definitions-as-rows pipeline hold a UBL
 * logical model? The expansion reads a snapshot as JSON and names no version,
 * so the question is whether a logical model's snapshot is the same kind of
 * JSON a resource's is.
 *
 * <p>It also settles which of two disagreeing engines a UBL invariant should
 * be written for. The toolchain's evaluator reads a leading identifier as a
 * type name, so a bare UBL element name there selects nothing. The compiler
 * reads a leading identifier as a child key, which is what UBL means. They
 * are opposite, and only one of them survives the retirement the design is
 * heading for, so the expression is written for the compiler.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UblSpikeDefinitionRowsTest {

    private static final String URL = "http://example.org/ubl/StructureDefinition/UBLInvoice";
    private static final String BARE = "LegalMonetaryTotal.PayableAmount >= 0";

    private final ElementVersion version = ElementVersion.of("r5");

    /** The wire-format model, with one invariant written in UBL's own names. */
    private static String model() {
        return UblSpikeWireFormatTest.modelForSpikes().replace(
                "{\"id\":\"UBLInvoice\",\"path\":\"UBLInvoice\",\"min\":0,\"max\":\"*\"",
                "{\"id\":\"UBLInvoice\",\"path\":\"UBLInvoice\",\"min\":0,\"max\":\"*\","
                        + "\"constraint\":[{\"key\":\"ubl-1\",\"severity\":\"error\","
                        + "\"human\":\"payable amount is not negative\","
                        + "\"expression\":\"" + BARE + "\"}]");
    }

    private DefinitionElements.Expansion expanded() throws Exception {
        TenantContext context = new TenantContext(version.context(), Terms.NONE, List.of(model()));
        org.hl7.fhir.r5.model.StructureDefinition held = context.fetchResource(
                org.hl7.fhir.r5.model.StructureDefinition.class, URL);
        assertTrue(held != null && held.hasSnapshot(), "the face did not snapshot the model");
        return DefinitionElements.of(new org.hl7.fhir.r5.formats.JsonParser().composeBytes(held));
    }

    @Test
    @DisplayName("a logical model is expanded into rows, and none of them is unenforceable")
    void aLogicalModelExpands() throws Exception {
        DefinitionElements.Expansion expansion = expanded();

        assertEquals("UBLInvoice", expansion.type());
        assertEquals("logical", expansion.kind(),
                "the expansion records what kind of definition it took apart");
        assertEquals(List.of(), expansion.refusals(),
                "an element a checker cannot locate would enforce nothing: " + expansion.refusals());
        assertTrue(expansion.elements().stream().allMatch(
                cloud.jengu.dbo.definitions.DefinitionElement::enforceable),
                "every element of a logical model is locatable by a path");
        assertTrue(expansion.elements().size() > 20,
                "the whole model, not a handful: " + expansion.elements().size());
    }

    @Test
    @DisplayName("an invariant in UBL's own names compiles to the path UBL means")
    void aBareUblNameCompilesToAChildKey() throws Exception {
        DefinitionInvariant rule = expanded().invariants().stream()
                .filter(one -> "ubl-1".equals(one.key()))
                .findFirst().orElseThrow(() -> new AssertionError("the invariant was not compiled"));

        assertTrue(rule.enforceable(), "refused rather than compiled: " + rule);
        assertTrue(rule.path().contains("$.\"LegalMonetaryTotal\""),
                "a leading UBL name must compile to a child key, not to a type test: " + rule.path());
        assertTrue(rule.path().contains("\"PayableAmount\""), rule.path());
    }
}
