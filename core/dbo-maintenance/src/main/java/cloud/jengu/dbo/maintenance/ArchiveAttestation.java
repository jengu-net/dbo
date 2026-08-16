package cloud.jengu.dbo.maintenance;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who attested an archive's root, and how anyone checks it (#34, ADR 0052).
 *
 * <p><b>Detached on purpose.</b> The attestation travels beside the archive
 * rather than inside it, so the tenant can countersign without holding the
 * key that seals it — a countersignature that required resealing would put
 * the vendor's key in the tenant's hands, or the tenant's ceremony in the
 * vendor's process, and either way the second signature would stop meaning
 * anything.
 *
 * <p>Ed25519: small signatures, no parameter choices to get wrong, and in
 * every JRE since 15.
 */
public record ArchiveAttestation(String root, Map<Party, String> signatures) {

    /** Who signs. Both are required before an import will proceed. */
    public enum Party {
        /** Us. Proves the archive is the one we produced. */
        VENDOR,
        /**
         * The tenant whose data it is. Proves we did not produce it alone —
         * which is the whole point, and only true while their key stays
         * somewhere we cannot use it.
         */
        TENANT
    }

    public ArchiveAttestation {
        signatures = Map.copyOf(signatures);
    }

    public static ArchiveAttestation over(String root) {
        return new ArchiveAttestation(root, Map.of());
    }

    /** Adds a party's signature over the root, leaving the others untouched. */
    public ArchiveAttestation signedBy(Party party, byte[] pkcs8PrivateKey) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8PrivateKey));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(root.getBytes(StandardCharsets.UTF_8));
            Map<Party, String> next = new LinkedHashMap<>(signatures);
            next.put(party, Base64.getEncoder().encodeToString(signer.sign()));
            return new ArchiveAttestation(root, next);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign as " + party, e);
        }
    }

    /**
     * Whether this party's signature over the root checks out against their
     * public key. Verification takes only public keys — a verifier that
     * carried a private one would be a tool that could be made to sign.
     */
    public boolean verifiedBy(Party party, byte[] x509PublicKey) {
        String signature = signatures.get(party);
        if (signature == null) {
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(x509PublicKey));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(root.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            // A malformed key or signature is a failed verification, not a
            // crash: the caller asked a yes/no question about trust.
            return false;
        }
    }

    public boolean hasSignature(Party party) {
        return signatures.containsKey(party);
    }

    public String toJson() {
        StringBuilder json = new StringBuilder("{\"root\":\"").append(root).append("\",\"signatures\":{");
        boolean first = true;
        for (Map.Entry<Party, String> entry : signatures.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey().name().toLowerCase()).append("\":\"")
                    .append(entry.getValue()).append('"');
        }
        return json.append("}}").toString();
    }

    public static ArchiveAttestation fromJson(String json) {
        String root = between(json, "\"root\":\"", "\"");
        Map<Party, String> signatures = new LinkedHashMap<>();
        for (Party party : Party.values()) {
            String needle = "\"" + party.name().toLowerCase() + "\":\"";
            if (json.contains(needle)) {
                signatures.put(party, between(json, needle, "\""));
            }
        }
        return new ArchiveAttestation(root, signatures);
    }

    private static String between(String source, String prefix, String suffix) {
        int start = source.indexOf(prefix) + prefix.length();
        return source.substring(start, source.indexOf(suffix, start));
    }
}
