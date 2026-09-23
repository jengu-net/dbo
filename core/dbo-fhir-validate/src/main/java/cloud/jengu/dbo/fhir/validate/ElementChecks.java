package cloud.jengu.dbo.fhir.validate;

import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the index says is wrong with a document: how often an element occurs,
 * what it must equal, and what it must contain.
 *
 * <p>The third answerer. The toolchain reads an object graph and the database
 * reads the expanded rows; this reads the same rows as arrays in its own heap,
 * with no context and no round trip. What it must be is the same answer —
 * three answers nobody compares would be worse than two that are.
 *
 * <p><b>One walk, several checks</b>, which is the arrangement the database
 * already has: {@code dbo.validate} walks a document once and hands the walk
 * to each check, rather than walking once per question. A second descent for
 * values would cost what the whole check costs and would be a second place the
 * rule about where the walk goes is written.
 *
 * <p><b>Driven from the DEFINITION rather than from the document</b>, because
 * a required element that is absent is invisible in the document and is most
 * of what cardinality catches.
 *
 * <p><b>The walk follows the snapshot, not the type.</b> Where a structure
 * enumerates something below an element, that is what applies and the walk
 * goes a path deeper in the same structure — a backbone is always enumerated
 * inside its own resource, and so is whatever a profile pinned inside a
 * datatype. Only where a structure says nothing below an element does the
 * walk move into the type's own structure and start again at its root:
 * {@code Patient.name} is a {@code HumanName} and a base Patient says no more
 * about it. That difference is what makes a nested maximum per parent
 * instance rather than per document — one contact holding two names is wrong,
 * two contacts holding one each is not — and it is what keeps a tenant's own
 * constraint from being walked past.
 */
public final class ElementChecks {

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

    private ElementChecks(DefinitionIndex index) {
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
        ElementChecks check = new ElementChecks(index);
        Map<String, Object> root = JsonDocument.of(document);
        String from = index.rootPathOf(canonical);
        if (root != null && from != null) {
            check.walk(canonical, from, root, from);
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
            // WHERE TO GO NEXT, and the rule is the snapshot's rather than the
            // type's. Where this structure enumerates anything below this
            // element, that is what applies and the walk stays here: a
            // backbone is always enumerated inside its own resource, and so is
            // whatever a profile pinned inside a datatype. Hopping into
            // Identifier's own structure on the strength of the type would
            // walk straight past a tenant's fixed Patient.identifier.system —
            // which is what this did until a profile was put to it, because
            // base definitions pin almost nothing and never showed it.
            boolean enumeratedHere = !index.childrenOf(canonical, full).isEmpty();
            String fixed = index.fixedOf(element);
            String pattern = index.patternOf(element);
            int at = 0;
            for (Object one : present) {
                String where = reported + "." + name
                        + (present.size() > 1 ? "[" + at++ + "]" : "");
                if (fixed != null && !JsonValue.same(one, fixed)) {
                    findings.add(new Finding("error", where, "fixed",
                            full + " is fixed to " + fixed + " and holds "
                                    + JsonValue.asText(one)));
                }
                if (pattern != null && !JsonValue.contains(one, pattern)) {
                    findings.add(new Finding("error", where, "pattern",
                            full + " must contain " + pattern + " and holds "
                                    + JsonValue.asText(one)));
                }
                if (!(one instanceof Map<?, ?> object)) {
                    continue;
                }
                deepest = Math.max(deepest, where.length() - where.replace(".", "").length());
                if (enumeratedHere) {
                    descents++;
                    walk(canonical, full, object, where);
                } else if (into != null && !NOT_FOLLOWED.contains(into)
                        && index.holds(PREFIX + into)) {
                    descents++;
                    walk(PREFIX + into, index.rootPathOf(PREFIX + into), object, where);
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
