package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.formats.IParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE, not a promise: UBL's wire form is namespaced XML whose values are
 * element text with qualifying attributes beside them, and the face reads and
 * writes JSON. The element model bridges both directions, and what it costs is
 * asserted here so it is not re-discovered.
 *
 * <p>The model shape is CDA's: a UBL basic component becomes a backbone with a
 * {@code value} child carrying the {@code xmlText} representation and its
 * qualifier carrying {@code xmlAttr}. Namespaces are per-element extensions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UblSpikeWireFormatTest {

    private static final String INVOICE_NS = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";

    private static final String XML_NS_EXT = "http://hl7.org/fhir/tools/StructureDefinition/xml-namespace";
    private static final String XML_NAME_EXT = "http://hl7.org/fhir/tools/StructureDefinition/xml-name";

    private static final String UBL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
                     xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2">
              <cbc:CustomizationID>urn:cen.eu:en16931:2017</cbc:CustomizationID>
              <cbc:ID>INV-2026-0001</cbc:ID>
              <cbc:IssueDate>2026-09-01</cbc:IssueDate>
              <cac:AccountingSupplierParty>
                <cbc:EndpointID schemeID="0088">7300010000001</cbc:EndpointID>
              </cac:AccountingSupplierParty>
              <cac:LegalMonetaryTotal>
                <cbc:PayableAmount currencyID="EUR">120.50</cbc:PayableAmount>
              </cac:LegalMonetaryTotal>
            </Invoice>""";

    private static String uri(String url, String value) {
        return "{\"url\":\"" + url + "\",\"valueUri\":\"" + value + "\"}";
    }

    private static String str(String url, String value) {
        return "{\"url\":\"" + url + "\",\"valueString\":\"" + value + "\"}";
    }

    private static String element(String id, String type, String ns, String representation) {
        List<String> parts = new ArrayList<>();
        parts.add("\"id\":\"" + id + "\"");
        parts.add("\"path\":\"" + id + "\"");
        parts.add("\"min\":0");
        parts.add("\"max\":\"1\"");
        if (ns != null) {
            parts.add("\"extension\":[" + uri(XML_NS_EXT, ns) + "]");
        }
        if (representation != null) {
            parts.add("\"representation\":[\"" + representation + "\"]");
        }
        parts.add("\"type\":[{\"code\":\"" + type + "\"}]");
        return "{" + String.join(",", parts) + "}";
    }

    /** A UBL basic component: text content, with its qualifying attribute beside it. */
    private static List<String> component(String id, String ns, String valueType, String attribute) {
        List<String> out = new ArrayList<>();
        out.add(element(id, "BackboneElement", ns, null));
        out.add(element(id + ".value", valueType, null, "xmlText"));
        if (attribute != null) {
            out.add(element(id + "." + attribute, "string", null, "xmlAttr"));
        }
        return out;
    }

    /** The same model, for the definition-rows spike. */
    static String modelForSpikes() {
        return model();
    }

    private static String model() {
        List<String> elements = new ArrayList<>();
        // The root carries the XML name, so composing writes <Invoice> while the
        // type stays UBLInvoice — R5 has an Invoice resource of its own.
        elements.add("{\"id\":\"UBLInvoice\",\"path\":\"UBLInvoice\",\"min\":0,\"max\":\"*\","
                + "\"extension\":[" + str(XML_NAME_EXT, "Invoice") + "]}");
        elements.add(element("UBLInvoice.id", "id", null, null));
        elements.add(element("UBLInvoice.meta", "Meta", null, null));
        elements.addAll(component("UBLInvoice.CustomizationID", CBC, "string", null));
        elements.addAll(component("UBLInvoice.ID", CBC, "string", null));
        elements.addAll(component("UBLInvoice.IssueDate", CBC, "date", null));
        elements.add(element("UBLInvoice.AccountingSupplierParty", "BackboneElement", CAC, null));
        elements.addAll(component("UBLInvoice.AccountingSupplierParty.EndpointID", CBC, "string", "schemeID"));
        elements.add(element("UBLInvoice.LegalMonetaryTotal", "BackboneElement", CAC, null));
        elements.addAll(component("UBLInvoice.LegalMonetaryTotal.PayableAmount", CBC, "decimal", "currencyID"));
        return """
                {"resourceType":"StructureDefinition",
                 "id":"UBLInvoice",
                 "url":"http://example.org/ubl/StructureDefinition/UBLInvoice",
                 "name":"Invoice",
                 "status":"draft",
                 "kind":"logical",
                 "abstract":false,
                 "type":"UBLInvoice",
                 "extension":[%s],
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Base",
                 "derivation":"specialization",
                 "differential":{"element":[%s]}}
                """.formatted(uri(XML_NS_EXT, INVOICE_NS), String.join(",", elements));
    }

    private final ElementVersion version = ElementVersion.of("r5");

    private TenantContext context() throws java.io.IOException {
        return new TenantContext(version.context(), Terms.NONE, List.of(model()));
    }

    private static String composed(TenantContext context, Element document, Manager.FhirFormat format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Manager.compose(context, document, out, format, IParser.OutputStyle.PRETTY, null);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "COMPOSE FAILED: " + e;
        }
    }

    private static Element parsed(TenantContext context, String text, Manager.FhirFormat format) {
        try {
            return Manager.parseSingle(context,
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), format);
        } catch (Throwable e) {
            System.out.println("[spike] PARSE FAILED (" + format + "): " + e);
            return null;
        }
    }

    @Test
    @DisplayName("a UBL document is read from its own XML, values, attributes and precision intact")
    void readFromUblXml() throws Exception {
        TenantContext context = context();
        Element document = parsed(context, UBL_XML, Manager.FhirFormat.XML);

        assertEquals("INV-2026-0001", document.getNamedChild("ID").getNamedChildValue("value"));
        assertEquals("2026-09-01", document.getNamedChild("IssueDate").getNamedChildValue("value"));
        Element endpoint = document.getNamedChild("AccountingSupplierParty").getNamedChild("EndpointID");
        assertEquals("7300010000001", endpoint.getNamedChildValue("value"));
        assertEquals("0088", endpoint.getNamedChildValue("schemeID"),
                "a qualifying attribute is part of the value, not decoration");
        Element amount = document.getNamedChild("LegalMonetaryTotal").getNamedChild("PayableAmount");
        assertEquals("120.50", amount.getNamedChildValue("value"),
                "a trailing zero is precision: an amount must not be renormalised");
        assertEquals("EUR", amount.getNamedChildValue("currencyID"));
    }

    @Test
    @DisplayName("the stored form is JSON, and rendering it back produces UBL's own XML")
    void storedAsJsonRenderedAsXml() throws Exception {
        TenantContext context = context();
        Element fromXml = parsed(context, UBL_XML, Manager.FhirFormat.XML);

        assertEquals("Invoice", fromXml.fhirType(),
                "the XML parser types a document by its root element name");
        // R5 has an Invoice resource of its own, and the JSON parser resolves
        // the FHIR canonical URL for a name first — so a stored document that
        // called itself an Invoice would come back as the R5 resource, empty.
        fromXml.setType("UBLInvoice");

        String stored = composed(context, fromXml, Manager.FhirFormat.JSON);
        assertTrue(stored.contains("\"resourceType\" : \"UBLInvoice\""), stored);
        assertTrue(stored.contains("\"value\" : 120.50"), "written precision survives: " + stored);

        Element reparsed = parsed(context, stored, Manager.FhirFormat.JSON);
        assertEquals("UBLInvoice", reparsed.fhirType());
        assertEquals("INV-2026-0001", reparsed.getNamedChild("ID").getNamedChildValue("value"));

        String rendered = composed(context, reparsed, Manager.FhirFormat.XML);
        assertTrue(rendered.contains("xmlns=\"" + INVOICE_NS + "\""), rendered);
        assertTrue(rendered.contains(":ID>INV-2026-0001</"), rendered);
        assertTrue(rendered.contains("schemeID=\"0088\">7300010000001<"), rendered);
        assertTrue(rendered.contains("currencyID=\"EUR\">120.50<"), rendered);
        assertTrue(rendered.contains("<Invoice "), "the root is UBL's name, not the type's: " + rendered);
    }

    @Test
    @DisplayName("the stored form validates against the logical model")
    void storedFormValidates() throws Exception {
        ElementPayloads payloads = version.payloadsFor(Terms.NONE, List.of(model()));
        TenantContext context = context();
        Element fromXml = parsed(context, UBL_XML, Manager.FhirFormat.XML);
        fromXml.setType("UBLInvoice");
        byte[] stored = composed(context, fromXml, Manager.FhirFormat.JSON).getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of(), payloads.validate("UBLInvoice", payloads.read(null, stored)));
    }

    @Test
    @DisplayName("the ancestor slots reach the wire, so a face rendering UBL has to take them off")
    void ancestorSlotsReachTheWire() throws Exception {
        TenantContext context = context();
        Element fromXml = parsed(context, UBL_XML, Manager.FhirFormat.XML);
        fromXml.setType("UBLInvoice");
        byte[] stored = composed(context, fromXml, Manager.FhirFormat.JSON).getBytes(StandardCharsets.UTF_8);

        byte[] served = ElementAncestors.rendered(context, stored, "inv-1", 3L);
        Element servedElement = parsed(context, new String(served, StandardCharsets.UTF_8),
                Manager.FhirFormat.JSON);
        String xml = composed(context, servedElement, Manager.FhirFormat.XML);

        assertTrue(xml.contains("<id value=\"inv-1\"/>"),
                "the store's own slots are composed into the domain's document: " + xml);
        assertTrue(xml.contains("<versionId value=\"3\"/>"), xml);
    }

    @Test
    @DisplayName("the JSON hop normalises element order, which XML alone preserves")
    void theJsonHopNormalisesOrder() throws Exception {
        TenantContext context = context();
        String reordered = UBL_XML
                .replace("  <cbc:IssueDate>2026-09-01</cbc:IssueDate>\n", "")
                .replace("<cbc:CustomizationID>",
                        "<cbc:IssueDate>2026-09-01</cbc:IssueDate>\n  <cbc:CustomizationID>");
        Element outOfOrder = parsed(context, reordered, Manager.FhirFormat.XML);
        outOfOrder.setType("UBLInvoice");

        String straight = composed(context, outOfOrder, Manager.FhirFormat.XML);
        assertTrue(straight.indexOf("IssueDate") < straight.indexOf("CustomizationID"),
                "XML to XML keeps the order the sender wrote: " + straight);

        Element throughJson = parsed(context,
                composed(context, outOfOrder, Manager.FhirFormat.JSON), Manager.FhirFormat.JSON);
        String viaJson = composed(context, throughJson, Manager.FhirFormat.XML);
        assertTrue(viaJson.indexOf("CustomizationID") < viaJson.indexOf("IssueDate"),
                "through JSON, children come back in the model's sequence: " + viaJson);
    }
}
