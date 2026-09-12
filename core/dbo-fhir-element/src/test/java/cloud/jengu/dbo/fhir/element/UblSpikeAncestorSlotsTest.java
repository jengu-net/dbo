package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE, not a promise: a UBL document type described as a FHIR logical model
 * rides the element face — parsed by its resourceType, validated by the
 * model's cardinalities and FHIRPath invariants, given the store's ancestor
 * slots, and indexed by a SearchParameter. Two things it found are recorded
 * as assertions so they are not re-found: a projection needs {@code id} and
 * {@code meta} declared on the model, and a FHIRPath expression over
 * UpperCamelCase element names has to be rooted, because an uppercase name at
 * the start of an expression is a type test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UblSpikeAncestorSlotsTest {

    private static final String URL = "http://example.org/ubl/StructureDefinition/UBLInvoice";

    private static final String ROOTED_INVARIANT = "$this.LegalMonetaryTotal.PayableAmount >= 0";
    private static final String BARE_INVARIANT = "LegalMonetaryTotal.PayableAmount >= 0";

    /** A logical model with the store's two slots declared on it. */
    private static String modelWithSlots() {
        return model(true, ROOTED_INVARIANT);
    }

    /** The same, as a pure domain model: no id, no meta. */
    private static String modelWithoutSlots() {
        return model(false, ROOTED_INVARIANT);
    }

    private static String model(boolean slots, String invariant) {
        String slotElements = slots ? """
                {"id":"UBLInvoice.id","path":"UBLInvoice.id","min":0,"max":"1","type":[{"code":"id"}]},
                {"id":"UBLInvoice.meta","path":"UBLInvoice.meta","min":0,"max":"1","type":[{"code":"Meta"}]},
                """ : "";
        return """
                {"resourceType":"StructureDefinition",
                 "id":"UBLInvoice",
                 "url":"%s",
                 "name":"UBLInvoice",
                 "status":"draft",
                 "kind":"logical",
                 "abstract":false,
                 "type":"UBLInvoice",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Base",
                 "derivation":"specialization",
                 "differential":{"element":[
                   {"id":"UBLInvoice","path":"UBLInvoice","min":0,"max":"*",
                    "constraint":[{"key":"ubl-1","severity":"error",
                      "human":"payable amount is not negative",
                      "expression":"%s"}]},
                   %s
                   {"id":"UBLInvoice.CustomizationID","path":"UBLInvoice.CustomizationID","min":0,"max":"1","type":[{"code":"string"}]},
                   {"id":"UBLInvoice.ID","path":"UBLInvoice.ID","min":1,"max":"1","type":[{"code":"string"}]},
                   {"id":"UBLInvoice.IssueDate","path":"UBLInvoice.IssueDate","min":1,"max":"1","type":[{"code":"date"}]},
                   {"id":"UBLInvoice.AccountingSupplierParty","path":"UBLInvoice.AccountingSupplierParty","min":1,"max":"1","type":[{"code":"BackboneElement"}]},
                   {"id":"UBLInvoice.AccountingSupplierParty.EndpointID","path":"UBLInvoice.AccountingSupplierParty.EndpointID","min":1,"max":"1","type":[{"code":"string"}]},
                   {"id":"UBLInvoice.LegalMonetaryTotal","path":"UBLInvoice.LegalMonetaryTotal","min":1,"max":"1","type":[{"code":"BackboneElement"}]},
                   {"id":"UBLInvoice.LegalMonetaryTotal.PayableAmount","path":"UBLInvoice.LegalMonetaryTotal.PayableAmount","min":1,"max":"1","type":[{"code":"decimal"}]}
                 ]}}
                """.formatted(URL, invariant, slotElements);
    }

    private static final String INVOICE = """
            {"resourceType":"UBLInvoice",
             "CustomizationID":"urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0",
             "ID":"INV-2026-0001",
             "IssueDate":"2026-09-01",
             "AccountingSupplierParty":{"EndpointID":"0088:7300010000001"},
             "LegalMonetaryTotal":{"PayableAmount":120.50}}""";

    private final ElementVersion version = ElementVersion.of("r5");

    private ElementPayloads payloads(String model) {
        return version.payloadsFor(Terms.NONE, List.of(model));
    }

    private static Element read(ElementPayloads payloads, String json) {
        return payloads.read(null, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a logical-model document is parsed by its resourceType and named")
    void parsedByResourceType() {
        ElementPayloads payloads = payloads(modelWithSlots());
        Element document = read(payloads, INVOICE);
        assertEquals("UBLInvoice", payloads.typeOf(document));
        assertEquals("INV-2026-0001", document.getNamedChildValue("ID"));
    }

    @Test
    @DisplayName("validation answers from the logical model: cardinality and a rooted FHIRPath invariant")
    void validatedFromTheModel() {
        ElementPayloads payloads = payloads(modelWithSlots());
        assertEquals(List.of(), payloads.validate("UBLInvoice", read(payloads, INVOICE)));

        List<String> missing = payloads.validate("UBLInvoice",
                read(payloads, INVOICE.replace("\"IssueDate\":\"2026-09-01\",", "")));
        assertTrue(missing.toString().contains("IssueDate"), missing.toString());

        List<String> negative = payloads.validate("UBLInvoice",
                read(payloads, INVOICE.replace("120.50", "-1")));
        assertTrue(negative.toString().contains("ubl-1"), negative.toString());
    }

    @Test
    @DisplayName("an invariant starting with an UpperCamelCase element name is a type test, and fails on a good document")
    void bareUpperCaseNamesAreTypeTests() {
        ElementPayloads payloads = payloads(model(true, BARE_INVARIANT));
        List<String> problems = payloads.validate("UBLInvoice", read(payloads, INVOICE));
        assertTrue(problems.toString().contains("ubl-1"),
                "a bare UBL name at the start of a FHIRPath expression must be rooted: " + problems);
    }

    @Test
    @DisplayName("ancestor slots: the token-copy rendering writes id and meta whether or not the model declares them")
    void ancestorsRendered() {
        for (String model : List.of(modelWithSlots(), modelWithoutSlots())) {
            ElementPayloads payloads = payloads(model);
            byte[] served = ElementAncestors.rendered(version.context(),
                    INVOICE.getBytes(StandardCharsets.UTF_8), "inv-1", 3L);
            String text = new String(served, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"id\":\"inv-1\""), text);
            assertTrue(text.contains("\"versionId\":\"3\""), text);
            assertEquals(List.of(), payloads.validate("UBLInvoice", payloads.read(null, served)));
        }
    }

    @Test
    @DisplayName("ancestor slots: a projection goes through the model, so the slots must be declared on it")
    void ancestorsProjected() throws java.io.IOException {
        TenantContext with = new TenantContext(version.context(), Terms.NONE, List.of(modelWithSlots()));
        String projected = new String(ElementAncestors.rendered(with,
                INVOICE.getBytes(StandardCharsets.UTF_8), "inv-1", 3L, List.of("ID")), StandardCharsets.UTF_8);
        assertTrue(projected.contains("\"id\":\"inv-1\""), projected);
        assertTrue(projected.contains("\"versionId\":\"3\""), projected);
        assertTrue(projected.contains("\"ID\":\"INV-2026-0001\""), projected);
        assertFalse(projected.contains("IssueDate"), projected);

        TenantContext without = new TenantContext(version.context(), Terms.NONE, List.of(modelWithoutSlots()));
        assertThrows(Error.class, () -> ElementAncestors.rendered(without,
                INVOICE.getBytes(StandardCharsets.UTF_8), "inv-1", 3L, List.of("ID")),
                "the element model refuses to set an id the model does not declare");
    }

    @Test
    @DisplayName("a SearchParameter over the logical model indexes the issue date and the supplier endpoint")
    void indexedBySearchParameter() {
        Element document = read(payloads(modelWithSlots()), INVOICE);
        SearchParameter issued = new SearchParameter();
        issued.setCode("issued");
        issued.setType(Enumerations.SearchParamType.DATE);
        issued.setExpression("UBLInvoice.IssueDate");
        SearchParameter supplier = new SearchParameter();
        supplier.setCode("supplier");
        supplier.setType(Enumerations.SearchParamType.STRING);
        supplier.setExpression("UBLInvoice.AccountingSupplierParty.EndpointID");
        Envelope envelope = ElementEnvelopes.extract(version.context(), List.of(issued, supplier), document, false);
        assertTrue(envelope.paths().toString().contains("2026-09-01"), envelope.paths().toString());
        assertTrue(envelope.paths().toString().contains("0088:7300010000001"), envelope.paths().toString());
    }
}
