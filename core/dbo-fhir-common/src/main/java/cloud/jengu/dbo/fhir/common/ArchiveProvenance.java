package cloud.jengu.dbo.fhir.common;

import java.util.List;

/**
 * An archive's attestation, rendered as the healthcare domain spells it:
 * {@code Provenance} carrying FHIR's {@code Signature} datatype.
 *
 * <p>The engine's attestation is a root and two detached signatures, and it
 * knows nothing about medicine — an archive of a non-FHIR domain is attested
 * the same way. This is the FHIR face's rendering of that fact,
 * which is why it lives here and not beside the thing it renders: the store
 * holds the evidence, the face says it in the reader's vocabulary.
 *
 * <p>A view, never a store — the same rule the audit projection follows. The
 * truth form stays the detached attestation; this is what a customer's own
 * tooling reads when it checks an export it was handed, with no access to us
 * and no need to learn our JSON.
 *
 * <p>Text rather than HAPI objects on purpose: this module is HAPI-free, and a
 * Provenance with two signatures is a shape, not a computation. The harness
 * validates the output through a real personality, so "it parses and conforms"
 * is proven rather than asserted.
 */
public final class ArchiveProvenance {

    // PLACED, not moved (#106). This is a version-scoped, stateless obligation
    // — the same tier as Coarsening and PortableRendering — and it belongs
    // behind the face contract as a declared capability.
    //
    // It stays a static until it has a production caller. Nothing outside a
    // test renders an archive's Provenance today, and declaring it now would
    // put an interface and a Signer record into the engine's core so that one
    // test could reach them through a lookup. That is machinery ahead of a
    // caller, which is the mistake the grain pieces (#112, #113) were
    // deliberately filed to avoid.
    //
    // The trigger is the first production attestation: at that point it is a
    // capability with core-owned types, and the face declares it.


    /** Where an archive's root is named, since an archive is not a resource. */
    public static final String ROOT_SYSTEM = "urn:dbo:archive-root";

    /** Where a signing key is named — a fingerprint, never the key. */
    public static final String KEY_SYSTEM = "urn:dbo:signing-key";

    /**
     * urn:iso-astm:E1762-95:2013 1.2.840.10065.1.12.1.1 — "Author's Signature".
     * The parties are attesting that this is the archive they produced and
     * accepted, which is what that code says.
     */
    private static final String SIGNATURE_TYPE = "1.2.840.10065.1.12.1.1";

    private ArchiveProvenance() {
    }

    /**
     * One signature over the root.
     *
     * @param party        who signed, in the engine's own words ({@code vendor},
     *                     {@code tenant}) — not a FHIR role, because who the
     *                     two parties are is a property of the arrangement
     *                     rather than of healthcare
     * @param keyFingerprint SHA-256 of the public key, hex
     * @param signature    the signature bytes, base64
     */
    public record Signer(String party, String keyFingerprint, String signature) {}

    /**
     * @param root     the root both parties signed
     * @param recorded when the destination accepted it, ISO-8601 instant
     */
    public static String render(String root, String recorded, List<Signer> signers) {
        StringBuilder json = new StringBuilder("{\"resourceType\":\"Provenance\"");
        // target is 1..* and an archive has no resource to point at, so it is
        // named by identifier — a Reference may carry one without a literal
        // reference, which is exactly the case FHIR provides it for.
        json.append(",\"target\":[{\"identifier\":{\"system\":\"").append(ROOT_SYSTEM)
                .append("\",\"value\":\"").append(root).append("\"}}]")
                .append(",\"recorded\":\"").append(recorded).append('"');

        json.append(",\"agent\":[");
        for (int i = 0; i < signers.size(); i++) {
            Signer signer = signers.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":{\"coding\":[{\"system\":\"")
                    .append("http://terminology.hl7.org/CodeSystem/provenance-participant-type")
                    .append("\",\"code\":\"attester\"}],\"text\":\"").append(signer.party())
                    .append("\"},\"who\":{\"identifier\":{\"system\":\"").append(KEY_SYSTEM)
                    .append("\",\"value\":\"").append(signer.keyFingerprint()).append("\"}}}");
        }
        json.append(']');

        json.append(",\"signature\":[");
        for (int i = 0; i < signers.size(); i++) {
            Signer signer = signers.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":[{\"system\":\"urn:iso-astm:E1762-95:2013\",\"code\":\"")
                    .append(SIGNATURE_TYPE).append("\"}]")
                    .append(",\"when\":\"").append(recorded).append('"')
                    .append(",\"who\":{\"identifier\":{\"system\":\"").append(KEY_SYSTEM)
                    .append("\",\"value\":\"").append(signer.keyFingerprint()).append("\"}}")
                    // Ed25519 over the root's bytes. sigFormat says what the
                    // data is, because a reader holding only this file has no
                    // other way to learn how to check it.
                    .append(",\"sigFormat\":\"application/octet-stream\"")
                    .append(",\"data\":\"").append(signer.signature()).append("\"}");
        }
        return json.append("]}").toString();
    }
}
