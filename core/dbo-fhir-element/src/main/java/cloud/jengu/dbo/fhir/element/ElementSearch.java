package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.DateKeys;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.ValueKind;
import cloud.jengu.dbo.fhir.common.UnknownSearchParameterException;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A FHIR query, compiled to engine criteria from the version's own search
 * parameters.
 *
 * <p>What a parameter is called, what it means and what it may point at are all
 * in the definitions, so this holds no version knowledge: it is the same
 * compiler for a version released years ago and one at ballot. What it does
 * hold is the query language — modifiers, prefixes, chains, the shape of
 * {@code _include} — which is FHIR's and the same in every version of it.
 *
 * <p>Strict by default (REQ-DBO-SRCH-STRICT-BY-DEFAULT): a parameter this
 * version does not define, or a modifier that does not apply to its type, is
 * refused rather than quietly ignored, because a search that silently drops a
 * filter answers with more than the caller asked for.
 */
final class ElementSearch {

    private ElementSearch() {
    }

    record Compiled(Criteria criteria, boolean countOnly, List<String> elements,
            List<String> includeRefParams, String byId) {}

    static Compiled compile(ElementVersion version, String typeName, Map<String, String> params) {
        Criteria criteria = Criteria.of(typeName);
        boolean countOnly = false;
        List<String> elements = null;
        List<String> includes = new ArrayList<>();
        String byId = null;

        Map<String, SearchParameter> known = new LinkedHashMap<>();
        for (SearchParameter parameter : version.parametersFor(typeName)) {
            known.put(parameter.getCode(), parameter);
        }

        for (Map.Entry<String, String> param : params.entrySet()) {
            String name = param.getKey();
            String value = param.getValue();
            switch (name) {
                case "_count" -> criteria.limit(cloud.jengu.dbo.fhir.common.ResultParameters
                        .count(value, typeName, 100, 10_000));
                case "_sort" -> sort(criteria, typeName, known, value);
                case "_summary" -> {
                    if (!"count".equals(value)) {
                        throw new UnknownSearchParameterException(typeName, "_summary=" + value);
                    }
                    countOnly = true;
                }
                case "_elements" -> elements = List.of(value.split(","));
                case "_include" -> includes.add(include(typeName, known, value));
                case "_id" -> byId = value;
                case "_lastUpdated" -> lastUpdated(criteria, value);
                case "_tag" -> token(criteria, "_tag", value, false);
                case "_tag:not" -> token(criteria, "_tag", value, true);
                case "_profile" -> criteria.eq("_profile", EnvelopeValue.of(value));
                case "_offset" -> throw new UnknownSearchParameterException(typeName,
                        "_offset (DBO paginates by cursor: follow Bundle.link[next])");
                default -> named(version, criteria, typeName, known, name, value);
            }
        }
        return new Compiled(criteria, countOnly, elements, includes, byId);
    }

    private static void sort(Criteria criteria, String typeName,
            Map<String, SearchParameter> known, String value) {
        // Spelled once, for every surface (#92): what -date means is not this
        // path's to decide differently from the trail's.
        cloud.jengu.dbo.fhir.common.ResultParameters.Sort asked =
                cloud.jengu.dbo.fhir.common.ResultParameters.sort(value, typeName);
        boolean descending = asked.descending();
        String sortParam = asked.field();
        if ("_lastUpdated".equals(sortParam)) {
            criteria.sortByLastUpdated(!descending);
            return;
        }
        SearchParameter parameter = known.get(sortParam);
        if (parameter == null) {
            throw new UnknownSearchParameterException(typeName, "_sort=" + value);
        }
        criteria.sortBy(pathName(sortParam), sortKind(parameter), !descending);
    }

    private static String include(String typeName, Map<String, SearchParameter> known,
            String value) {
        String[] parts = value.split(":");
        if (parts.length != 2 || !typeName.equals(parts[0])) {
            throw new UnknownSearchParameterException(typeName, "_include=" + value);
        }
        SearchParameter parameter = known.get(parts[1]);
        if (parameter == null || parameter.getType() != Enumerations.SearchParamType.REFERENCE) {
            throw new UnknownSearchParameterException(typeName, "_include=" + value);
        }
        return parts[1];
    }

