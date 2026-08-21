package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.elementmodel.Element;

import java.util.List;
import java.util.Optional;

/**
 * A reference that is a question, answered before the document is written
 * (#89).
 *
 * <p>FHIR lets a writer point at a resource it can describe but cannot name:
 * {@code "reference": "Patient?identifier=https://ehr.example|12345"}. The
 * writer knows an identifier — a device, an integration, a bundle author
 * allocating nothing — and the server resolves it. Stored unresolved, such a
 * reference points at nothing: the document is accepted and broken, which is
 * the failure this exists to prevent.
 *
 * <p><b>Resolved in the tree that was already read</b>, never by re-reading:
 * the write path reads a payload once, and what is indexed must be what is
 * stored (REQ-DBO-VER-ONE-READ-PER-REQUEST). So this edits the element the
 * validator is about to see, and the bytes are composed once afterwards.
 *
 * <p>Knowing what a reference IS belongs here, in the face. Finding what it
 * points at does not — that reaches a store, so it arrives as a function the
 * facade supplies.
 */
final class ElementReferences {

    private ElementReferences() {
    }

    /** What a conditional reference resolves to, or why it did not. */
    interface Resolver {
        /**
         * @param typeName the type named before the {@code ?}
         * @param query    the condition, exactly as written
         * @return the id, or empty when nothing matches — a resolver that
         *         finds several refuses on its own, because "which one" is not
         *         a question this layer can answer
         */
        Optional<String> resolve(String typeName, String query);
    }

    /**
     * Resolves every conditional reference in the document.
     *
     * @return true when the document changed, so the caller composes it once
     *         rather than storing bytes that no longer match the tree
     */
    static boolean resolve(Element document, Resolver resolver) {
        boolean changed = false;
        for (Element reference : conditionalReferences(document)) {
            String value = reference.primitiveValue();
            int question = value.indexOf('?');
            String typeName = value.substring(0, question);
            String query = value.substring(question + 1);
            String id = resolver.resolve(typeName, query).orElseThrow(
                    () -> new IllegalArgumentException("the reference '" + value
                            + "' matches nothing in this store — a reference that is a "
                            + "question is answered when the document is written, and an "
                            + "unanswered one would be stored pointing at nothing"));
            reference.setValue(typeName + "/" + id);
            changed = true;
        }
        return changed;
    }

    /** Every {@code reference} element whose value is a query, at any depth. */
    private static List<Element> conditionalReferences(Element element) {
        List<Element> found = new java.util.ArrayList<>();
        collect(element, found);
        return found;
    }

    private static void collect(Element element, List<Element> found) {
        if ("reference".equals(element.getName()) && element.primitiveValue() != null
                && element.primitiveValue().indexOf('?') > 0) {
            found.add(element);
        }
        for (Element child : element.getChildren()) {
            collect(child, found);
        }
    }
}
