package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveManifest;
import cloud.jengu.dbo.maintenance.SealedArchive;
import cloud.jengu.dbo.maintenance.TenantImport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * An archive both parties have signed, for tests that need to import one.
 *
 * <p>Import verifies or it does nothing (REQ-DBO-MNT-IMPORT-REFUSES-UNATTESTED),
 * so every test that moves data now performs the ceremony. Doing it here rather
 * than in each test keeps the ceremony one thing: a test that wanted to prove
 * something about references or redelivery should not also be a worked example
 * of key handling.
 *
 * <p>The keys are generated per archive and thrown away with it. A test that is
 * about the signatures themselves — a wrong key, a missing countersignature —
 * builds its own, because that is the thing it is proving
 * ({@code ArchiveAttestationIT}).
 */
record CoSignedArchive(byte[] sealed, ArchiveAttestation attestation,
        byte[] vendorPublicKey, byte[] tenantPublicKey) {

    /** Signs a sealed archive as both parties, over the root the export wrote. */
    static CoSignedArchive over(byte[] sealed, byte[] ownerMasterKey) throws Exception {
        byte[] plain;
        try (InputStream opening = SealedArchive.opening(
                new ByteArrayInputStream(sealed), ownerMasterKey)) {
            plain = opening.readAllBytes();
        }
        String root = ArchiveManifest.of(plain).root();

        KeyPair vendor = ed25519();
        KeyPair tenant = ed25519();
        ArchiveAttestation attestation = ArchiveAttestation.over(root)
                .signedBy(ArchiveAttestation.Party.VENDOR, vendor.getPrivate().getEncoded())
                .signedBy(ArchiveAttestation.Party.TENANT, tenant.getPrivate().getEncoded());
        return new CoSignedArchive(sealed, attestation,
                vendor.getPublic().getEncoded(), tenant.getPublic().getEncoded());
    }

    /** The archive, re-openable — import reads it twice: once to verify, once to apply. */
    TenantImport.ArchiveSource source() {
        return () -> new ByteArrayInputStream(sealed);
    }

    /** Imports into {@code target}, verifying first, as production does. */
    TenantImport.PortableResult importInto(cloud.jengu.dbo.core.api.ObjectStore target,
            byte[] ownerMasterKey, TenantImport.HistoryMode history) throws IOException {
        return TenantImport.importVerified(target, source(), ownerMasterKey,
                attestation, vendorPublicKey, tenantPublicKey, history);
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
