package cloud.jengu.dbo.fhir.r4;

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

    /** The payload schema version this personality writes. */
    public static final String PAYLOAD_VERSION = "4.0";

    private final Map<String, FhirTypeConfig> types = new LinkedHashMap<>();
    private volatile FhirContext ctx;
    private volatile FhirValidator validator;

    /**
     * What this personality provides to the engine — the inward
     * contract, as against the outward facade a server calls.
     */
    public cloud.jengu.dbo.core.face.DomainFace face() {
        return cloud.jengu.dbo.fhir.common.FhirFace.of("r4");
    }

    public R4Personality(List<FhirTypeConfig> typeConfigs) {
        for (FhirTypeConfig t : typeConfigs) {
            types.put(t.typeName(), t);
        }
    }

    // -------------------------------------------------------- registrations

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
        for (org.hl7.fhir.r4.model.Identifier ident : fhirPath.evaluate(
                resource, typeName + ".identifier", org.hl7.fhir.r4.model.Identifier.class)) {
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
            e.value(path, new EnvelopeValue.Token(system, null)); // system-only ("sys|") form
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

    /**
     * A regex check that ran out of wall clock rather than finding anything.
     *
     * <p>HAPI guards every primitive-type regex against catastrophic
     * backtracking by running it on a <b>new single-thread executor</b> with a
     * 500ms budget — a thread per regex, timed by the clock rather than by
     * work done. On a loaded machine the thread may simply not be scheduled in
     * time, and a pattern matched against {@code rest-hook} "times out".
     *
     * <p>Which is not a finding. It says nothing about the resource, and
     * treating it as an error rejects a valid write because a machine was
     * busy.
     */
    private static final String REGEX_TIMED_OUT = "Regex evaluation timed out";

    /** ERROR/FATAL issue lines; empty = valid. */
    public List<String> validate(String resourceJson) {
        return withTccl(() -> {
            IBaseResource resource = ctx().newJsonParser().parseResource(resourceJson);
            List<String> issues = issuesFrom(validator().validateWithResult(resource));
            if (issues.stream().anyMatch(i -> i.contains(REGEX_TIMED_OUT))) {
                // Re-run rather than assume either way. A timeout is not
                // evidence of invalidity, so accepting it would reject a valid
                // resource; but the guard exists because FHIR's own `code`
                // pattern backtracks badly, so ignoring it would wave through
                // exactly what it defends against. Asking again is the only
                // answer that is not a guess — and if it times out twice,
                // something is wrong beyond a busy moment and it is reported.
                issues = issuesFrom(validator().validateWithResult(resource));
            }
            return issues;
        });
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
     * both back; read did not, so the same object had an id in a search hit
     * and none when fetched directly.
     *
     * <p>Costs a parse and a re-serialise on every read. That is the price of
     * payload-is-truth, and it is the same price the bundle framing already
     * pays for every entry.
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
            org.hl7.fhir.r4.model.Resource resource = (org.hl7.fhir.r4.model.Resource)
                    ctx().newJsonParser()
                            .parseResource(new String(payload, StandardCharsets.UTF_8));
            resource.setId(id);
            resource.getMeta().setVersionId(Long.toString(versionId));
            return ctx().newJsonParser().encodeResourceToString(resource);
        });
    }

    public String toResourceJson(StoredObject stored) {
        return withTccl(() -> {
            Resource resource = (Resource) ctx().newJsonParser()
                    .parseResource(new String(stored.payload(), StandardCharsets.UTF_8));
            resource.setId(stored.id());
            resource.getMeta().setVersionId(Long.toString(stored.versionId()));
            return ctx().newJsonParser().encodeResourceToString(resource);
        });
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
            if (!chunk.drained() && chunk.nextCursor() != null) {
                StringBuilder qs = new StringBuilder();
                originalParams.forEach((k, v) -> qs.append(qs.isEmpty() ? "" : "&").append(k).append('=').append(v));
                qs.append(qs.isEmpty() ? "" : "&").append("_cursor=").append(chunk.nextCursor());
                bundle.addLink().setRelation("next")
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
        return withTccl(() -> {
            var cs = new org.hl7.fhir.r4.model.CapabilityStatement();
            cs.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);
            cs.setKind(org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind.INSTANCE);
            cs.setDate(new java.util.Date());
            cs.setFhirVersion(org.hl7.fhir.r4.model.Enumerations.FHIRVersion
                    .fromCode(ctx().getVersion().getVersion().getFhirVersionString()));
            cs.addFormat("application/fhir+json");
            var rest = cs.addRest();
            rest.setMode(org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode.SERVER);
            for (String typeName : types.keySet()) {
                var resource = rest.addResource();
                resource.setType(typeName);
                for (var interaction : java.util.List.of("read", "create", "update", "delete",
                        "search-type", "history-instance")) {
                    resource.addInteraction().setCode(
                            org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction
                                    .fromCode(interaction));
                }
                for (RuntimeSearchParam sp : searchParams(typeName)) {
                    var supported = switch (sp.getParamType()) {
                        case TOKEN, STRING, DATE, NUMBER, REFERENCE, URI -> true;
                        default -> false;
                    };
                    if (supported) {
                        resource.addSearchParam().setName(sp.getName()).setType(
                                org.hl7.fhir.r4.model.Enumerations.SearchParamType
                                        .fromCode(sp.getParamType().getCode()));
                    }
                }
                resource.addSearchParam().setName("_tag").setType(
                        org.hl7.fhir.r4.model.Enumerations.SearchParamType.TOKEN);
                resource.addSearchParam().setName("_profile").setType(
                        org.hl7.fhir.r4.model.Enumerations.SearchParamType.URI);
                resource.addSearchParam().setName("_lastUpdated").setType(
                        org.hl7.fhir.r4.model.Enumerations.SearchParamType.DATE);
                resource.addSearchParam().setName("_id").setType(
                        org.hl7.fhir.r4.model.Enumerations.SearchParamType.TOKEN);
            }
            return ctx().newJsonParser().encodeResourceToString(cs);
        });
    }

    /** An OperationOutcome document for error responses. */
    public String operationOutcome(String issueCode, String diagnostics) {
        return withTccl(() -> {
            var outcome = new org.hl7.fhir.r4.model.OperationOutcome();
            var issue = outcome.addIssue();
            issue.setSeverity(org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity.ERROR);
            issue.setCode(org.hl7.fhir.r4.model.OperationOutcome.IssueType.fromCode(issueCode));
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

    /** Same-package internal access; never crosses the public boundary. */
    FhirContext ctxInternal() {
        return ctx();
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

    /** HAPI landmine: service discovery is TCCL-based; pin ours. */
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
