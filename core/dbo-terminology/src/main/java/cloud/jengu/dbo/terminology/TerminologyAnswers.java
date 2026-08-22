package cloud.jengu.dbo.terminology;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The three terminology operations' answers, rendered once for every FHIR
 * version this store serves.
 *
 * <p>This is the part of a terminology facade that is genuinely version-neutral,
 * and it is worth naming why, because the rest of such a facade is not. A
 * {@code Parameters} carrying a name and a display, and a {@code ValueSet}
 * carrying an expansion, have the same JSON in R4, R5 and R6 — the shapes these
 * operations answer with did not move between versions. What moved is the
 * <b>reading</b> of a client's CodeSystem: the typed faces parse with their own
 * model and the element face with the element model, and no seam joins those
 * two without becoming a third parser.
 *
 * <p>So the split is: reading stays in each face, answering lives here. A face
 * asks its store, and hands the concepts to these methods.
 *
 * <p>Written as strings rather than through a model deliberately — this module
 * has one dependency (the PostgreSQL driver) and gains nothing by taking a FHIR
 * model to emit four fixed shapes.
 */
public final class TerminologyAnswers {

    private TerminologyAnswers() {}

    /**
     * {@code CodeSystem/$lookup} → Parameters, with the concept's designations
     * and properties as {@code part}s.
     *
     * <p>A concept with no display OMITS the parameter, and this is the one
     * place where all three faces were previously wrong in two different ways.
     * The typed faces emitted {@code {"name":"display"}} — a parameter with no
     * value, which fails {@code inv-1} ("one and only one of value, resource,
     * part"). This one emitted {@code "valueString": ""}, which fails
     * {@code ele-1}: a FHIR string cannot be empty. Both were refused by this
     * store's own validator, asked for the first time in #103.
     *
     * <p>So absence is the only legal encoding of "no display" — FHIR has no
     * way to say the display is the empty string, and a caller cannot be given
     * one.
     */
    public static String lookup(String system, Concept concept) {
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"Parameters\",\"parameter\":[");
        sb.append("{\"name\":\"name\",\"valueString\":").append(Json.quote(system)).append('}');
        if (concept.display() != null && !concept.display().isEmpty()) {
            sb.append(",{\"name\":\"display\",\"valueString\":")
                    .append(Json.quote(concept.display())).append('}');
        }
        for (Map.Entry<String, String> designation : concept.designations().entrySet()) {
            sb.append(",{\"name\":\"designation\",\"part\":[")
                    .append(part("language", designation.getKey())).append(',')
                    .append(part("value", designation.getValue())).append("]}");
        }
        for (Map.Entry<String, String> property : concept.properties().entrySet()) {
            sb.append(",{\"name\":\"property\",\"part\":[")
                    .append(part("code", property.getKey())).append(',')
                    .append(part("value", property.getValue())).append("]}");
        }
        return sb.append("]}").toString();
    }

    /**
     * {@code $validate-code} → Parameters with {@code result}.
     *
     * <p>A null concept is {@code result: false} and a 200, not a 404: "no" is
     * the answer to this question rather than a failure to answer it.
     */
    public static String validateCode(Concept concept) {
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"Parameters\",\"parameter\":[");
        sb.append("{\"name\":\"result\",\"valueBoolean\":").append(concept != null).append('}');
        if (concept != null && concept.display() != null && !concept.display().isEmpty()) {
            sb.append(",{\"name\":\"display\",\"valueString\":")
                    .append(Json.quote(concept.display())).append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * {@code ValueSet/$expand} → a ValueSet carrying only its expansion.
     *
     * <p>The {@code timestamp} is not decoration: it is 1..1 on
     * {@code ValueSet.expansion}, and every expansion all three faces ever
     * answered was invalid without it (#103). It says when THIS expansion was
     * computed, which is now — an expansion is a snapshot of concepts that can
     * change under it, and a caller keeping one needs to know how old it is.
     */
    public static String expansion(String valueSetUrl, int offset,
            TerminologyStore.Expansion expansion) {
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"ValueSet\",\"url\":");
        sb.append(Json.quote(valueSetUrl)).append(",\"status\":\"active\",\"expansion\":{");
        sb.append("\"timestamp\":")
                .append(Json.quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        sb.append(",\"total\":").append(expansion.total());
        sb.append(",\"offset\":").append(offset);
        sb.append(",\"contains\":[");
        List<TerminologyStore.ExpandedConcept> contains = expansion.contains();
        for (int i = 0; i < contains.size(); i++) {
            TerminologyStore.ExpandedConcept c = contains.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"system\":").append(Json.quote(c.system()))
                    .append(",\"code\":").append(Json.quote(c.code()));
            if (c.display() != null) {
                sb.append(",\"display\":").append(Json.quote(c.display()));
            }
            sb.append('}');
        }
        return sb.append("]}}").toString();
    }

    private static String part(String name, String value) {
        return "{\"name\":" + Json.quote(name) + ",\"valueString\":" + Json.quote(value) + "}";
    }
}
