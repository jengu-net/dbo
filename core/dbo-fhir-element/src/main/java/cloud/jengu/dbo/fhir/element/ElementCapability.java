package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.common.FhirOperation;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this server actually serves, said in FHIR's words
 * (REQ-DBO-SRCH-HONEST-CAPABILITY).
 *
 * <p>Every line is derived: the types are the tenant's declared types, the
 * interactions are what each type's <b>declared handling</b> permits, the
 * search parameters are the ones the compiler will accept, and the operations
 * are the ones actually registered. A statement written by hand drifts into
 * announcing what the store answers with a refusal.
 */
final class ElementCapability {

    /** The only wire format this face renders — and so the only one declared. */
    private static final String RENDERED_FORMAT = "application/fhir+json";

    /**
     * The parameters the compiler accepts for every type, kept beside it in one
     * list rather than written out here and there: three copies is how a fifth
     * one comes to be served and not declared.
     */
    private static final Map<String, String> META_SEARCH_PARAMS =
            new LinkedHashMap<>(Map.of("_tag", "token", "_profile", "uri",
                    "_lastUpdated", "date", "_id", "token"));

    private ElementCapability() {
    }

    static String statement(ElementVersion version, List<FhirTypeConfig> types, String baseUrl,
            Collection<FhirOperation> served) {
        StringBuilder out = new StringBuilder(2048);
        out.append("{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",")
                .append("\"kind\":\"instance\",\"fhirVersion\":")
                .append(ElementOutcomes.quoted(version.payloadVersion()))
                .append(",\"format\":[").append(ElementOutcomes.quoted(RENDERED_FORMAT))
                .append("],\"implementation\":{\"description\":\"dbo\",\"url\":")
                .append(ElementOutcomes.quoted(baseUrl))
                .append("},\"rest\":[{\"mode\":\"server\",\"resource\":[");
        boolean firstType = true;
        for (FhirTypeConfig type : types) {
            if (!firstType) {
                out.append(',');
            }
            firstType = false;
            resource(out, version, type, served);
        }
        return out.append("]}]}").toString();
    }

    private static void resource(StringBuilder out, ElementVersion version, FhirTypeConfig type,
            Collection<FhirOperation> served) {
        Handling handling = type.handling();
        boolean writable = handling.isWritableBy(Handling.Authority.TENANT_USERS);
        boolean mayChange = handling.mutability() == Handling.Mutability.FULL
                || handling.mutability() == Handling.Mutability.REPLACE_IN_PLACE;
        boolean keepsHistory = handling.durability() == Handling.Durability.VERSIONED;

        List<String> interactions = new ArrayList<>(List.of("read", "search-type"));
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

        out.append("{\"type\":").append(ElementOutcomes.quoted(type.typeName()))
                .append(",\"interaction\":[");
        boolean first = true;
        for (String interaction : interactions) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"code\":").append(ElementOutcomes.quoted(interaction)).append('}');
        }
        // A conditional write must be keyed on the type's own identity
        // (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS), so a store-assigned id has
        // nothing to key on and the store refuses one.
        out.append("],\"conditionalCreate\":")
                .append(writable && type.identityClass() != IdentityClass.INTERNAL)
                // Not versioned-update: If-Match is honoured, never required.
                .append(",\"versioning\":")
                .append(keepsHistory ? "\"versioned\"" : "\"no-version\"")
                .append(",\"readHistory\":").append(keepsHistory)
                .append(",\"searchParam\":[");
        first = true;
        for (SearchParameter parameter : version.parametersFor(type.typeName())) {
            if (!served(parameter.getType())) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"name\":").append(ElementOutcomes.quoted(parameter.getCode()))
                    .append(",\"type\":")
                    .append(ElementOutcomes.quoted(parameter.getType().toCode())).append('}');
        }
        for (Map.Entry<String, String> meta : META_SEARCH_PARAMS.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"name\":").append(ElementOutcomes.quoted(meta.getKey()))
                    .append(",\"type\":").append(ElementOutcomes.quoted(meta.getValue()))
                    .append('}');
        }
        out.append(']');
        // declared because registered, not because remembered (#51)
        List<FhirOperation> operations = served.stream()
                .filter(operation -> operation.types().contains(type.typeName())).toList();
        if (!operations.isEmpty()) {
            out.append(",\"operation\":[");
            first = true;
            for (FhirOperation operation : operations) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append("{\"name\":").append(ElementOutcomes.quoted(operation.name()))
                        .append(",\"definition\":")
                        .append(ElementOutcomes.quoted(operation.definition())).append('}');
            }
            out.append(']');
        }
        out.append('}');
    }

    /** What the compiler will accept — anything else would be announced and refused. */
    private static boolean served(Enumerations.SearchParamType type) {
        return switch (type) {
            case TOKEN, STRING, DATE, NUMBER, REFERENCE, URI -> true;
            default -> false;
        };
    }
}
