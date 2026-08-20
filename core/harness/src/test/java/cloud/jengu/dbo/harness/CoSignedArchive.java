package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.maintenance.ArchiveAttestation;
import cloud.jengu.dbo.maintenance.ArchiveManifest;
import cloud.jengu.dbo.maintenance.TenantImport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        String root = ArchiveManifest.rootOfSealed(
                new ByteArrayInputStream(sealed), ownerMasterKey);

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
        return importInto(target, ownerMasterKey, history, accepted -> { });
    }

    /** As above, asking a face whether two documents are the same object. */
    TenantImport.PortableResult importInto(cloud.jengu.dbo.core.api.ObjectStore target,
            byte[] ownerMasterKey, TenantImport.HistoryMode history,
            cloud.jengu.dbo.core.face.DocumentEquivalence equivalence) throws IOException {
        return TenantImport.importVerified(target, source(), ownerMasterKey,
                attestation, vendorPublicKey, tenantPublicKey, history, accepted -> { },
                equivalence);
    }

    /** As above, with the ledger the destination records into. */
    TenantImport.PortableResult importInto(cloud.jengu.dbo.core.api.ObjectStore target,
            byte[] ownerMasterKey, TenantImport.HistoryMode history,
            cloud.jengu.dbo.maintenance.ImportLedger ledger) throws IOException {
        return TenantImport.importVerified(target, source(), ownerMasterKey,
                attestation, vendorPublicKey, tenantPublicKey, history, ledger,
                TenantImport.comparingBytes());
    }

    /** Byte-faithful restore into an empty tenant, attested as production requires. */
    void restoreFidelityInto(javax.sql.DataSource target, String domain, byte[] ownerMasterKey)
            throws IOException {
        TenantImport.restoreFidelity(target, domain, new ByteArrayInputStream(sealed),
                ownerMasterKey, attestation, vendorPublicKey, tenantPublicKey, accepted -> { });
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
