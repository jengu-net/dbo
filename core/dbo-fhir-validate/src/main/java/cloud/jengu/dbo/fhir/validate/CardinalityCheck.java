package cloud.jengu.dbo.fhir.validate;

import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How often an element may occur, counted inside the parent it occurs in,
 * against the index.
 *
 * <p>The third answerer. The toolchain reads an object graph and the database
 * reads the expanded rows; this reads the same rows as arrays in its own heap,
 * with no context and no round trip. What it must be is the same answer —
 * three answers nobody compares would be worse than two that are.
 *
 * <p><b>Driven from the DEFINITION rather than from the document</b>, because
 * a required element that is absent is invisible in the document and is most
 * of what cardinality catches.
 *
 * <p><b>The walk changes structure where the document does.</b> A backbone —
 * {@code Patient.contact} — is defined inside its own resource, so the walk
 * stays in that structure and goes a path deeper. An element typed as a
 * datatype — {@code Patient.name} is a {@code HumanName} — is defined in that
 * type's own structure, so the walk moves there and starts again at its root.
 * That difference is what makes a nested maximum per parent instance rather
 * than per document: one contact holding two names is wrong, two contacts
 * holding one each is not.
 */
public final class CardinalityCheck {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /**
     * Types the walk does not follow.
     *
     * <p>A contained resource may be of any type and the element says only
     * {@code Resource}: walking a contained Patient against {@code Resource}
     * would find its own elements undeclared and be right about nothing. What
     * a document may contain is a question about the tenant's declaration
     * rather than about cardinality.
     */
    private static final Set<String> NOT_FOLLOWED = Set.of("Resource", "DomainResource");

    private final DefinitionIndex index;
    private final List<Finding> findings = new ArrayList<>();
    private int descents;
    private int deepest;

    private CardinalityCheck(DefinitionIndex index) {
        this.index = index;
    }

    /**
     * What was found, and what the walk did to find it.
     *
     * <p>The two counts are not decoration. "Nothing was faulted" is also
     * what a checker that never descended reports, so a caller measuring this
     * against a clean corpus has no way to tell the two apart without them.
     *
     * @param findings what is wrong, in the order the definition states it
     * @param descents nodes the walk went into
     * @param deepest  the longest path it reached, in segments
     */
    public record Checked(List<Finding> findings, int descents, int deepest) {
    }

    /** What the index says is wrong with this document, at every depth. */
    public static Checked over(DefinitionIndex index, String canonical, byte[] document) {
        CardinalityCheck check = new CardinalityCheck(index);
        Map<String, Object> root = JsonDocument.of(document);
        if (root != null) {
            String type = canonical.substring(canonical.lastIndexOf('/') + 1);
            check.walk(canonical, type, root, type);
        }
        return new Checked(List.copyOf(check.findings), check.descents, check.deepest);
    }

    private void walk(String canonical, String path, Map<?, ?> node, String reported) {
        for (int element : index.childrenOf(canonical, path)) {
            String full = index.pathOf(element);
            String name = full.substring(full.lastIndexOf('.') + 1);
            List<Object> present = new ArrayList<>();
            String chosen = null;
            if (name.endsWith("[x]")) {
                // A choice arrives under a name the definition never states:
                // value[x] is valueQuantity on the wire, and the suffix is the
                // type, which is also how the walk knows where to go next.
                String stem = name.substring(0, name.length() - 3);
                for (Map.Entry<?, ?> entry : node.entrySet()) {
                    String field = String.valueOf(entry.getKey());
                    if (field.startsWith(stem) && field.length() > stem.length()
                            && Character.isUpperCase(field.charAt(stem.length()))) {
                        present.addAll(values(entry.getValue()));
                        chosen = field.substring(stem.length());
                    }
                }
            } else {
                present.addAll(values(node.get(name)));
            }

            int min = index.minOf(element);
            int max = index.maxOf(element);
            if (present.size() < min || (max != DefinitionIndex.UNBOUNDED
                    && present.size() > max)) {
                findings.add(new Finding("error", reported + "." + name, "cardinality",
                        full + " occurs " + present.size() + " times here; the profile allows "
                                + min + ".." + (max == DefinitionIndex.UNBOUNDED
                                        ? "*" : String.valueOf(max))));
            }
            if (present.isEmpty()) {
                continue;
            }

            List<String> types = index.typesOf(element);
            String into = chosen != null ? chosen : types.size() == 1 ? types.get(0) : null;
            boolean backbone = types.contains("BackboneElement") || types.contains("Element");
            int at = 0;
            for (Object one : present) {
                String where = reported + "." + name
                        + (present.size() > 1 ? "[" + at++ + "]" : "");
                if (!(one instanceof Map<?, ?> object)) {
                    continue;
                }
                deepest = Math.max(deepest, where.length() - where.replace(".", "").length());
                if (backbone) {
                    descents++;
                    walk(canonical, full, object, where);
                } else if (into != null && !NOT_FOLLOWED.contains(into)
                        && index.holds(PREFIX + into)) {
                    descents++;
                    walk(PREFIX + into, into, object, where);
                }
            }
        }
    }

    /** One value, or the entries of a repeat. */
    private static List<Object> values(Object held) {
        if (held == null) {
            return List.of();
        }
        if (held instanceof List<?> many) {
            return new ArrayList<>(many);
        }
        return List.of(held);
    }
}
