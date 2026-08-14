package cloud.jengu.dbo.fhir.r4;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.StoredObject;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The R4 personality (REQ-DBO-VER-PERSONALITY-OWNS-MEANING): envelope
 * extraction driven by HAPI's built-in R4 search-parameter definitions,
 * strict search compilation to engine {@link Criteria}, validation on write,
 * and Bundle framing over the feed primitive. The public surface speaks JSON
 * strings and core api types only — no HAPI type crosses (§7.3).
 */
public final class R4Personality {

    public static final String DOMAIN = "r4";

    private final Map<String, R4TypeConfig> types = new LinkedHashMap<>();
    private volatile FhirContext ctx;
    private volatile FhirValidator validator;

    public R4Personality(List<R4TypeConfig> typeConfigs) {
        for (R4TypeConfig t : typeConfigs) {
            types.put(t.typeName(), t);
        }
    }

    // -------------------------------------------------------- registrations

    public List<TypeRegistration> registrations() {
        List<TypeRegistration> out = new ArrayList<>();
        for (R4TypeConfig t : types.values()) {
            EnvelopeExtractor extractor = (typeName, payload) -> withTccl(() -> extract(typeName, payload));
            List<IndexSpec> indexes = defaultIndexes(t.typeName());
            out.add(new TypeRegistration(t.typeName(), DOMAIN, t.identityClass(),
                    t.identitySystems(), extractor, indexes));
        }
        return out;
    }

    private List<IndexSpec> defaultIndexes(String typeName) {
        // declared indexes for the hot sort paths this slice serves
        List<IndexSpec> specs = new ArrayList<>();
        for (RuntimeSearchParam sp : searchParams(typeName)) {
            if (sp.getParamType() == ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.DATE) {
                specs.add(new IndexSpec(pathName(sp.getName()), ValueKind.DATE));
            }
        }
        return specs;
    }

    private Envelope extract(String typeName, byte[] payload) {
        IBaseResource resource = ctx().newJsonParser()
                .parseResource(new String(payload, StandardCharsets.UTF_8));
        Envelope e = new Envelope();
        IFhirPath fhirPath = ctx().newFhirPath();

        for (RuntimeSearchParam sp : searchParams(typeName)) {
            List<IBase> hits;
            try {
                hits = fhirPath.evaluate(resource, sp.getPath(), IBase.class);
            } catch (Exception ex) {
                continue; // paths with unsupported syntax are skipped, not fatal
            }
            String path = pathName(sp.getName());
            for (IBase hit : hits) {
                switch (sp.getParamType()) {
                    case TOKEN -> addToken(e, path, hit);
                    case STRING -> {
                        if (hit instanceof IPrimitiveType<?> p && p.getValueAsString() != null) {
                            // FHIR string search is case-insensitive; stored lowercase
                            e.value(path, EnvelopeValue.of(p.getValueAsString().toLowerCase()));
                        }
                    }
                    case DATE -> addDate(e, path, hit);
                    case NUMBER -> {
                        if (hit instanceof IPrimitiveType<?> p && p.getValueAsString() != null) {
                            e.value(path, EnvelopeValue.of(new BigDecimal(p.getValueAsString())));
                        }
                    }
                    case REFERENCE -> {
                        if (hit instanceof Reference r && r.getReferenceElement().hasResourceType()) {
                            e.reference(path, r.getReferenceElement().getResourceType(),
                                    r.getReferenceElement().getIdPart());
                        }
                    }
                    default -> { /* composite/quantity/special: later slices */ }
                }
            }
        }

        // every Identifier element becomes an engine identifier (searchable;
        // identity-bearing per the type's designated systems)
        for (org.hl7.fhir.r4.model.Identifier ident : fhirPath.evaluate(
                resource, typeName + ".identifier", org.hl7.fhir.r4.model.Identifier.class)) {
            if (ident.hasSystem() && ident.hasValue()) {
                e.identifier(ident.getSystem(), ident.getValue());
            }
        }
        // canonical identity: the url element
        R4TypeConfig cfg = types.get(typeName);
        if (cfg != null && cfg.identityClass() == cloud.jengu.dbo.core.api.IdentityClass.CANONICAL) {
            for (IPrimitiveType<?> url : fhirPath.evaluate(resource, typeName + ".url", IPrimitiveType.class)) {
                e.identifier(Identifier.CANONICAL_SYSTEM, url.getValueAsString());
                e.value("url", EnvelopeValue.of(url.getValueAsString()));
            }
        }
        return e;
    }

