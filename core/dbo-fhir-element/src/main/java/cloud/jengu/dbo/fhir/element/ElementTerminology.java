package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirOperation;
import cloud.jengu.dbo.fhir.common.FhirTerminology;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.terminology.Compose;
import cloud.jengu.dbo.terminology.Concept;
import cloud.jengu.dbo.terminology.TerminologyAnswers;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.hl7.fhir.r5.elementmodel.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The element face's terminology surface: metadata SHELLS through the object
 * engine, concepts in the normalized store, the resource form reassembled on
 * demand (REQ-DBO-CORE-DECLARED-TRUTH-FORM).
 *
 * <p>Answers the same three operations as the typed faces, for whichever
 * versions this face serves. That was already the promise —
 * REQ-DBO-TERM-EVERY-TENANT-ANSWERS says "whichever FHIR version it speaks; no
 * tenant is a second-class reader" — and this face was the tenant it was not
 * true for: it refused every operation, and because
 * {@code publishVocabularies} reads an empty {@code operations()} as "cannot
 * hold concepts natively", dbo's own vocabularies were written whole here and
 * resolved nothing.
 *
 * <p>Reading a client's CodeSystem is where this differs from the typed faces
 * and why it is a separate class rather than a shared one: they parse with a
 * version's model, this parses with the element model, and every field below is
 * reached by name rather than by getter. The <b>answers</b> are shared —
 * {@link TerminologyAnswers} renders them once for all three faces, because
 * those shapes did not move between versions.
 *
 * <p>The routing declarations are the one duplication left, and deliberately:
 * they are the statuses each operation chose (a 404 for an unregistered
 * ValueSet, a 200 with {@code result: false} for an unknown code), and folding
 * three faces' status decisions into one place would make that choice harder to
 * see, not easier.
 */
final class ElementTerminology implements FhirTerminology {

    /** Preserves the original CodeSystem.content across the shell round-trip. */
    static final String ORIGINAL_CONTENT_EXT = "https://dbo.dev/fhir/ext/original-content";

    private final ObjectStore store;
    private final ElementStore canonical;
    private final TerminologyStore terminology;
    private final Payloads<Element> payloads;

    @SuppressWarnings("unchecked")
    ElementTerminology(ObjectStore store, ElementVersion version, List<FhirTypeConfig> types,
            TerminologyStore terminology) {
        this.store = store;
        this.terminology = terminology;
        // No tenant terms and no base url: this path reads and writes canonical
        // artifacts by their own url and never renders a served resource, so
        // the shared definitions-only payloads are the right ones — a
        // per-tenant validating context here would cost a parse per tenant for
        // a validation nothing on this path asks for.
        this.canonical = new ElementStore(store, version, types, "");
        this.payloads = (Payloads<Element>) (Payloads<?>) version.face().require(Payloads.class);
    }

    // ----------------------------------------------------------- operations

    @Override
    public List<FhirOperation> operations() {
        return List.of(
                operation("expand", "ValueSet-expand", "ValueSet",
                        query -> expand(required(query, "url"), query.get("filter"),
                                intOf(query, "offset", 0), intOf(query, "count", 100))
                                .map(FhirOperation.Answer::ok)
                                .orElseGet(() -> notFound(
                                        "ValueSet not registered: " + required(query, "url")))),
                operation("lookup", "CodeSystem-lookup", "CodeSystem",
                        query -> lookup(required(query, "system"), required(query, "code"))
                                .map(FhirOperation.Answer::ok)
                                .orElseGet(() -> notFound("code not found"))),
                operation("validate-code", "CodeSystem-validate-code", "CodeSystem",
                        query -> FhirOperation.Answer.ok(
                                validateCode(required(query, "system"), required(query, "code")))));
    }

    private interface Handler {
        FhirOperation.Answer answer(Map<String, String> query);
    }

