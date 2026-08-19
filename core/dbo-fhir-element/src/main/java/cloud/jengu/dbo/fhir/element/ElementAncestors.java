package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.formats.IParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A stored payload with the engine's own facts put back.
 *
 * <p>Stamping is a claim about custody at a moment and is made at write;
 * putting the ancestors into a document a reader receives is the other act, and
 * belongs at read — the alternative is freezing the store's facts into bytes
 * that are never rewritten (§1). One body, so serving, export and framing
 * cannot drift apart.
 *
 * <p>Which slots those are is the version's, not this code's: {@code id} and
 * {@code meta.versionId} are named in the definitions, and setting them through
 * the element model is replace-not-insert on a payload that already carries
 * them — trivial when re-emitting a parse, treacherous when editing a string.
 */
final class ElementAncestors {

    private ElementAncestors() {
    }

    static byte[] rendered(SimpleWorkerContext context, byte[] payload, String id, long versionId) {
        try {
            Element document = Manager.parseSingle(context, new ByteArrayInputStream(payload),
                    Manager.FhirFormat.JSON);
            document.setChildValue("id", id);
            Element meta = document.getNamedChild("meta");
            if (meta == null) {
                meta = document.makeElement("meta");
            }
            meta.setChildValue("versionId", Long.toString(versionId));
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
            Manager.compose(context, document, out, Manager.FhirFormat.JSON,
                    IParser.OutputStyle.NORMAL, null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot render a stored payload held in memory", e);
        }
    }
}