    private void addToken(Envelope e, String path, IBase hit) {
        switch (hit) {
            case Coding c -> tokenPair(e, path, c.getSystem(), c.getCode());
            case CodeableConcept cc -> cc.getCoding().forEach(c -> tokenPair(e, path, c.getSystem(), c.getCode()));
            case org.hl7.fhir.r4.model.Identifier id -> tokenPair(e, path, id.getSystem(), id.getValue());
            case IPrimitiveType<?> p -> tokenPair(e, path, null, p.getValueAsString());
            default -> { }
        }
    }

    private void tokenPair(Envelope e, String path, String system, String code) {
        if (code == null) {
            return;
        }
        if (system != null) {
            e.value(path, EnvelopeValue.token(system, code));
        }
        e.value(path, new EnvelopeValue.Token(null, code)); // bare-code form
    }

    private void addDate(Envelope e, String path, IBase hit) {
        Date value = switch (hit) {
            case org.hl7.fhir.r4.model.BaseDateTimeType d -> d.getValue();
            case Period p -> p.getStart();
            default -> null;
        };
        if (value != null) {
            e.value(path, EnvelopeValue.of(value.toInstant()));
        }
    }

    // --------------------------------------------------------------- search

    /**
     * FHIR query parameters → engine criteria. Strict: any parameter that is
     * not a supported search parameter of the type is rejected.
     */
    public Criteria compileSearch(String typeName, Map<String, String> params) {
        requireType(typeName);
        Criteria criteria = Criteria.of(typeName);
        Map<String, RuntimeSearchParam> known = new LinkedHashMap<>();
        for (RuntimeSearchParam sp : searchParams(typeName)) {
            known.put(sp.getName(), sp);
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            String name = p.getKey();
            String value = p.getValue();
            switch (name) {
                case "_count" -> criteria.limit(Integer.parseInt(value));
                case "_sort" -> {
                    boolean descending = value.startsWith("-");
                    String sortParam = descending ? value.substring(1) : value;
                    RuntimeSearchParam sp = known.get(sortParam);
                    if (sp == null) {
                        throw new UnknownSearchParameterException(typeName, "_sort=" + value);
                    }
                    criteria.sortBy(pathName(sortParam), sortKind(sp), !descending);
                }
                default -> {
                    RuntimeSearchParam sp = known.get(name);
                    if (sp == null) {
                        throw new UnknownSearchParameterException(typeName, name);
                    }
                    compileParam(criteria, typeName, sp, value);
                }
            }
        }
        return criteria;
    }

    private void compileParam(Criteria criteria, String typeName, RuntimeSearchParam sp, String value) {
        String path = pathName(sp.getName());
        switch (sp.getParamType()) {
            case TOKEN -> {
                int pipe = value.indexOf('|');
                if (pipe >= 0) {
                    criteria.eq(path, EnvelopeValue.token(value.substring(0, pipe), value.substring(pipe + 1)));
                } else {
                    criteria.eq(path, new EnvelopeValue.Token(null, value));
                }
            }
            case STRING -> criteria.eq(path, EnvelopeValue.of(value.toLowerCase()));
            case NUMBER -> criteria.eq(path, EnvelopeValue.of(new BigDecimal(value)));
            case REFERENCE -> {
                int slash = value.indexOf('/');
                if (slash < 0) {
                    throw new UnknownSearchParameterException(typeName,
                            sp.getName() + "=" + value + " (typed reference Type/id required)");
                }
                criteria.referencing(path, value.substring(0, slash), value.substring(slash + 1));
            }
            default -> throw new UnknownSearchParameterException(typeName,
                    sp.getName() + " (" + sp.getParamType() + " not supported in this slice)");
        }
    }

    private ValueKind sortKind(RuntimeSearchParam sp) {
        return switch (sp.getParamType()) {
            case DATE -> ValueKind.DATE;
            case NUMBER -> ValueKind.NUMBER;
            case TOKEN -> ValueKind.TOKEN;
            default -> ValueKind.STRING;
        };
    }