    private static void named(ElementVersion version, Criteria criteria, String typeName,
            Map<String, SearchParameter> known, String name, String value) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            chain(version, criteria, typeName, known,
                    name.substring(0, dot), name.substring(dot + 1), value);
            return;
        }
        int colon = name.indexOf(':');
        String base = colon > 0 ? name.substring(0, colon) : name;
        String modifier = colon > 0 ? name.substring(colon + 1) : null;
        SearchParameter parameter = known.get(base);
        if (parameter == null) {
            throw new UnknownSearchParameterException(typeName, name);
        }
        String path = pathName(base);

        if (modifier != null) {
            switch (modifier) {
                case "missing" -> {
                    boolean isMissing = Boolean.parseBoolean(value);
                    if (parameter.getType() == Enumerations.SearchParamType.REFERENCE) {
                        criteria.refMissing(path, isMissing);
                    } else {
                        criteria.missing(path, isMissing);
                    }
                }
                case "exact" -> {
                    require(typeName, parameter, Enumerations.SearchParamType.STRING, name);
                    criteria.eq(path + "_xct", EnvelopeValue.of(value));
                }
                case "not" -> {
                    require(typeName, parameter, Enumerations.SearchParamType.TOKEN, name);
                    token(criteria, path, value, true);
                }
                case "identifier" -> {
                    require(typeName, parameter, Enumerations.SearchParamType.REFERENCE, name);
                    token(criteria, path + "_identifier", value, false);
                }
                default -> throw new UnknownSearchParameterException(typeName, name);
            }
            return;
        }

        switch (parameter.getType()) {
            case TOKEN -> token(criteria, path, value, false);
            case STRING -> criteria.startsWith(path, value.toLowerCase());
            case URI -> criteria.eq(path, EnvelopeValue.of(value));
            case NUMBER -> {
                Criteria.RangeOp op = tryPrefixOp(value);
                if (op != null) {
                    criteria.range(path, ValueKind.NUMBER, op, stripPrefix(value));
                } else {
                    criteria.eq(path, EnvelopeValue.of(new java.math.BigDecimal(value)));
                }
            }
            case DATE -> date(criteria, path, value);
            case REFERENCE -> {
                int slash = value.indexOf('/');
                if (slash < 0) {
                    throw new UnknownSearchParameterException(typeName,
                            base + "=" + value + " (typed reference Type/id required)");
                }
                criteria.referencing(path, value.substring(0, slash), value.substring(slash + 1));
            }
            default -> throw new UnknownSearchParameterException(typeName,
                    base + " (" + parameter.getType().toCode() + " is not served)");
        }
    }

    private static void chain(ElementVersion version, Criteria criteria, String typeName,
            Map<String, SearchParameter> known, String refName, String targetParam, String value) {
        SearchParameter reference = known.get(refName);
        if (reference == null || reference.getType() != Enumerations.SearchParamType.REFERENCE) {
            throw new UnknownSearchParameterException(typeName, refName + "." + targetParam);
        }
        List<String> targets = reference.getTarget().stream()
                .map(org.hl7.fhir.r5.model.Enumeration::getCode).sorted().toList();
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
        SearchParameter target = version.parametersFor(targetType).stream()
                .filter(p -> p.getCode().equals(targetParam)).findFirst()
                .orElseThrow(() -> new UnknownSearchParameterException(typeName,
                        refName + "." + targetParam));
        EnvelopeValue targetValue = switch (target.getType()) {
            case TOKEN -> {
                int pipe = value.indexOf('|');
                yield pipe >= 0
                        ? EnvelopeValue.token(value.substring(0, pipe), value.substring(pipe + 1))
                        : new EnvelopeValue.Token(null, value);
            }
            case STRING -> EnvelopeValue.of(value.toLowerCase());
            default -> throw new UnknownSearchParameterException(typeName, refName + "."
                    + targetParam + " (" + target.getType().toCode() + " chain is not served)");
        };
        criteria.chained(refPath, targetType,
                new Criteria.ChainTarget.ByEq(pathName(targetParam), targetValue));
    }

    /**
     * A date parameter, at whatever precision the caller wrote it.
     *
     * <p>A FHIR date names a span — {@code 2020} is a year, {@code 2020-01-01}
     * a day — and a prefix says how the caller's span relates to the stored
     * moment. No prefix means {@code eq}, which is the whole span and not its
     * first instant: read as an instant, a bare date matches midnight exactly
     * and so matches nothing that happened during the day it names.
     */
    private static void date(Criteria criteria, String path, String value) {
        Criteria.RangeOp op = tryPrefixOp(value);
        DateKeys.Window window = DateKeys.window(op == null ? value : stripPrefix(value));
        switch (op == null ? Bound.EQ : bound(op)) {
            case EQ -> criteria.range(path, ValueKind.DATE, Criteria.RangeOp.GE,
                            DateKeys.of(window.from()))
                    .range(path, ValueKind.DATE, Criteria.RangeOp.LT,
                            DateKeys.of(window.until()));
            // after the span, not after its start: gt2020 is 2021 onward
            case AFTER -> criteria.range(path, ValueKind.DATE, Criteria.RangeOp.GE,
                    DateKeys.of(window.until()));
            case FROM -> criteria.range(path, ValueKind.DATE, Criteria.RangeOp.GE,
                    DateKeys.of(window.from()));
            case BEFORE -> criteria.range(path, ValueKind.DATE, Criteria.RangeOp.LT,
                    DateKeys.of(window.from()));
            case UNTIL -> criteria.range(path, ValueKind.DATE, Criteria.RangeOp.LT,
                    DateKeys.of(window.until()));
        }
    }

    /** The same, for the engine's own last-updated moment. */
    private static void lastUpdated(Criteria criteria, String value) {
        Criteria.RangeOp op = tryPrefixOp(value);
        DateKeys.Window window = DateKeys.window(op == null ? value : stripPrefix(value));
        switch (op == null ? Bound.EQ : bound(op)) {
            case EQ -> criteria.lastUpdated(Criteria.RangeOp.GE, window.from())
                    .lastUpdated(Criteria.RangeOp.LT, window.until());
            case AFTER -> criteria.lastUpdated(Criteria.RangeOp.GE, window.until());
            case FROM -> criteria.lastUpdated(Criteria.RangeOp.GE, window.from());
            case BEFORE -> criteria.lastUpdated(Criteria.RangeOp.LT, window.from());
            case UNTIL -> criteria.lastUpdated(Criteria.RangeOp.LT, window.until());
        }
    }

    /** Which end of the caller's span a prefix asks about. */
    private enum Bound { EQ, AFTER, FROM, BEFORE, UNTIL }

    private static Bound bound(Criteria.RangeOp op) {
        return switch (op) {
            case GT -> Bound.AFTER;
            case GE -> Bound.FROM;
            case LT -> Bound.BEFORE;
            case LE -> Bound.UNTIL;
        };
    }

    private static void token(Criteria criteria, String path, String value, boolean negate) {
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

    private static void require(String typeName, SearchParameter parameter,
            Enumerations.SearchParamType expected, String display) {
        if (parameter.getType() != expected) {
            throw new UnknownSearchParameterException(typeName, display);
        }
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

    private static ValueKind sortKind(SearchParameter parameter) {
        return switch (parameter.getType()) {
            case DATE -> ValueKind.DATE;
            case NUMBER -> ValueKind.NUMBER;
            case TOKEN -> ValueKind.TOKEN;
            default -> ValueKind.STRING;
        };
    }

    /** Envelope path names: parameter codes with '-' folded to '_'. */
    private static String pathName(String code) {
        return code.replace('-', '_');
    }
}
