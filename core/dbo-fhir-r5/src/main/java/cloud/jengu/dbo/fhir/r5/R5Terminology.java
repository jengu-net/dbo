package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.fhir.common.TerminologyFacade.IngestResult;
import cloud.jengu.dbo.terminology.Compose;
import cloud.jengu.dbo.terminology.Concept;
import cloud.jengu.dbo.terminology.TerminologyStore;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.ValueSet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The R5 face of the normalized terminology store (§6): CodeSystem metadata
 * SHELLS go through the object engine (identity, history, feed), concepts go
 * native; the resource form is a projection reassembled on demand — the
 * declared truth-form inversion (REQ-DBO-CORE-DECLARED-TRUTH-FORM).
 * Public surface: JSON in, JSON out (§7.3).
 */
public final class R5Terminology implements cloud.jengu.dbo.fhir.common.FhirTerminology {

    /** Preserves the original CodeSystem.content across the shell round-trip. */
    static final String ORIGINAL_CONTENT_EXT = "https://dbo.dev/fhir/ext/original-content";

    private final ObjectStore store;
    private final R5Personality personality;
    private final TerminologyStore terminology;

    public R5Terminology(ObjectStore store, R5Personality personality, TerminologyStore terminology) {
        this.store = store;
        this.personality = personality;
        this.terminology = terminology;
    }

    // ----------------------------------------------------------- operations

    /**
     * The three operations this facade answers (#51).
     *
     * <p>Each carries its own status decision: an unregistered ValueSet is a
     * 404 from {@code $expand}, an unknown code is a 404 from {@code $lookup},
     * while {@code $validate-code} answers 200 with {@code result: false} —
     * because "no" is the answer to that question rather than a failure to
     * answer it. A router inferring statuses would have flattened all three.
     */
    @Override
    public java.util.List<cloud.jengu.dbo.fhir.common.FhirOperation> operations() {
        return java.util.List.of(
                operation("expand", "ValueSet-expand", "ValueSet",
                        (type, query, body) -> expand(required(query, "url"), query.get("filter"),
                                intOf(query, "offset", 0), intOf(query, "count", 100))
                                .map(cloud.jengu.dbo.fhir.common.FhirOperation.Answer::ok)
                                .orElseGet(() -> notRegistered(
                                        "ValueSet not registered: " + required(query, "url")))),
                operation("lookup", "CodeSystem-lookup", "CodeSystem",
                        (type, query, body) -> lookup(required(query, "system"),
                                required(query, "code"))
                                .map(cloud.jengu.dbo.fhir.common.FhirOperation.Answer::ok)
                                .orElseGet(() -> notRegistered("code not found"))),
                operation("validate-code", "CodeSystem-validate-code", "CodeSystem",
                        (type, query, body) -> cloud.jengu.dbo.fhir.common.FhirOperation.Answer.ok(
                                validateCode(required(query, "system"), required(query, "code")))));
    }

    /** The shape of an answer: what happened, and what to send. */
    private interface Handler {
        cloud.jengu.dbo.fhir.common.FhirOperation.Answer answer(
                String typeName, java.util.Map<String, String> query, String body);
    }

