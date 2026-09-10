package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.PortableRendering;
import cloud.jengu.dbo.fhir.common.FhirFace;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.SearchParameter;

import java.util.ArrayList;
import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One FHIR version, served from the definitions this face carries.
 *
 * <p>Nothing here is written for R6. A version is (definitions, version code):
 * how a payload parses, what is valid, what a search parameter means and where
 * it points all come from StructureDefinitions and SearchParameters in a
 * package, and the element model reads an instance against them without a
 * generated Java class for any of it. That is what makes one implementation
 * able to serve versions that were released years apart, and one still at
 * ballot that has no generated model at all
 * (REQ-DBO-VER-CONCURRENT-VERSIONS).
 *
 * <p>Built once per version for the node, and <b>held softly</b>. The
 * definitions are not tens of megabytes: measured, a context retains ~180 MB
 * for R4 and a little over 200 MB each for R5 and R6. A node that serves one
 * version and is asked about another once pays for the second for as long as
 * it runs, and while these were held hard there was no pressure under which
 * that memory could come back.
 *
 * <p>Softly is the honest strength for it. The context is expensive to build
 * and cheap to rebuild from nothing but the packages, it belongs to no tenant,
 * and a version nobody has touched since memory got tight is exactly what
 * should go first. A version in use stays reachable through the faces built on
 * it, so this drops what is idle rather than what is working — and the cost of
 * being wrong is one slow request, not a wrong answer.
 */
public final class ElementVersion {

    private static final Map<String, SoftReference<ElementVersion>> BY_CODE =
            new ConcurrentHashMap<>();

    private final String code;
    private final SimpleWorkerContext context;
    private final ElementPayloads payloads;
    private final PayloadFraming framing;
    private final DomainFace face;
    private final Map<String, List<SearchParameter>> searchParameters = new ConcurrentHashMap<>();

