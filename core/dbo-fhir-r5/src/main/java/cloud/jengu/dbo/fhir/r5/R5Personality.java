package cloud.jengu.dbo.fhir.r5;

import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.UnknownSearchParameterException;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.DateKeys;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
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
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Resource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The R5 personality (REQ-DBO-VER-PERSONALITY-OWNS-MEANING): envelope
 * extraction driven by HAPI's built-in R5 search-parameter definitions,
 * strict search compilation to engine {@link Criteria}, validation on write,
 * and Bundle framing over the feed primitive. The public surface speaks JSON
 * strings and core api types only — no HAPI type crosses (§7.3).
 */
public final class R5Personality {

    public static final String DOMAIN = "r5";

    /**
     * The engine-level search dimensions this personality accepts, beyond the
     * type's own parameters. Read by the search compiler and the capability
     * statement alike (#52).
     */
    static final java.util.Map<String, String> META_SEARCH_PARAMS =
            new java.util.LinkedHashMap<>(java.util.Map.of(
                    "_tag", "token", "_profile", "uri",
                    "_lastUpdated", "date", "_id", "token"));

    /** The only wire format this personality renders — and so the only one declared. */
    static final String RENDERED_FORMAT = "application/fhir+json";

    /** The payload schema version this personality writes. */
    public static final String PAYLOAD_VERSION = "5.0";

    private final Map<String, FhirTypeConfig> types = new LinkedHashMap<>();
    private volatile FhirContext ctx;
    private volatile FhirValidator validator;

    /**
     * What this personality provides to the engine — the inward
     * contract, as against the outward facade a server calls.
     */
    public cloud.jengu.dbo.core.face.DomainFace face() {
        return cloud.jengu.dbo.fhir.common.FhirFace.of("r5");
    }

    public R5Personality(List<FhirTypeConfig> typeConfigs) {
        for (FhirTypeConfig t : typeConfigs) {
            types.put(t.typeName(), t);
        }
    }

    // -------------------------------------------------------- registrations

    /** The types this personality is configured for — what an operation applies to. */
    public java.util.Set<String> configuredTypes() {
        return java.util.Set.copyOf(types.keySet());
    }

    public List<TypeRegistration> registrations() {
        return registrations(DOMAIN);
    }