    // ----------------------------------------------------------- validation

    /** ERROR/FATAL issue lines; empty = valid. */
    public List<String> validate(String resourceJson) {
        return withTccl(() -> {
            IBaseResource resource = ctx().newJsonParser().parseResource(resourceJson);
            ValidationResult result = validator().validateWithResult(resource);
            return result.getMessages().stream()
                    .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                            || m.getSeverity() == ResultSeverityEnum.FATAL)
                    .map(m -> m.getSeverity() + " " + m.getLocationString() + ": " + m.getMessage())
                    .toList();
        });
    }

    // ------------------------------------------------------- bundle framing

    /**
     * A searchset Bundle over one page; when the chunk continues, link[next]
     * carries the opaque keyset cursor as {@code _cursor}.
     */
    public String toSearchBundle(FeedChunk<StoredObject> chunk, String baseUrl, String typeName,
            Map<String, String> originalParams) {
        return withTccl(() -> {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            for (StoredObject o : chunk.items()) {
                Resource resource = (Resource) ctx().newJsonParser()
                        .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                resource.setId(o.id());
                resource.getMeta().setVersionId(Long.toString(o.versionId()));
                bundle.addEntry().setResource(resource)
                        .setFullUrl(baseUrl + "/" + typeName + "/" + o.id());
            }
            if (!chunk.drained() && chunk.nextCursor() != null) {
                StringBuilder qs = new StringBuilder();
                originalParams.forEach((k, v) -> qs.append(qs.isEmpty() ? "" : "&").append(k).append('=').append(v));
                qs.append(qs.isEmpty() ? "" : "&").append("_cursor=").append(chunk.nextCursor());
                bundle.addLink().setRelation("next")
                        .setUrl(baseUrl + "/" + typeName + "?" + qs);
            }
            return ctx().newJsonParser().encodeResourceToString(bundle);
        });
    }

    /** The resource type of a raw resource JSON (for generic write endpoints). */
    public String resourceTypeOf(String resourceJson) {
        return withTccl(() -> ctx().newJsonParser().parseResource(resourceJson)
                .fhirType());
    }

    /** The canonical url of a canonical resource JSON. */
    public String canonicalUrlOf(String resourceJson) {
        return withTccl(() -> {
            IBaseResource resource = ctx().newJsonParser().parseResource(resourceJson);
            List<IPrimitiveType> urls = ctx().newFhirPath()
                    .evaluate(resource, resource.fhirType() + ".url", IPrimitiveType.class);
            if (urls.isEmpty()) {
                throw new IllegalArgumentException("canonical resource without url");
            }
            return urls.get(0).getValueAsString();
        });
    }

    // -------------------------------------------------------------- plumbing

    private List<RuntimeSearchParam> searchParams(String typeName) {
        RuntimeResourceDefinition def = ctx().getResourceDefinition(requireType(typeName));
        return def.getSearchParams().stream()
                .filter(sp -> sp.getPath() != null && !sp.getName().startsWith("_"))
                .toList();
    }

    private String requireType(String typeName) {
        if (!types.containsKey(typeName)) {
            throw new IllegalArgumentException("type not configured in R4 personality: " + typeName);
        }
        return typeName;
    }

    /** Envelope path names: search-param names with '-' folded to '_' (engine path charset). */
    private static String pathName(String searchParamName) {
        return searchParamName.replace('-', '_');
    }

    private synchronized FhirContext ctx() {
        if (ctx == null) {
            ctx = withTccl(FhirContext::forR4);
        }
        return ctx;
    }

    private synchronized FhirValidator validator() {
        if (validator == null) {
            validator = withTccl(() -> {
                FhirContext c = ctx();
                ValidationSupportChain chain = new ValidationSupportChain(
                        new DefaultProfileValidationSupport(c),
                        new InMemoryTerminologyServerValidationSupport(c),
                        new CommonCodeSystemsTerminologyService(c));
                FhirValidator v = c.newValidator();
                v.registerValidatorModule(new FhirInstanceValidator(chain));
                return v;
            });
        }
        return validator;
    }

    /** HAPI landmine #2 from the spike: service discovery is TCCL-based; pin ours. */
    private <T> T withTccl(Supplier<T> body) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(R4Personality.class.getClassLoader());
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }
}
