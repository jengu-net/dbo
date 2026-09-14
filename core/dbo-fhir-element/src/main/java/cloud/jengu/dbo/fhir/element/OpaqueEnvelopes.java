package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * The envelope of a type this face has no definition for: its identity, and
 * nothing else.
 *
 * <p><b>Why such a type exists at all.</b> A consumer's own declarations —
 * which tenant this is, which zone it belongs to, where its configuration is
 * kept — have no FHIR shape, and FHIR will not let them be given one: a
 * StructureDefinition that defines a resource type the specification does not
 * have is refused by the validator, and the only legal way to describe an
 * arbitrary shape is a logical model, which is not a resource and cannot be
 * read or searched. So the choice is between holding such a thing as somebody
 * else's resource with the document buried in an extension — a second
 * representation, and a second thing to be wrong — or holding it as itself.
 *
 * <p><b>Nothing here parses FHIR.</b> The ordinary extractor's first act is to
 * read the payload through the toolchain, which is exactly what refuses an
 * unknown resource type; the definition types already take a path that reads
 * their JSON directly, for a related reason, and this is the same move. The
 * document is read as JSON, the declared identity systems are picked out of
 * {@code identifier}, and the bytes are never interpreted again.
 *
 * <p><b>What such a type therefore promises, and what it does not.</b> It is
 * findable by the identity it declares — which is what makes an identity-keyed
 * write, a conditional update and {@code ?identifier=system|value} work — and
 * by nothing else. Another search parameter over it would have to name a
 * {@code base} the specification does not know, which is the same illegality
 * as the StructureDefinition. A caller asking by anything else is told the
 * parameter is unsupported rather than answered with an empty result, because
 * an empty result reads as there being nothing to find.
 */
final class OpaqueEnvelopes {

    private OpaqueEnvelopes() {
    }

    /**
     * The identity claims this document makes, under the systems its type
     * declared.
     *
     * <p>Only the declared systems, not every identifier the document carries.
     * A claim is exclusive — it refuses a second record making it — and
     * claiming every system a document happens to mention would have one
     * declaration locking out another over an identifier neither of them is
     * identified by.
     */
    static Envelope extract(String typeName, Set<String> identitySystems, byte[] payload) {
        JsonObject document;
        try {
            document = JsonParser.parseObject(new ByteArrayInputStream(payload));
        } catch (IOException | RuntimeException e) {
            // Still JSON, and still the caller's mistake when it is not: what
            // is not being asked of it is that it be FHIR.
            throw new IllegalArgumentException("body is not parseable JSON: "
                    + e.getMessage(), e);
        }
        String declared = document.asString("resourceType");
        if (declared != null && !typeName.equals(declared)) {
            // Read for exactly one type, so being any other is a refusal —
            // the same one the toolchain path gives. A document with no
            // resourceType at all is not refused: this type is not a FHIR
            // resource and nothing says it must carry one.
            throw new IllegalArgumentException("expected a " + typeName + ", found '"
                    + declared + "'");
        }
        Envelope envelope = new Envelope();
        if (!document.has("identifier")) {
            return envelope;
        }
        for (org.hl7.fhir.utilities.json.model.JsonElement each
                : document.getJsonArray("identifier")) {
            if (!(each instanceof JsonObject one)) {
                continue;
            }
            String system = one.asString("system");
            String value = one.asString("value");
            if (system == null || value == null || !identitySystems.contains(system)) {
                continue;
            }
            ElementEnvelopes.tokenForms(envelope, "identifier", system, value);
            envelope.identifier(system, value);
        }
        return envelope;
    }
}
