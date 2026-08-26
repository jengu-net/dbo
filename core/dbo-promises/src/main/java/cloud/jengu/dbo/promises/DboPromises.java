package cloud.jengu.dbo.promises;

import cloud.jengu.dbo.promise.Catalogue;
import cloud.jengu.dbo.promise.Promise;

/**
 * The store's promise catalogue — the pilot carries the SHAPE and PDI areas
 * (#140); the remaining areas migrate table by table as the model proves
 * itself. The constant's name IS the code, prefixed by the namespace, so a
 * citation cannot drift from a declaration.
 */
@Catalogue(namespace = "REQ-DBO")
public enum DboPromises implements Promise {

    // ── SHAPE — shape versioning ────────────────────────────────────────

    SHAPE_WRITTEN_UNDER_STAMPED("Every object accepted through the face carries, as a fact "
            + "of the accept event stored beside the payload, the version of each declared "
            + "pack profile it was validated against; re-accepting replaces the stamp, "
            + "never accumulates it."),

    SHAPE_STAMP_IS_DERIVED("The shape stamp is a per-version fact column in state and "
            + "history; the envelope's shape dimension is rebuilt from it on reindex, and "
            + "every history version serves its own stamp."),

    SHAPE_SERVED_BESIDE_THE_CLAIM("The stamp is served in meta as the published "
            + "urn:dbo:shape extension beside the unversioned meta.profile canonical; an "
            + "echoed copy of the engine's own stamp is dropped at accept, so stored bytes "
            + "stay the author's claims."),

    SHAPE_MIRRORED_KEEPS_ITS_STAMP("The stamp travels the sync wire beside the "
            + "storage-format version, so a mirrored copy keeps the stamp of the store "
            + "that validated it; only an authored accept restamps."),

    // ── PDI — personal-data isolation ───────────────────────────────────

    PDI_STRUCTURAL_VAULT("Identifying elements, declared per type/element, live encrypted "
            + "in the tenant's person vault; the main store holds pseudonymous records and "
            + "the engine reassembles full resources for authorized reads — isolation is "
            + "beneath the API, not a caller discipline."),

    PDI_CRYPTO_SHREDDING("Erasure destroys the person's key: history stays byte-immutable, "
            + "existing archives stay valid as files, and the person's data is "
            + "cryptographically gone from live store, history, envelopes and archives at "
            + "once."),

    PDI_UNFINDABLE_AFTER_ERASURE("Search indexes derived from personal elements are "
            + "vault-scoped or rebuilt on shred — an erased person is unfindable, not "
            + "merely unreadable."),

    PDI_BLIND_OPERATIONS("Backup and restore are machinery-driven end to end over "
            + "ciphertext; the operator can run the whole lifecycle without the ability to "
            + "read personal data, and opening an archive outside the running system is an "
            + "owner-only act."),

    PDI_SHRED_LEDGER("Erasures are recorded without personal data and re-applied on every "
            + "restore before serving resumes — an old archive cannot silently resurrect "
            + "an erased person."),

    PDI_RIGHTS_AS_OPERATIONS("Access, portability and restriction are standard machinery "
            + "operations over the vault join, not per-request projects."),

    PDI_EXACT_RESOLUTION("An exact, purpose-stated lookup on a vault-indexed value — a "
            + "claimed identifier (system|value) or an indexed contact point — resolves "
            + "through the vault to the records holding it, served under the caller's "
            + "disclosure mode. The match runs over keyed hashes and every resolution "
            + "leaves a value fingerprint in the disclosure trail; after erasure the "
            + "answer is empty. Anything inexact, unsystemed, or combined with other "
            + "predicates is refused, never half-answered.");

    private final String text;

    DboPromises(String text) {
        this.text = text;
    }

    @Override
    public String text() {
        return text;
    }
}