    /** Registrations over an explicit domain — the re-binding seam for version transitions. */
    public List<TypeRegistration> registrations(String domain) {
        List<TypeRegistration> out = new ArrayList<>();
        for (FhirTypeConfig t : types.values()) {
            EnvelopeExtractor extractor = (typeName, payload) -> withTccl(() -> extract(typeName, payload));
            List<IndexSpec> indexes = defaultIndexes(t.typeName());
            out.add(new TypeRegistration(t.typeName(), domain, t.identityClass(),
                    t.identitySystems(), t.handling(), extractor, indexes, PAYLOAD_VERSION));
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
                            // FHIR string search is case-insensitive starts-with: the
                            // base path stores lowercase; :exact matches the _xct path
                            e.value(path, EnvelopeValue.of(p.getValueAsString().toLowerCase()));
                            e.value(path + "_xct", EnvelopeValue.of(p.getValueAsString()));
                        }
                    }
                    case URI -> {
                        if (hit instanceof IPrimitiveType<?> p && p.getValueAsString() != null) {
                            e.value(path, EnvelopeValue.of(p.getValueAsString())); // uris are case-sensitive
                        }
                    }
                    case DATE -> addDate(e, path, hit);
                    case NUMBER -> {
                        if (hit instanceof IPrimitiveType<?> p && p.getValueAsString() != null) {
                            e.value(path, EnvelopeValue.of(new BigDecimal(p.getValueAsString())));
                        }
                    }
                    case REFERENCE -> {
                        if (hit instanceof Reference r) {
                            if (r.getReferenceElement().hasResourceType()) {
                                e.reference(path, r.getReferenceElement().getResourceType(),
                                        r.getReferenceElement().getIdPart());
                            }
                            if (r.hasIdentifier() && r.getIdentifier().hasSystem()) {
                                // logical reference: the :identifier modifier's target
                                tokenPair(e, path + "_identifier",
                                        r.getIdentifier().getSystem(), r.getIdentifier().getValue());
                            }
                        }
                    }
                    default -> { /* composite/quantity/special: later slices */ }
                }
            }
        }

        // every Identifier element becomes an engine identifier (searchable;
        // identity-bearing per the type's designated systems)
        for (org.hl7.fhir.r5.model.Identifier ident : fhirPath.evaluate(
                resource, typeName + ".identifier", org.hl7.fhir.r5.model.Identifier.class)) {
            if (ident.hasSystem() && ident.hasValue()) {
                e.identifier(ident.getSystem(), ident.getValue());
            }
        }
        // engine-level meta search dimensions: _tag (tokens), _profile (uris)
        if (resource instanceof Resource r4res && r4res.hasMeta()) {
            for (Coding tag : r4res.getMeta().getTag()) {
                tokenPair(e, "_tag", tag.getSystem(), tag.getCode());
            }
            for (IPrimitiveType<String> profile : r4res.getMeta().getProfile()) {
                e.value("_profile", EnvelopeValue.of(profile.getValueAsString()));
            }
        }

        // canonical identity: the url element
        FhirTypeConfig cfg = types.get(typeName);
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
            case org.hl7.fhir.r5.model.Identifier id -> tokenPair(e, path, id.getSystem(), id.getValue());
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
            e.value(path, new EnvelopeValue.Token(system, null)); // system-only ("sys|") form
        }
        e.value(path, new EnvelopeValue.Token(null, code)); // bare-code form
    }

    private void addDate(Envelope e, String path, IBase hit) {
        Date value = switch (hit) {
            case org.hl7.fhir.r5.model.BaseDateTimeType d -> d.getValue();
            case Period p -> p.getStart();
            default -> null;
        };
        if (value != null) {
            e.value(path, EnvelopeValue.of(value.toInstant()));
        }
    }

    // --------------------------------------------------------------- search

    /** The compiled form of a FHIR query: engine criteria plus result-shaping directives. */
    public record CompiledSearch(
            Criteria criteria,
            boolean countOnly,
            List<String> elements,
            List<String> includeRefParams,
            String byId) {}

    /**
     * FHIR query parameters → engine criteria + directives. Strict
     * (REQ-DBO-SRCH-STRICT-BY-DEFAULT): anything unsupported is rejected.
     */
    public CompiledSearch compileSearch(String typeName, Map<String, String> params) {
        requireType(typeName);
        Criteria criteria = Criteria.of(typeName);
        boolean countOnly = false;
        List<String> elements = null;
        List<String> includes = new ArrayList<>();
        String byId = null;

        Map<String, RuntimeSearchParam> known = new LinkedHashMap<>();
        for (RuntimeSearchParam sp : searchParams(typeName)) {
            known.put(sp.getName(), sp);
        }

        for (Map.Entry<String, String> p : params.entrySet()) {
            String name = p.getKey();
            String value = p.getValue();
            switch (name) {
                case "_count" -> criteria.limit(Integer.parseInt(value));
                case "_sort" -> compileSort(criteria, typeName, known, value);
                case "_summary" -> {
                    if (!"count".equals(value)) {
                        throw new UnknownSearchParameterException(typeName, "_summary=" + value);
                    }
                    countOnly = true;
                }
                case "_elements" -> elements = List.of(value.split(","));
                case "_include" -> includes.add(compileInclude(typeName, known, value));
                case "_id" -> byId = value;
                case "_lastUpdated" -> criteria.lastUpdated(prefixOp(typeName, name, value),
                        java.time.Instant.parse(stripPrefix(value)));
                case "_tag" -> compileToken(criteria, "_tag", value, false);
                case "_tag:not" -> compileToken(criteria, "_tag", value, true);
                case "_profile" -> criteria.eq("_profile", EnvelopeValue.of(value));
                case "_offset" -> throw new UnknownSearchParameterException(typeName,
                        "_offset (DBO paginates by cursor: follow Bundle.link[next])");
                default -> compileNamed(criteria, typeName, known, name, value);
            }
        }
        return new CompiledSearch(criteria, countOnly, elements, includes, byId);
    }

    private void compileSort(Criteria criteria, String typeName,
            Map<String, RuntimeSearchParam> known, String value) {
        boolean descending = value.startsWith("-");
        String sortParam = descending ? value.substring(1) : value;
        if ("_lastUpdated".equals(sortParam)) {
            criteria.sortByLastUpdated(!descending);
            return;
        }
        RuntimeSearchParam sp = known.get(sortParam);
        if (sp == null) {
            throw new UnknownSearchParameterException(typeName, "_sort=" + value);
        }
        criteria.sortBy(pathName(sortParam), sortKind(sp), !descending);
    }

    private String compileInclude(String typeName, Map<String, RuntimeSearchParam> known, String value) {
        String[] parts = value.split(":");
        if (parts.length != 2 || !typeName.equals(parts[0])) {
            throw new UnknownSearchParameterException(typeName, "_include=" + value);
        }
        RuntimeSearchParam sp = known.get(parts[1]);
        if (sp == null || sp.getParamType() != ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.REFERENCE) {
            throw new UnknownSearchParameterException(typeName, "_include=" + value);
        }
        return parts[1];
    }

    private void compileNamed(Criteria criteria, String typeName,
            Map<String, RuntimeSearchParam> known, String name, String value) {
        // one-level chain: refParam.targetParam
        int dot = name.indexOf('.');
        if (dot > 0) {
            compileChain(criteria, typeName, known, name.substring(0, dot), name.substring(dot + 1), value);
            return;
        }
        // modifier: name:modifier
        int colon = name.indexOf(':');
        String base = colon > 0 ? name.substring(0, colon) : name;
        String modifier = colon > 0 ? name.substring(colon + 1) : null;
        RuntimeSearchParam sp = known.get(base);
        if (sp == null) {
            throw new UnknownSearchParameterException(typeName, name);
        }
        String path = pathName(base);

        if (modifier != null) {
            switch (modifier) {
                case "missing" -> {
                    boolean isMissing = Boolean.parseBoolean(value);
                    if (sp.getParamType() == ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.REFERENCE) {
                        criteria.refMissing(path, isMissing);
                    } else {
                        criteria.missing(path, isMissing);
                    }
                }
                case "exact" -> {
                    requireParamType(typeName, sp, ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.STRING, name);
                    criteria.eq(path + "_xct", EnvelopeValue.of(value));
                }
                case "not" -> {
                    requireParamType(typeName, sp, ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.TOKEN, name);
                    compileToken(criteria, path, value, true);
                }
                case "identifier" -> {
                    requireParamType(typeName, sp, ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.REFERENCE, name);
                    compileToken(criteria, path + "_identifier", value, false);
                }
                default -> throw new UnknownSearchParameterException(typeName, name);
            }
            return;
        }

        switch (sp.getParamType()) {
            case TOKEN -> compileToken(criteria, path, value, false);
            case STRING -> criteria.startsWith(path, value.toLowerCase());
            case URI -> criteria.eq(path, EnvelopeValue.of(value));
            case NUMBER -> {
                Criteria.RangeOp op = tryPrefixOp(value);
                if (op != null) {
                    criteria.range(path, ValueKind.NUMBER, op, stripPrefix(value));
                } else {
                    criteria.eq(path, EnvelopeValue.of(new BigDecimal(value)));
                }
            }
            case DATE -> {
                Criteria.RangeOp op = tryPrefixOp(value);
                if (op == null) {
                    throw new UnknownSearchParameterException(typeName,
                            base + "=" + value + " (date search requires a gt/lt/ge/le prefix in this slice)");
                }
                criteria.range(path, ValueKind.DATE, op, DateKeys.ofSearchValue(stripPrefix(value)));
            }
            case REFERENCE -> {
                int slash = value.indexOf('/');
                if (slash < 0) {
                    throw new UnknownSearchParameterException(typeName,
                            base + "=" + value + " (typed reference Type/id required)");
                }
                criteria.referencing(path, value.substring(0, slash), value.substring(slash + 1));
            }
            default -> throw new UnknownSearchParameterException(typeName,
                    base + " (" + sp.getParamType() + " not supported in tier 1)");
        }
    }

    private void compileChain(Criteria criteria, String typeName,
            Map<String, RuntimeSearchParam> known, String refName, String targetParam, String value) {
        RuntimeSearchParam refSp = known.get(refName);
        if (refSp == null || refSp.getParamType() != ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum.REFERENCE) {
            throw new UnknownSearchParameterException(typeName, refName + "." + targetParam);
        }
        List<String> targets = refSp.getTargets().stream().sorted().toList();
        if (targets.size() != 1) {
            throw new UnknownSearchParameterException(typeName,
                    refName + "." + targetParam + " (ambiguous chain target: " + targets + ")");
        }
        String targetType = targets.get(0);
        String refPath = pathName(refName);

        if ("identifier".equals(targetParam)) {
            int pipe = value.indexOf('|');
            Criteria.ChainTarget.ByIdentifier target;
            if (pipe < 0) {
                target = new Criteria.ChainTarget.ByIdentifier(null, value);
            } else if (pipe == value.length() - 1) {
                target = new Criteria.ChainTarget.ByIdentifier(value.substring(0, pipe), null);
            } else {
                target = new Criteria.ChainTarget.ByIdentifier(
                        value.substring(0, pipe), value.substring(pipe + 1));
            }
            criteria.chained(refPath, targetType, target);
            return;
        }
        // chain into the target's envelope: token or string equality
        requireType(targetType);
        RuntimeSearchParam targetSp = searchParams(targetType).stream()
                .filter(x -> x.getName().equals(targetParam)).findFirst()
                .orElseThrow(() -> new UnknownSearchParameterException(typeName, refName + "." + targetParam));
        EnvelopeValue targetValue = switch (targetSp.getParamType()) {
            case TOKEN -> {
                int pipe = value.indexOf('|');
                yield pipe >= 0
                        ? EnvelopeValue.token(value.substring(0, pipe), value.substring(pipe + 1))
                        : new EnvelopeValue.Token(null, value);
            }
            case STRING -> EnvelopeValue.of(value.toLowerCase());
            default -> throw new UnknownSearchParameterException(typeName,
                    refName + "." + targetParam + " (" + targetSp.getParamType() + " chain not supported)");
        };
        criteria.chained(refPath, targetType,
                new Criteria.ChainTarget.ByEq(pathName(targetParam), targetValue));
    }

    private void compileToken(Criteria criteria, String path, String value, boolean negate) {
        EnvelopeValue token;
        int pipe = value.indexOf('|');
        if (pipe < 0) {
            token = new EnvelopeValue.Token(null, value);
        } else if (pipe == value.length() - 1) {
            token = new EnvelopeValue.Token(value.substring(0, pipe), null); // sys| any-value form
        } else {
            token = EnvelopeValue.token(value.substring(0, pipe), value.substring(pipe + 1));
        }
        if (negate) {
            criteria.notEq(path, token);
        } else {
            criteria.eq(path, token);
        }
    }

    private void requireParamType(String typeName, RuntimeSearchParam sp,
            ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum expected, String display) {
        if (sp.getParamType() != expected) {
            throw new UnknownSearchParameterException(typeName, display);
        }
    }

    private Criteria.RangeOp prefixOp(String typeName, String name, String value) {
        Criteria.RangeOp op = tryPrefixOp(value);
        if (op == null) {
            throw new UnknownSearchParameterException(typeName,
                    name + "=" + value + " (gt/lt/ge/le prefix required)");
        }
        return op;
    }

    private static Criteria.RangeOp tryPrefixOp(String value) {
        if (value.length() < 2) {
            return null;
        }
        return switch (value.substring(0, 2)) {
            case "gt" -> Criteria.RangeOp.GT;
            case "lt" -> Criteria.RangeOp.LT;
            case "ge" -> Criteria.RangeOp.GE;
            case "le" -> Criteria.RangeOp.LE;
            default -> null;
        };
    }

    private static String stripPrefix(String value) {
        return value.substring(2);
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

    /** See {@code R4Personality} — the same guard, the same busy machine. */
    private static final String REGEX_TIMED_OUT = "Regex evaluation timed out";

    /** ERROR/FATAL issue lines; empty = valid. */
    public List<String> validate(String resourceJson) {
        return withTccl(() -> issuesFrom(validated(parse(resourceJson))));
    }

    /**
     * The same validation, rendered as the domain's own OperationOutcome (#48).
     *
     * <p>One call, two renderings — deliberately. A caller asking whether a
     * resource would be accepted, and the write that accepts or refuses it,
     * must not be able to disagree, so they share the ValidationResult rather
     * than each running the validator their own way.
     */
    public String validationOutcome(String resourceJson) {
        return withTccl(() -> {
            IBaseResource resource = parse(resourceJson);
            ValidationResult result = validated(resource);
            return ctx().newJsonParser().encodeResourceToString(
                    (IBaseResource) result.toOperationOutcome());
        });
    }

    /**
     * The write path's validation, with the timeout policy in one place.
     *
     * <p>Runs under a TCCL the caller has already pinned. The retry and the
     * refusal to answer live here rather than in each entry point, because a
     * second entry point that forgot them would answer "invalid" on a busy
     * machine — which is the bug #42 was.
     */
    private ValidationResult validated(IBaseResource resource) {
        ValidationResult result = validator().validateWithResult(resource);
        if (issuesFrom(result).stream().anyMatch(i -> i.contains(REGEX_TIMED_OUT))) {
            // A regex that ran out of wall clock found nothing; asking again is
            // the only answer that is not a guess.
            result = validator().validateWithResult(resource);
        }
        List<String> issues = issuesFrom(result);
        if (issues.stream().anyMatch(i -> i.contains(REGEX_TIMED_OUT))) {
            // Twice is not a busy moment, and it is still not a finding.
            throw new cloud.jengu.dbo.fhir.common.ValidationUnavailableException(
                    resource.fhirType(),
                    issues.stream().filter(i -> i.contains(REGEX_TIMED_OUT))
                            .findFirst().orElse(REGEX_TIMED_OUT));
        }
        return result;
    }

    private static List<String> issuesFrom(ValidationResult result) {
        return result.getMessages().stream()
                .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                        || m.getSeverity() == ResultSeverityEnum.FATAL)
                .map(m -> m.getSeverity() + " " + m.getLocationString() + ": " + m.getMessage())
                .toList();
    }

    /**
     * One stored object rendered as the FHIR resource a client expects:
     * the stored payload with its envelope {@code id} and
     * {@code meta.versionId} put back.
     *
     * <p>The payload is the truth and does not carry them — a create without
     * an id in the body is stored exactly as sent, which is the point. But a
     * resource served without {@code Resource.id} is not a FHIR resource a
     * client can use: it cannot be referenced, re-read, or matched to what
     * the search that found it returned. The bundle paths have always put
     * both back; read did not.
     */
    /**
     * The face's answer to "give me one line somebody else's tools can read"
     * — a FHIR resource carrying its own id and version,
     * which is what FHIR Bulk Data is.
     *
     * <p>The same projection {@link #toResourceJson} performs for every read.
     * A portable export shows a reader what a client would see, not a shape
     * invented for archives.
     */
    public cloud.jengu.dbo.core.face.PortableRendering portableRendering() {
        return (payload, id, versionId) -> withTccl(() -> {
            org.hl7.fhir.r5.model.Resource resource = (org.hl7.fhir.r5.model.Resource)
                    ctx().newJsonParser()
                            .parseResource(new String(payload, StandardCharsets.UTF_8));
            resource.setId(id);
            resource.getMeta().setVersionId(Long.toString(versionId));
            return ctx().newJsonParser().encodeResourceToString(resource);
        });
    }

    /**
     * Serving and export ask the same question — what does this stored payload
     * look like with the engine's own facts put back — so they get the same
     * answer from one place. Two copies of this body drifted apart the moment
     * either learned a fact the other did not.
     */
    public String toResourceJson(StoredObject stored) {
        return portableRendering().render(stored.payload(), stored.id(), stored.versionId());
    }

    // ------------------------------------------------------- bundle framing

    /**
     * A searchset Bundle over one page; when the chunk continues, link[next]
     * carries the opaque keyset cursor as {@code _cursor}. Included resources
     * ride with {@code search.mode=include}; {@code _elements} trims encoded
     * entry resources (id and meta always kept).
     */
    public String toSearchBundle(FeedChunk<StoredObject> chunk, String baseUrl, String typeName,
            Map<String, String> originalParams, List<StoredObject> includedTargets,
            List<String> elements) {
        return withTccl(() -> {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            for (StoredObject o : chunk.items()) {
                Resource resource = (Resource) ctx().newJsonParser()
                        .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                resource.setId(o.id());
                resource.getMeta().setVersionId(Long.toString(o.versionId()));
                bundle.addEntry().setResource(resource)
                        .setFullUrl(baseUrl + "/" + typeName + "/" + o.id())
                        .getSearch().setMode(Bundle.SearchEntryMode.MATCH);
            }
            if (includedTargets != null) {
                for (StoredObject o : includedTargets) {
                    Resource resource = (Resource) ctx().newJsonParser()
                            .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                    resource.setId(o.id());
                    bundle.addEntry().setResource(resource)
                            .setFullUrl(baseUrl + "/" + o.typeName() + "/" + o.id())
                            .getSearch().setMode(Bundle.SearchEntryMode.INCLUDE);
                }
            }
            // The self link is not decoration: the specification requires a
            // search result to say which query produced it, and a client
            // holding a Bundle with only link[next] cannot repeat, cache or
            // cite the search it just ran.
            StringBuilder self = new StringBuilder();
            originalParams.forEach((k, v) ->
                    self.append(self.isEmpty() ? "" : "&").append(k).append('=').append(v));
            bundle.addLink().setRelation(org.hl7.fhir.r5.model.Bundle.LinkRelationTypes.SELF)
                    .setUrl(baseUrl + "/" + typeName + (self.isEmpty() ? "" : "?" + self));
            if (!chunk.drained() && chunk.nextCursor() != null) {
                StringBuilder qs = new StringBuilder();
                originalParams.forEach((k, v) -> qs.append(qs.isEmpty() ? "" : "&").append(k).append('=').append(v));
                qs.append(qs.isEmpty() ? "" : "&").append("_cursor=").append(chunk.nextCursor());
                bundle.addLink().setRelation(org.hl7.fhir.r5.model.Bundle.LinkRelationTypes.NEXT)
                        .setUrl(baseUrl + "/" + typeName + "?" + qs);
            }
            var parser = ctx().newJsonParser();
            if (elements != null) {
                java.util.Set<String> encode = new java.util.LinkedHashSet<>();
                encode.add(typeName + ".id");
                encode.add(typeName + ".meta");
                for (String el : elements) {
                    encode.add(typeName + "." + el.trim());
                }
                parser.setEncodeElements(encode);
                parser.setEncodeElementsAppliesToChildResourcesOnly(true);
            }
            return parser.encodeResourceToString(bundle);
        });
    }

    /** A count-only searchset Bundle ({@code _summary=count}). */
    public String countBundle(long total) {
        return withTccl(() -> {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            bundle.setTotal((int) total);
            return ctx().newJsonParser().encodeResourceToString(bundle);
        });
    }

    /** (targetType, targetId) pairs referenced by the resource at a reference search param. */
    public List<String[]> referencedTargets(String resourceJson, String refParamName) {
        return withTccl(() -> {
            IBaseResource resource = ctx().newJsonParser().parseResource(resourceJson);
            RuntimeSearchParam sp = ctx().getResourceDefinition(resource.fhirType())
                    .getSearchParam(refParamName);
            if (sp == null) {
                return List.of();
            }
            List<String[]> out = new ArrayList<>();
            for (Reference r : ctx().newFhirPath().evaluate(resource, sp.getPath(), Reference.class)) {
                if (r.getReferenceElement().hasResourceType()) {
                    out.add(new String[] {r.getReferenceElement().getResourceType(),
                            r.getReferenceElement().getIdPart()});
                }
            }
            return out;
        });
    }


    /** History Bundle (type=history), oldest first. */
    public String toHistoryBundle(java.util.List<StoredObject> versions, String baseUrl, String typeName) {
        return withTccl(() -> {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.HISTORY);
            bundle.setTotal(versions.size());
            for (StoredObject o : versions) {
                Resource resource = (Resource) ctx().newJsonParser()
                        .parseResource(new String(o.payload(), StandardCharsets.UTF_8));
                resource.setId(o.id());
                resource.getMeta().setVersionId(Long.toString(o.versionId()));
                bundle.addEntry().setResource(resource)
                        .setFullUrl(baseUrl + "/" + typeName + "/" + o.id());
            }
            return ctx().newJsonParser().encodeResourceToString(bundle);
        });
    }

    /**
     * REQ-DBO-SRCH-HONEST-CAPABILITY: generated from the configured types and
     * their actually-supported search parameters — never hand-maintained.
     */
    public String capabilityStatement(String baseUrl) {
        return capabilityStatement(baseUrl, java.util.List.of());
    }

    /**
     * As above, declaring exactly the operations that were registered (#51).
     *
     * <p>The list is given rather than inferred: this personality cannot see
     * which facades the serving layer wired, and a generator that guessed would
     * announce an operation nobody answers — which a client would then call.
     */
    public String capabilityStatement(String baseUrl,
            java.util.Collection<cloud.jengu.dbo.fhir.common.FhirOperation> served) {
        return withTccl(() -> {
            var cs = new org.hl7.fhir.r5.model.CapabilityStatement();
            cs.setStatus(org.hl7.fhir.r5.model.Enumerations.PublicationStatus.ACTIVE);
            cs.setKind(org.hl7.fhir.r5.model.Enumerations.CapabilityStatementKind.INSTANCE);
            cs.setDate(new java.util.Date());
            cs.setFhirVersion(org.hl7.fhir.r5.model.Enumerations.FHIRVersion
                    .fromCode(ctx().getVersion().getVersion().getFhirVersionString()));
            cs.addFormat(RENDERED_FORMAT); // what it renders, not a wish
            var rest = cs.addRest();
            rest.setMode(org.hl7.fhir.r5.model.CapabilityStatement.RestfulCapabilityMode.SERVER);
            for (String typeName : types.keySet()) {
                var resource = rest.addResource();
                resource.setType(typeName);
                // #52: derived from what the type DECLARES, not a list applied to
                // every type. The engine already refuses a tenant write to a
                // replicated type and any change to an append-only one; a
                // statement announcing create for those told a client something
                // the store answers with 403.
                FhirTypeConfig config = types.get(typeName);
                Handling handling = config.handling();
                boolean writable = handling.isWritableBy(Handling.Authority.TENANT_USERS);
                boolean mayChange = handling.mutability() == Handling.Mutability.FULL
                        || handling.mutability() == Handling.Mutability.REPLACE_IN_PLACE;
                boolean keepsHistory = handling.durability() == Handling.Durability.VERSIONED;

                java.util.List<String> interactions = new ArrayList<>(
                        java.util.List.of("read", "search-type"));
                if (writable) {
                    interactions.add("create");
                    if (mayChange) {
                        interactions.add("update");
                        interactions.add("delete");
                    }
                }
                if (keepsHistory) {
                    interactions.add("history-instance");
                    interactions.add("vread");
                }
                for (var interaction : interactions) {
                    resource.addInteraction().setCode(
                            org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction
                                    .fromCode(interaction));
                }

                // A conditional write must be keyed on the type's own identity
                // (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS), so a store-assigned
                // id has nothing to key on and the store refuses one.
                resource.setConditionalCreate(
                        writable && config.identityClass() != IdentityClass.INTERNAL);

                // History is the durability declaration, said in FHIR's words.
                // Not VERSIONED_UPDATE: If-Match is honoured, never required.
                resource.setVersioning(keepsHistory
                        ? org.hl7.fhir.r5.model.CapabilityStatement.ResourceVersionPolicy.VERSIONED
                        : org.hl7.fhir.r5.model.CapabilityStatement.ResourceVersionPolicy.NOVERSION);
                resource.setReadHistory(keepsHistory);
                for (RuntimeSearchParam sp : searchParams(typeName)) {
                    var supported = switch (sp.getParamType()) {
                        case TOKEN, STRING, DATE, NUMBER, REFERENCE, URI -> true;
                        default -> false;
                    };
                    if (supported) {
                        resource.addSearchParam().setName(sp.getName()).setType(
                                org.hl7.fhir.r5.model.Enumerations.SearchParamType
                                        .fromCode(sp.getParamType().getCode()));
                    }
                }
                // One list, checked against the compiler by CapabilityHonestyIT —
                // these used to be written out here, in the extractor and in the
                // search switch, so a fifth would have updated two of three.
                META_SEARCH_PARAMS.forEach((name, kind) ->
                        resource.addSearchParam().setName(name).setType(
                                org.hl7.fhir.r5.model.Enumerations.SearchParamType.fromCode(kind)));
                // declared because registered, not because remembered (#51)
                for (var operation : served) {
                    if (operation.types().contains(typeName)) {
                        resource.addOperation().setName(operation.name())
                                .setDefinition(operation.definition());
                    }
                }
            }
            return ctx().newJsonParser().encodeResourceToString(cs);
        });
    }

    /** An OperationOutcome document for error responses. */
    public String operationOutcome(String issueCode, String diagnostics) {
        return withTccl(() -> {
            var outcome = new org.hl7.fhir.r5.model.OperationOutcome();
            var issue = outcome.addIssue();
            issue.setSeverity(org.hl7.fhir.r5.model.OperationOutcome.IssueSeverity.ERROR);
            issue.setCode(org.hl7.fhir.r5.model.OperationOutcome.IssueType.fromCode(issueCode));
            issue.setDiagnostics(diagnostics);
            return ctx().newJsonParser().encodeResourceToString(outcome);
        });
    }

    /** True when the type is configured in this personality. */
    public boolean knowsType(String typeName) {
        return types.containsKey(typeName);
    }

    /** The resource type of a raw resource JSON (for generic write endpoints). */
    public String resourceTypeOf(String resourceJson) {
        return withTccl(() -> parse(resourceJson).fhirType());
    }

    /**
     * Parses, and treats a body that is not FHIR as the client's mistake.
     *
     * <p>An unparseable body used to reach the serving surface as whatever
     * the parser threw, land in its catch-all and answer 500 — telling a
     * caller the server broke, when in fact their request was malformed. It
     * is a 400, and the difference matters to whoever is deciding which of
     * the two of you has a bug.
     */
    private IBaseResource parse(String resourceJson) {
        try {
            return ctx().newJsonParser().parseResource(resourceJson);
        } catch (ca.uhn.fhir.parser.DataFormatException e) {
            throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                    + e.getMessage(), e);
        }
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

    /** Same-package internal access; never crosses the public boundary. */
    FhirContext ctxInternal() {
        return ctx();
    }

    private synchronized FhirContext ctx() {
        if (ctx == null) {
            ctx = withTccl(FhirContext::forR5);
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

    /**
     * HAPI landmine: service discovery is TCCL-based; pin the loader that owns
     * the stack. Asked for by way of a HAPI class rather than this one, because
     * the engine lives in a bundle of its own: the ServiceLoader lookup behind
     * CacheFactory only finds its provider from the loader carrying the engine's
     * META-INF/services, and that is no longer this personality's loader.
     */
    private <T> T withTccl(Supplier<T> body) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(FhirContext.class.getClassLoader());
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }
}
