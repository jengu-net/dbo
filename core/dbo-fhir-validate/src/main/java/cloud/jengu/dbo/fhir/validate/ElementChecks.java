package cloud.jengu.dbo.fhir.validate;

import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the index says is wrong with a document: how often an element occurs,
 * what it must equal, what it must contain, and whether a coded value is in
 * the value set a required binding names.
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
    private final BoundCodes codes;
    private final List<Finding> findings = new ArrayList<>();
    private int descents;
    private int deepest;

    private ElementChecks(DefinitionIndex index, BoundCodes codes) {
        this.index = index;
        this.codes = codes;
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
        return over(index, null, canonical, document);
    }

    /**
     * The same, with the codes a required binding is decided against.
     *
     * <p>Optional because they are a different kind of content: a process may
     * hold the definitions and leave terminology to the database, and one
     * that does gets every check but this one rather than a broken one.
     */
    public static Checked over(DefinitionIndex index, BoundCodes codes, String canonical,
            byte[] document) {
        ElementChecks check = new ElementChecks(index, codes);
        Map<String, Object> root = JsonDocument.of(document);
        int from = index.rootOf(canonical);
        if (root != null && from >= 0) {
            check.walk(from, root, index.pathOf(from));
        }
        return new Checked(List.copyOf(check.findings), check.descents, check.deepest);
    }

    private void walk(int parent, Map<?, ?> node, String reported) {
        for (int element : index.childrenOf(parent)) {
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

            // A SLICE CLAIMS SOME OF THEM. Patient.identifier and
            // Patient.identifier:ik are two elements over one field, and the
            // second is counted as the members its discriminator picks out.
            // Counting every member against a slice's own minimum and maximum
            // reports a document that satisfies neither and accepts one that
            // satisfies nothing.
            String slice = index.sliceOf(element);
            if (slice != null) {
                present.removeIf(one -> !SlicePredicate.claims(one, slice));
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
            // type's. Where this ELEMENT has children, that is what applies
            // and the walk stays here: a backbone is always enumerated inside
            // its own resource, and so is whatever a profile pinned inside a
            // datatype. Hopping into Identifier's own structure on the
            // strength of the type would walk straight past a tenant's fixed
            // Patient.identifier.system — which is what this did until a
            // profile was put to it, because base definitions pin almost
            // nothing and never showed it. By element and not by path, because
            // a slice's children are not the children of what it slices.
            boolean enumeratedHere = !index.childrenOf(element).isEmpty();
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
                bound(element, one, where, full);
                if (!(one instanceof Map<?, ?> object)) {
                    continue;
                }
                deepest = Math.max(deepest, where.length() - where.replace(".", "").length());
                if (enumeratedHere) {
                    descents++;
                    walk(element, object, where);
                } else if (into != null && !NOT_FOLLOWED.contains(into)
                        && index.holds(PREFIX + into)) {
                    descents++;
                    walk(index.rootOf(PREFIX + into), object, where);
                }
            }
        }
    }

    /**
     * A required binding, judged per element instance rather than per coding.
     *
     * <p>A CodeableConcept satisfies a binding when ANY of its codings is in
     * the value set, so the codings are judged together and the element is
     * refused only when every code that could be judged was and none of them
     * belonged. Where nothing could judge — the value set is not held, or none
     * of the systems it is built from are — there is no finding at all.
     * Unresolvable is not invalid.
     */
    private void bound(int element, Object instance, String where, String full) {
        String valueSet = index.bindingValueSetOf(element);
        if (codes == null || valueSet == null
                || index.bindingStrengthOf(element) != DefinitionIndex.REQUIRED) {
            return;
        }
        int judged = 0;
        List<String> shown = new ArrayList<>();
        for (String[] coded : codedValues(instance)) {
            Boolean member = codes.contains(valueSet, coded[0], coded[1]);
            if (member == null) {
                continue;
            }
            judged++;
            shown.add((coded[0] == null ? "" : coded[0] + "#") + coded[1]);
            if (member) {
                return;
            }
        }
        if (judged > 0) {
            findings.add(new Finding("error", where, "binding",
                    full + " is bound to " + valueSet.split("\\|")[0] + ", and "
                            + String.join(", ", shown) + " is not in it"));
        }
    }

    /**
     * The coded values inside one element instance: a bare code, a Coding, or
     * the codings of a CodeableConcept.
     */
    private static List<String[]> codedValues(Object instance) {
        List<String[]> out = new ArrayList<>();
        if (instance instanceof String bare) {
            out.add(new String[] {null, bare});
            return out;
        }
        if (!(instance instanceof Map<?, ?> object)) {
            return out;
        }
        for (Object coding : values(object.get("coding"))) {
            if (coding instanceof Map<?, ?> one && one.get("code") != null) {
                out.add(new String[] {text(one.get("system")), text(one.get("code"))});
            }
        }
        if (object.get("code") != null) {
            out.add(new String[] {text(object.get("system")), text(object.get("code"))});
        }
        return out;
    }

    private static String text(Object held) {
        return held instanceof String one ? one : null;
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