    private FhirOperation operation(String name, String definition, String type, Handler handler) {
        return new FhirOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String definition() {
                return "http://hl7.org/fhir/OperationDefinition/" + definition;
            }

            @Override
            public Set<String> types() {
                return Set.of(type);
            }

            @Override
            public Answer answer(String typeName, Map<String, String> query, String body) {
                return handler.answer(query);
            }
        };
    }

    private FhirOperation.Answer notFound(String diagnostics) {
        return FhirOperation.Answer.status(404,
                canonical.operationOutcome("not-found", diagnostics));
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required parameter: " + name);
        }
        return value;
    }

    private static int intOf(Map<String, String> query, String name, int fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    @Override
    public Optional<String> lookup(String system, String code) {
        return terminology.lookup(system, code).map(c -> TerminologyAnswers.lookup(system, c));
    }

    @Override
    public String validateCode(String system, String code) {
        return TerminologyAnswers.validateCode(terminology.lookup(system, code).orElse(null));
    }

    @Override
    public Optional<String> expand(String valueSetUrl, String filter, int offset, int count) {
        return terminology.valueSetCompose(valueSetUrl).map(compose -> TerminologyAnswers.expansion(
                valueSetUrl, offset, terminology.expand(compose, filter, offset, count)));
    }

    // --------------------------------------------------------------- ingest

    @Override
    public IngestResult ingestCodeSystem(String codeSystemJson) {
        Element cs = read(codeSystemJson);
        String url = cs.getNamedChildValue("url");

        List<Concept> flat = new ArrayList<>();
        flatten(cs, null, flat);

        PutResult engineResult = canonical.putCanonical(json(shellOf(cs, flat.size())));
        long imported = terminology.importSystem(url, cs.getNamedChildValue("version"),
                flat.iterator());
        return new IngestResult(engineResult.id(), engineResult.versionId(), imported);
    }

    @Override
    public PutResult ingestValueSet(String valueSetJson) {
        PutResult result = canonical.putCanonical(valueSetJson);
        putCompose(read(valueSetJson));
        return result;
    }

    /** A ValueSet's compose, into the native form — shared by ingest and receive. */
    private void putCompose(Element vs) {
        Element compose = vs.getNamedChild("compose");
        List<Compose.Include> includes = new ArrayList<>();
        List<Compose.Exclude> excludes = new ArrayList<>();
        if (compose != null) {
            for (Element include : children(compose, "include")) {
                String isA = null;
                for (Element filter : children(include, "filter")) {
                    if ("concept".equals(filter.getNamedChildValue("property"))
                            && "is-a".equals(filter.getNamedChildValue("op"))) {
                        isA = filter.getNamedChildValue("value");
                    }
                }
                includes.add(new Compose.Include(include.getNamedChildValue("system"),
                        codesOf(include), isA));
            }
            for (Element exclude : children(compose, "exclude")) {
                excludes.add(new Compose.Exclude(exclude.getNamedChildValue("system"),
                        codesOf(exclude)));
            }
        }
        terminology.putValueSet(vs.getNamedChildValue("url"), vs.getNamedChildValue("version"),
                new Compose(includes, excludes));
    }

    private static List<String> codesOf(Element conceptSet) {
        List<String> codes = new ArrayList<>();
        for (Element concept : children(conceptSet, "concept")) {
            codes.add(concept.getNamedChildValue("code"));
        }
        return codes;
    }

    /** The stored form: metadata, a count, and an honest {@code not-present}. */
    private Element shellOf(Element cs, int count) {
        Element shell = read(json(cs));
        shell.getChildren().removeIf(child -> "concept".equals(child.getName()));
        shell.setChildValue("count", Integer.toString(count));
        // the shell honestly declares its concepts live elsewhere; the original
        // content mode rides an extension for faithful reassembly
        String content = shell.getNamedChildValue("content");
        if (content != null && originalContentOf(shell) == null) {
            Element extension = shell.makeElement("extension");
            extension.setChildValue("url", ORIGINAL_CONTENT_EXT);
            extension.setChildValue("valueString", content);
        }
        shell.setChildValue("content", "not-present");
        return shell;
    }

    private static Element originalContentOf(Element shell) {
        for (Element extension : children(shell, "extension")) {
            if (ORIGINAL_CONTENT_EXT.equals(extension.getNamedChildValue("url"))) {
                return extension;
            }
        }
        return null;
    }

    private void flatten(Element parent, String parentCode, List<Concept> out) {
        for (Element concept : children(parent, "concept")) {
            String code = concept.getNamedChildValue("code");
            Map<String, String> designations = new LinkedHashMap<>();
            for (Element designation : children(concept, "designation")) {
                String language = designation.getNamedChildValue("language");
                String value = designation.getNamedChildValue("value");
                if (language != null && value != null) {
                    designations.put(language, value);
                }
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (Element property : children(concept, "property")) {
                String propertyCode = property.getNamedChildValue("code");
                String value = choiceValue(property);
                if (propertyCode != null && value != null) {
                    properties.put(propertyCode, value);
                }
            }
            out.add(new Concept(code, concept.getNamedChildValue("display"), parentCode,
                    designations, properties));
            flatten(concept, code, out);
        }
    }

    /**
     * A {@code value[x]}'s value, whichever type it was written as.
     *
     * <p>The element model names a choice child by the type it carries —
     * {@code valueString}, {@code valueCode}, {@code valueInteger} — so there is
     * no {@code value} child to ask for, and a getter by name finds nothing. The
     * store holds properties as text, so whatever primitive arrived is taken as
     * its string.
     */
    private static String choiceValue(Element property) {
        for (Element child : property.getChildren()) {
            if (child.getName().startsWith("value")) {
                return child.primitiveValue();
            }
        }
        return null;
    }

    // ----------------------------------------------------------- projection

    /** The resource form, reassembled: engine shell + native concept tree. */
    Optional<String> codeSystemResource(String url) {
        List<StoredObject> shells = store.getByIdentifier("CodeSystem",
                List.of(new Identifier(Identifier.CANONICAL_SYSTEM, url)));
        if (shells.isEmpty()) {
            return Optional.empty();
        }
        Element shell = payloads.read(null, shells.get(0).payload());
        Element originalContent = originalContentOf(shell);
        if (originalContent != null) {
            shell.setChildValue("content", choiceValue(originalContent));
            shell.getChildren().remove(originalContent);
        }

        // REPLACED, not appended: a CodeSystem written the ordinary way is
        // stored whole, and adding the native concepts to the ones already in
        // the document produced a resource carrying every code twice — on the
        // wire, a sync round whose COPY collides with itself on (system, code)
        // and a stream that never acks. What this store holds natively is
        // the answer, whatever the stored document carries beside it.
        shell.getChildren().removeIf(child -> "concept".equals(child.getName()));

        List<Concept> all = terminology.allConcepts(url);
        Map<String, List<Concept>> byParent = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        all.forEach(c -> known.add(c.code()));
        for (Concept c : all) {
            // a concept whose parent this store does not hold is a root here:
            // the tree is what is present, not what was once referenced
            String parent = c.parentCode() != null && known.contains(c.parentCode())
                    ? c.parentCode() : null;
            byParent.computeIfAbsent(parent, p -> new ArrayList<>()).add(c);
        }
        appendConcepts(shell, byParent, null);
        return Optional.of(json(shell));
    }

    /**
     * Depth-first, parent before child — which the element model requires: a
     * child element is made from its parent's property, so it cannot be built
     * detached and adopted later the way a typed object can. The store's order
     * is kept within each parent.
     */
    private void appendConcepts(Element parent, Map<String, List<Concept>> byParent, String code) {
        for (Concept concept : byParent.getOrDefault(code, List.of())) {
            Element element = parent.makeElement("concept");
            element.setChildValue("code", concept.code());
            if (concept.display() != null) {
                element.setChildValue("display", concept.display());
            }
            concept.designations().forEach((language, value) -> {
                Element designation = element.makeElement("designation");
                designation.setChildValue("language", language);
                designation.setChildValue("value", value);
            });
            concept.properties().forEach((propertyCode, value) -> {
                Element property = element.makeElement("property");
                property.setChildValue("code", propertyCode);
                property.setChildValue("valueString", value);
            });
            appendConcepts(element, byParent, concept.code());
        }
    }

    // ---------------------------------------------------------- sync grain

    /**
     * The two types this face keeps in a form smaller than the wire's
     * (REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES).
     *
     * <p>Not "nothing", which is what this answered while the face held no
     * concepts natively: once the concepts live in the normalized store, a
     * shell shipped as-is would arrive as a CodeSystem with no codes in it.
     */
    @Override
    public boolean handles(String typeName) {
        return "CodeSystem".equals(typeName) || "ValueSet".equals(typeName);
    }

    @Override
    public byte[] forTransport(String typeName, byte[] storedPayload) {
        if (!"CodeSystem".equals(typeName)) {
            return storedPayload;
        }
        Element shell = payloads.read(null, storedPayload);
        return codeSystemResource(shell.getNamedChildValue("url"))
                .map(json -> json.getBytes(StandardCharsets.UTF_8))
                // a shell whose system this store has no concepts for is sent
                // as it is: an empty system is a fact, not a failure
                .orElse(storedPayload);
    }

    @Override
    public byte[] storedFormOf(String typeName, byte[] transportedPayload) {
        if ("ValueSet".equals(typeName)) {
            return transportedPayload;
        }
        Element document = payloads.read(null, transportedPayload);
        List<Concept> flat = new ArrayList<>();
        flatten(document, null, flat);
        return json(shellOf(document, flat.size())).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void keep(String typeName, byte[] transportedPayload) {
        Element document = payloads.read(null, transportedPayload);
        if ("ValueSet".equals(typeName)) {
            putCompose(document);
            return;
        }
        List<Concept> flat = new ArrayList<>();
        flatten(document, null, flat);
        terminology.importSystem(document.getNamedChildValue("url"),
                document.getNamedChildValue("version"), flat.iterator());
    }

    // ------------------------------------------------------------- payloads

    private Element read(String json) {
        return payloads.read(null, json.getBytes(StandardCharsets.UTF_8));
    }

    private String json(Element document) {
        return new String(payloads.write(document), StandardCharsets.UTF_8);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        parent.getNamedChildren(name, found);
        return found;
    }
}
