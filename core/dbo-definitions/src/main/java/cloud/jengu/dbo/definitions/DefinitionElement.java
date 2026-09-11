package cloud.jengu.dbo.definitions;

import java.util.List;

/**
 * One element of a definition, in the form the database checks against
 * (REQ-DBO-CORE-DECLARED-TRUTH-FORM, as a CodeSystem's concepts are).
 *
 * <p>The document keeps saying what it always said; this says what a checker
 * has to know, with the walking already done. A StructureDefinition states an
 * element as a dotted path over a type system — {@code Observation.value[x]},
 * {@code Observation.component:SystolicBP.code} — and nothing that reads JSON
 * can act on either without first resolving what key a choice appears under
 * and which members of an array a slice claims. That resolution is the same
 * every time the definition is read, so it is done once, when the definition
 * arrives.
 *
 * <p><b>An element that cannot be located is still a row.</b> A slice told
 * apart by following a reference is a join rather than a path, and no step
 * reaches it. The row is written anyway, carrying {@link #unenforceable} and
 * no steps, because the alternative is a checker that holds nothing for that
 * element and therefore passes everything at it — silently. This is the shape
 * REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID already gives a code from a system
 * the store does not hold: not judged, and never mistaken for judged.
 *
 * <p><b>Why {@link #steps} is a list, and relative.</b> An element is located
 * from an INSTANCE of its parent, not from the document root: {@code max = 1}
 * on {@code Patient.contact.name} means one name per contact, and a path from
 * the root flattens three contacts into three names and refuses a document
 * that is correct. So each row carries the step from its parent's instance and
 * names that parent, and a checker walks the two together. The list has more
 * than one entry only for a choice, which is one element appearing under one
 * of several keys — the count is over all of them, because the instance uses
 * exactly one.
 *
 * @param id        the definition's own element id, slice markers and all
 * @param path      the element path, without slice markers
 * @param parentId  the element this one is found inside, null at the root
 * @param steps     Postgres jsonpath, relative to an instance of the parent;
 *                  empty at the root, where the instance IS the document
 * @param min       least occurrences per parent instance
 * @param max       most occurrences per parent instance, null for unbounded
 * @param types     what may stand here, with the profiles and reference
 *                  targets each type names
 * @param fixedJson the value this element must equal, as authored, or null
 * @param patternJson the value this element must contain, as authored, or null
 * @param bindingStrength   required | extensible | preferred | example, or null
 * @param bindingValueSet   the value set a coded element is bound to, or null
 * @param unenforceable why nothing here can be checked against this element,
 *                      or null when it can — see the class note
 */
public record DefinitionElement(
        String id,
        String path,
        String parentId,
        List<String> steps,
        int min,
        Integer max,
        List<Type> types,
        String fixedJson,
        String patternJson,
        String bindingStrength,
        String bindingValueSet,
        String unenforceable) {

    /** Whether a checker can act on this element at all. */
    public boolean enforceable() {
        return unenforceable == null;
    }

    /** One type an element may take, with what it must conform to. */
    public record Type(String code, List<String> profiles, List<String> targets) {}
}
