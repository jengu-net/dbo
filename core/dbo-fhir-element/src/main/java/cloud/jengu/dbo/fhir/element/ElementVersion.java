package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirFace;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.SearchParameter;

import java.util.ArrayList;
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
 * <p>Built once per version for the node: the definitions are tens of
 * megabytes parsed into a context, and nothing in them is any tenant's.
 */
public final class ElementVersion {

    private static final Map<String, ElementVersion> BY_CODE = new ConcurrentHashMap<>();

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
                .build();
    }

    /** The version served under this code, loaded once for the node. */
    public static ElementVersion of(String code) {
        return BY_CODE.computeIfAbsent(code, c -> new ElementVersion(c, offline(c)));
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
        List<SearchParameter> parameters = parametersFor(typeName);
        return (type, payload) -> extract(parameters, payload, canonical);
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
            String url = element.getNamedChildValue("url");
            if (url != null) {
                return url;
            }
        }
        throw new IllegalArgumentException("canonical resource without url");
    }

    SimpleWorkerContext context() {
        return context;
    }
}