    private cloud.jengu.dbo.fhir.common.FhirOperation operation(String name, String definition,
            String type, Handler handler) {
        return new cloud.jengu.dbo.fhir.common.FhirOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String definition() {
                return "http://hl7.org/fhir/OperationDefinition/" + definition;
            }

            @Override
            public java.util.Set<String> types() {
                return java.util.Set.of(type);
            }

            @Override
            public Answer answer(String typeName, java.util.Map<String, String> query, String body) {
                return handler.answer(typeName, query, body);
            }
        };
    }

    private cloud.jengu.dbo.fhir.common.FhirOperation.Answer notRegistered(String diagnostics) {
        return cloud.jengu.dbo.fhir.common.FhirOperation.Answer.status(404,
                new R5Store(store, personality, "").operationOutcome("not-found", diagnostics));
    }

    private static String required(java.util.Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required parameter: " + name);
        }
        return value;
    }

    private static int intOf(java.util.Map<String, String> query, String name, int fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    // --------------------------------------------------------------- ingest

    /** Shell through the engine, concepts through COPY. Returns (engine result, concept count). */
    @Override
    public IngestResult ingestCodeSystem(String codeSystemJson) {
        CodeSystem cs = (CodeSystem) personality.ctxInternal().newJsonParser()
                .parseResource(codeSystemJson);
        String url = cs.getUrl();

        List<Concept> flat = new ArrayList<>();
        flatten(cs.getConcept(), null, flat);

        String shellJson = personality.ctxInternal().newJsonParser()
                .encodeResourceToString(shellOf(cs));

        PutResult engineResult = new R5Store(store, personality, "").putCanonical(shellJson);
        long imported = terminology.importSystem(url, cs.getVersion(), flat.iterator());
        return new IngestResult(engineResult.id(), engineResult.versionId(), imported);
    }

    @Override
    public PutResult ingestValueSet(String valueSetJson) {
        ValueSet vs = (ValueSet) personality.ctxInternal().newJsonParser().parseResource(valueSetJson);
        PutResult result = new R5Store(store, personality, "").putCanonical(valueSetJson);

        putCompose(vs);
        return result;
    }


    /** A ValueSet's compose, into the native form — shared by ingest and receive. */
    private void putCompose(ValueSet vs) {
        List<Compose.Include> includes = new ArrayList<>();
        for (ValueSet.ConceptSetComponent inc : vs.getCompose().getInclude()) {
            String isA = null;
            for (ValueSet.ConceptSetFilterComponent f : inc.getFilter()) {
                if ("concept".equals(f.getProperty())
                        && f.getOp() == org.hl7.fhir.r5.model.Enumerations.FilterOperator.ISA) {
                    isA = f.getValue();
                }
            }
            includes.add(new Compose.Include(inc.getSystem(),
                    inc.getConcept().stream().map(ValueSet.ConceptReferenceComponent::getCode).toList(),
                    isA));
        }
        List<Compose.Exclude> excludes = new ArrayList<>();
        for (ValueSet.ConceptSetComponent ex : vs.getCompose().getExclude()) {
            excludes.add(new Compose.Exclude(ex.getSystem(),
                    ex.getConcept().stream().map(ValueSet.ConceptReferenceComponent::getCode).toList()));
        }
        terminology.putValueSet(vs.getUrl(), vs.getVersion(), new Compose(includes, excludes));
    }

    /** The stored form: metadata, a count, and an honest {@code not-present}. */
    private CodeSystem shellOf(CodeSystem cs) {
        List<Concept> flat = new ArrayList<>();
        flatten(cs.getConcept(), null, flat);
        CodeSystem shell = cs.copy();
        shell.setConcept(List.of());
        shell.setCount(flat.size());
        // the shell honestly declares its concepts live elsewhere; the original
        // content mode rides an extension for faithful reassembly
        if (cs.hasContent() && shell.getExtensionByUrl(ORIGINAL_CONTENT_EXT) == null) {
            shell.addExtension(ORIGINAL_CONTENT_EXT, new StringType(cs.getContent().toCode()));
        }
        shell.setContent(org.hl7.fhir.r5.model.Enumerations.CodeSystemContentMode.NOTPRESENT);
        return shell;
    }

    private void flatten(List<CodeSystem.ConceptDefinitionComponent> concepts, String parent,
            List<Concept> out) {
        for (CodeSystem.ConceptDefinitionComponent c : concepts) {
            Map<String, String> designations = new LinkedHashMap<>();
            for (CodeSystem.ConceptDefinitionDesignationComponent d : c.getDesignation()) {
                if (d.hasLanguage() && d.hasValue()) {
                    designations.put(d.getLanguage(), d.getValue());
                }
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (CodeSystem.ConceptPropertyComponent prop : c.getProperty()) {
                if (prop.hasCode() && prop.getValue() != null) {
                    properties.put(prop.getCode(), prop.getValue().primitiveValue());
                }
            }
            out.add(new Concept(c.getCode(), c.getDisplay(), parent, designations, properties));
            if (!c.getConcept().isEmpty()) {
                flatten(c.getConcept(), c.getCode(), out);
            }
        }
    }

    // ----------------------------------------------------------- projection

    /** The resource form, reassembled: engine shell + native concept tree. */
    public Optional<String> codeSystemResource(String url) {
        List<StoredObject> shells = store.getByIdentifier("CodeSystem",
                List.of(new Identifier(Identifier.CANONICAL_SYSTEM, url)));
        if (shells.isEmpty()) {
            return Optional.empty();
        }
        CodeSystem shell = (CodeSystem) personality.ctxInternal().newJsonParser()
                .parseResource(new String(shells.get(0).payload(), StandardCharsets.UTF_8));
        var originalContent = shell.getExtensionByUrl(ORIGINAL_CONTENT_EXT);
        if (originalContent != null) {
            shell.setContent(org.hl7.fhir.r5.model.Enumerations.CodeSystemContentMode
                    .fromCode(originalContent.getValue().primitiveValue()));
            shell.getExtension().removeIf(e -> ORIGINAL_CONTENT_EXT.equals(e.getUrl()));
        }

        Map<String, CodeSystem.ConceptDefinitionComponent> byCode = new LinkedHashMap<>();
        List<Concept> all = terminology.allConcepts(url);
        for (Concept c : all) {
            CodeSystem.ConceptDefinitionComponent def = new CodeSystem.ConceptDefinitionComponent();
            def.setCode(c.code());
            def.setDisplay(c.display());
            c.designations().forEach((lang, value) -> def.addDesignation()
                    .setLanguage(lang).setValue(value));
            c.properties().forEach((code, value) -> def.addProperty()
                    .setCode(code).setValue(new StringType(value)));
            byCode.put(c.code(), def);
        }
        for (Concept c : all) {
            CodeSystem.ConceptDefinitionComponent def = byCode.get(c.code());
            if (c.parentCode() != null && byCode.containsKey(c.parentCode())) {
                byCode.get(c.parentCode()).getConcept().add(def);
            } else {
                shell.getConcept().add(def);
            }
        }
        return Optional.of(personality.ctxInternal().newJsonParser().encodeResourceToString(shell));
    }

    // ----------------------------------------------------------- operations

    /** CodeSystem/$lookup → Parameters JSON. */
    @Override
    public Optional<String> lookup(String system, String code) {
        return terminology.lookup(system, code).map(c -> {
            Parameters p = new Parameters();
            p.addParameter("name", new StringType(system));
            p.addParameter("display", new StringType(c.display() == null ? "" : c.display()));
            c.designations().forEach((lang, value) -> {
                Parameters.ParametersParameterComponent d = p.addParameter().setName("designation");
                d.addPart().setName("language").setValue(new StringType(lang));
                d.addPart().setName("value").setValue(new StringType(value));
            });
            c.properties().forEach((propertyCode, value) -> {
                Parameters.ParametersParameterComponent prop = p.addParameter().setName("property");
                prop.addPart().setName("code").setValue(new StringType(propertyCode));
                prop.addPart().setName("value").setValue(new StringType(value));
            });
            return personality.ctxInternal().newJsonParser().encodeResourceToString(p);
        });
    }

    /** $validate-code → Parameters JSON with result + display. */
    @Override
    public String validateCode(String system, String code) {
        Optional<Concept> concept = terminology.lookup(system, code);
        Parameters p = new Parameters();
        p.addParameter("result", new BooleanType(concept.isPresent()));
        concept.ifPresent(c -> p.addParameter("display",
                new StringType(c.display() == null ? "" : c.display())));
        return personality.ctxInternal().newJsonParser().encodeResourceToString(p);
    }

    /** ValueSet/$expand → ValueSet JSON with expansion.contains. */
    @Override
    public Optional<String> expand(String valueSetUrl, String filter, int offset, int count) {
        return terminology.valueSetCompose(valueSetUrl).map(compose -> {
            TerminologyStore.Expansion expansion = terminology.expand(compose, filter, offset, count);
            ValueSet vs = new ValueSet();
            vs.setUrl(valueSetUrl);
            vs.setStatus(org.hl7.fhir.r5.model.Enumerations.PublicationStatus.ACTIVE);
            ValueSet.ValueSetExpansionComponent exp = vs.getExpansion();
            exp.setTotal((int) expansion.total());
            exp.setOffset(offset);
            for (TerminologyStore.ExpandedConcept c : expansion.contains()) {
                exp.addContains().setSystem(c.system()).setCode(c.code()).setDisplay(c.display());
            }
            return personality.ctxInternal().newJsonParser().encodeResourceToString(vs);
        });
    }

    // ---------------------------------------------------------- sync grain

    /**
     * The two types this face keeps in a form smaller than the wire's
     * (REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES).
     */
    @Override
    public boolean handles(String typeName) {
        return "CodeSystem".equals(typeName) || "ValueSet".equals(typeName);
    }

    /**
     * A shell goes out whole. A ValueSet is already whole — its compose IS its
     * content — so it travels untouched; only the CodeSystem has concepts
     * living somewhere the stream cannot see.
     */
    @Override
    public byte[] forTransport(String typeName, byte[] storedPayload) {
        if (!"CodeSystem".equals(typeName)) {
            return storedPayload;
        }
        CodeSystem shell = (CodeSystem) personality.ctxInternal().newJsonParser()
                .parseResource(new String(storedPayload, StandardCharsets.UTF_8));
        return codeSystemResource(shell.getUrl())
                .map(json -> json.getBytes(StandardCharsets.UTF_8))
                // a shell whose system this store has no concepts for is sent
                // as it is: an empty system is a fact, not a failure
                .orElse(storedPayload);
    }

    /**
     * The concepts land in this store's native form, and the shell goes back to
     * the engine — which writes it under the source's identity, as it does for
     * every other type.
     */
    @Override
    public byte[] receive(String typeName, byte[] transportedPayload) {
        String json = new String(transportedPayload, StandardCharsets.UTF_8);
        if ("ValueSet".equals(typeName)) {
            putCompose((ValueSet) personality.ctxInternal().newJsonParser().parseResource(json));
            return transportedPayload;
        }
        CodeSystem cs = (CodeSystem) personality.ctxInternal().newJsonParser().parseResource(json);
        List<Concept> flat = new ArrayList<>();
        flatten(cs.getConcept(), null, flat);
        terminology.importSystem(cs.getUrl(), cs.getVersion(), flat.iterator());
        return personality.ctxInternal().newJsonParser().encodeResourceToString(shellOf(cs))
                .getBytes(StandardCharsets.UTF_8);
    }
}