    private ElementVersion(String code, SimpleWorkerContext context) {
        this.code = code;
        this.context = context;
        this.payloads = new ElementPayloads(context);
        this.framing = new ElementFraming(context);
        this.face = FhirFace.describing(code)
                .providing(Payloads.class, payloads)
                .providing(PayloadFraming.class, framing)
                // the engine's own records in this version's words: a run as
                // a Task, an audit entry as an AuditEvent. The domain a version
                // claims is its own code (ElementFhirVersion.domain()).
                .providing(RecordProjection.class, new ElementRecordProjection(code, code))
                // whether two documents say the same thing — a domain question,
                // and the engine's own answer (compare the bytes) says they
                // differ because a tool wrote the fields in another order
                .providing(cloud.jengu.dbo.core.face.DocumentEquivalence.class,
                        ElementEquivalence.INSTANCE)
                // Version-scoped and pure: putting the ancestors back on a
                // stored payload needs this version's definitions and nothing
                // else. It was reachable only through a method on FhirVersion,
                // which is where an obligation goes when nobody has decided
                // which kind it is.
                .providing(PortableRendering.class,
                        (payload, id, versionId) -> new String(
                                ElementAncestors.rendered(context, payload, id, versionId),
                                java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    /**
     * The version served under this code, built if nothing holds it.
     *
     * <p>Double-checked under a lock rather than through
     * {@code computeIfAbsent}, because two threads arriving together on an
     * empty reference would otherwise each parse the packages — two hundred
     * megabytes and several seconds, twice, at the moment memory is already
     * short enough to have collected the first one.
     */
    public static ElementVersion of(String code) {
        ElementVersion held = held(code);
        if (held != null) {
            return held;
        }
        synchronized (BY_CODE) {
            held = held(code);
            if (held != null) {
                return held;
            }
            ElementVersion built = new ElementVersion(code, offline(code));
            BY_CODE.put(code, new SoftReference<>(built));
            return built;
        }
    }

    /** What the cache still holds, or null where the collector has been. */
    private static ElementVersion held(String code) {
        SoftReference<ElementVersion> reference = BY_CODE.get(code);
        return reference == null ? null : reference.get();
    }

    /**
     * The version's definitions, with no way out to a terminology server.
     *
     * <p>Coded values are checked against what the face carries, and a write
     * that would otherwise reach tx.fhir.org is a write that fails when
     * somebody else's server does — on a store's accept path, where the answer
     * has to be the store's own (REQ-DBO-TERM-EVERY-TENANT-ANSWERS).
     */
    private static SimpleWorkerContext offline(String code) {
        SimpleWorkerContext context = CarriedDefinitions.contextFor(code);
        context.setNoTerminologyServer(true);
        context.setCanRunWithoutTerminology(true);
        // Expansion needs parameters even when they say nothing. Left unset,
        // every expansion fails with a NullPointerException wearing a
        // terminology error's clothes — "the value provided was not found in
        // the value set", about a value that is in it.
        context.setExpansionParameters(new org.hl7.fhir.r5.model.Parameters());
        return context;
    }

    /** {@code r6} — what a tenant spec declares. */
    public String code() {
        return code;
    }

    /**
     * The version a payload is stored under, taken from the definitions rather
     * than written down beside them: a ballot is served under its own code
     * (REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION), and the two places it could be
     * spelled would eventually spell it differently.
     */
    public String payloadVersion() {
        return context.getVersion();
    }

    /** What the engine requires from this version. */
    public DomainFace face() {
        return face;
    }

    /**
     * The searchable envelope for one type, from that type's own search
     * parameters.
     *
     * <p>No {@code PayloadConverter} is declared beside it. A released HL7 core
     * carries no converters to or from R6, and a face that provides nothing for
     * a capability is refused by name rather than answering wrongly (§1).
     */
    public EnvelopeExtractor extractor(String typeName) {
        return extractor(typeName, false);
    }

    /**
     * @param canonical whether this type's identity is its {@code url}, which
     *                  the definitions do not say — it is the tenant's
     *                  declaration about the type, and the envelope is where an
     *                  identity is claimed
     */
    public EnvelopeExtractor extractor(String typeName, boolean canonical) {
        return extractor(typeName, canonical, List.of());
    }

    /**
     * The same, over this version's parameters <b>and</b> a tenant's own.
     *
     * <p>The extra ones are passed in rather than read here, and that is the
     * whole of the scoping rule: a version is one face serving every tenant on
     * this deployment, so it cannot hold one tenant's parameters, and a thing
     * that went looking for them would be reading a store — which a declared
     * capability may not do. Whoever holds the store composes the list and
     * hands it over.
     *
     * <p>The version's own win a collision. A tenant may add to what
     * {@code Patient?birthdate} means nowhere: redefining a parameter the
     * specification defines would make one tenant's {@code birthdate} a
     * different question from another's, under one code, with nothing at the
     * door to say so.
     */
    public EnvelopeExtractor extractor(String typeName, boolean canonical,
            List<SearchParameter> alsoAuthoredHere) {
        if (alsoAuthoredHere.isEmpty() && DefinitionParameters.isDefinitionType(typeName)) {
            // A definition is indexed from its JSON, because the toolchain
            // needs the version's definitions to parse one and a definition
            // arriving is what a tenant does not have yet. Same expressions,
            // same envelope — held identical by test over every definition
            // the face carries. A tenant that authored parameters of its own
            // over these types takes the toolchain path, whose expressions
            // are unbounded.
            List<DefinitionParameters.Parameter> parameters =
                    DefinitionParameters.forType(code, typeName);
            return (type, payload) -> DefinitionEnvelopes.extract(parameters, type, payload, canonical);
        }
        List<SearchParameter> parameters = union(parametersFor(typeName), alsoAuthoredHere);
        return (type, payload) -> extract(parameters, payload, canonical);
    }

    /** This version's parameters for a type, then whichever of the others it does not already define. */
    static List<SearchParameter> union(List<SearchParameter> defined,
            List<SearchParameter> authored) {
        if (authored.isEmpty()) {
            return defined;
        }
        Map<String, SearchParameter> byCode = new LinkedHashMap<>();
        defined.forEach(p -> byCode.put(p.getCode(), p));
        authored.forEach(p -> byCode.putIfAbsent(p.getCode(), p));
        return List.copyOf(new ArrayList<>(byCode.values()));
    }

    /**
     * The indexes a set of parameters declares: one per date parameter, which
     * are the sort paths a clinical search actually issues
     * (REQ-DBO-SRCH-DECLARED-INDEXES).
     *
     * <p>Here rather than beside the registrations, because a tenant-authored
     * date parameter must declare its index by the same rule as a defined one
     * — two derivations would eventually disagree, and the one that lost would
     * be the tenant's.
     */
    public static List<cloud.jengu.dbo.core.api.IndexSpec> indexesFor(
            List<SearchParameter> parameters) {
        List<cloud.jengu.dbo.core.api.IndexSpec> specs = new ArrayList<>();
        for (SearchParameter parameter : parameters) {
            if (parameter.getType()
                    == org.hl7.fhir.r5.model.Enumerations.SearchParamType.DATE) {
                specs.add(new cloud.jengu.dbo.core.api.IndexSpec(
                        parameter.getCode().replace('-', '_'),
                        cloud.jengu.dbo.core.api.ValueKind.DATE));
            }
        }
        return specs;
    }

    /**
     * Whether an expression can be evaluated at all, for a refusal at the door.
     *
     * <p>This version's own parameters are evaluable by construction and a
     * test holds that. A tenant-authored expression is arbitrary input, and the
     * only moment a person is present to fix it is the write — after that it
     * is a background reindex failing about a document nobody is looking at.
     */
    public java.util.Optional<String> whyNotEvaluable(String expression) {
        if (expression == null || expression.isBlank()) {
            return java.util.Optional.of("it has no expression, so there is nothing to extract");
        }
        try {
            org.hl7.fhir.r5.fhirpath.FHIRPathEngine engine =
                    new org.hl7.fhir.r5.fhirpath.FHIRPathEngine(context);
            engine.setHostServices(new ElementHostServices(context));
            engine.parse(expression);
            return java.util.Optional.empty();
        } catch (Exception notFhirPath) {
            return java.util.Optional.of(notFhirPath.getMessage() == null
                    ? notFhirPath.toString() : notFhirPath.getMessage());
        }
    }

    private Envelope extract(List<SearchParameter> parameters, byte[] payload, boolean canonical) {
        Element document = payloads.read(null, payload);
        return ElementEnvelopes.extract(context, parameters, document, canonical);
    }

    /** Every search parameter this version defines over a type, expression first. */
    List<SearchParameter> parametersFor(String typeName) {
        return searchParameters.computeIfAbsent(typeName, type -> {
            Map<String, SearchParameter> byCode = new LinkedHashMap<>();
            for (SearchParameter parameter : context.fetchResourcesByType(SearchParameter.class)) {
                boolean applies = parameter.getBase().stream()
                        .anyMatch(base -> type.equals(base.getCode()));
                if (!applies || parameter.getCode().startsWith("_")
                        || parameter.getExpression() == null
                        || parameter.getExpression().isBlank()) {
                    continue;
                }
                byCode.putIfAbsent(parameter.getCode(), parameter);
            }
            return List.copyOf(new ArrayList<>(byCode.values()));
        });
    }

    /**
     * Where one member's reference parameter points, for {@code _include}.
     *
     * <p>Asked of the document the page already read, and of the parameter's
     * own expression, so what an include follows and what a search filters on
     * are the same definition rather than two readings of it.
     */
    List<String[]> referencedTargets(Object document, String typeName, String refParam) {
        SearchParameter parameter = parametersFor(typeName).stream()
                .filter(p -> p.getCode().equals(refParam)).findFirst().orElse(null);
        if (parameter == null || !(document instanceof Element element)) {
            return List.of();
        }
        List<String[]> targets = new ArrayList<>();
        List<org.hl7.fhir.r5.model.Base> hits;
        try {
            org.hl7.fhir.r5.fhirpath.FHIRPathEngine fhirPath =
                    new org.hl7.fhir.r5.fhirpath.FHIRPathEngine(context);
            fhirPath.setHostServices(new ElementHostServices(context));
            hits = fhirPath.evaluate(element, parameter.getExpression());
        } catch (Exception e) {
            return List.of();
        }
        for (org.hl7.fhir.r5.model.Base hit : hits) {
            String reference = hit instanceof Element referenced
                    ? ("Reference".equals(referenced.fhirType())
                            ? referenced.getNamedChildValue("reference")
                            : referenced.primitiveValue())
                    : hit.primitiveValue();
            if (reference == null) {
                continue;
            }
            int slash = reference.lastIndexOf('/');
            if (slash > 0) {
                String type = reference.substring(0, slash);
                int previous = type.lastIndexOf('/');
                targets.add(new String[] {previous < 0 ? type : type.substring(previous + 1),
                        reference.substring(slash + 1)});
            }
        }
        return targets;
    }

    /** The canonical url of a canonical resource — its identity, for a conditional write. */
    String canonicalUrlOf(Object document) {
        if (document instanceof Element element) {
            // The same rule the envelope claims identity by, asked once: a
            // write and the index it is found through must not disagree about
            // what a resource is called.
            String url = ElementEnvelopes.canonicalIdentity(element);
            if (url != null) {
                return url;
            }
        }
        throw new IllegalArgumentException("canonical resource without url");
    }

    /**
     * A payloads view bound to one tenant's terminology: the same definitions,
     * with code membership answered by the tenant's own store where the
     * definitions are silent. Costs one context copy (~100ms),
     * paid once per tenant at facade construction, never per request.
     */
    ElementPayloads payloadsFor(Terms terms) {
        return payloadsFor(terms, java.util.List.of());
    }

    /**
     * The same, with the tenant's own StructureDefinitions in the view:
     * validation runs against the carried pack PLUS what this tenant defined
     * on top of it.
     */
    ElementPayloads payloadsFor(Terms terms, java.util.List<String> profiles) {
        return payloadsFor(terms, profiles, java.util.List.of());
    }

    /** The same, with the tenant's own converters in the view too. */
    ElementPayloads payloadsFor(Terms terms, java.util.List<String> profiles,
            java.util.List<String> maps) {
        try {
            return new ElementPayloads(new TenantContext(context(), terms, profiles, maps),
                    terms);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "cannot derive a tenant context from the shared " + code + " context", e);
        }
    }

    SimpleWorkerContext context() {
        return context;
    }
}
