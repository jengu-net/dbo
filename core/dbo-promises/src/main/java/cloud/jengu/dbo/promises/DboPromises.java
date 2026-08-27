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

    SHAPE_QUERYABLE_BY_VERSION("Objects are searchable by shape-stamp bound — below, or "
            + "at and above, a stated major for a stated profile — pageable like any "
            + "search, on every serving surface."),

    SHAPE_STOCK_COUNTED("The tenant inventory counts shape stock per type, profile and "
            + "stamped version — including objects that declare a profile and carry no "
            + "stamp at all — so the same report runs before and after a migration and "
            + "diffs line by line."),

    SHAPE_UNPARSEABLE_VERSION_REFUSED("A pack shape whose version has no parseable "
            + "leading integer major is refused at accept, by name — it would stamp "
            + "objects no version bound can ever match."),

    SHAPE_RESHAPED_IN_PLACE("The store converts stamped objects to a target major in "
            + "place: each rewrite is an ordinary versioned write, so history keeps the "
            + "pre-conversion object with its own stamp and the new version carries the "
            + "new one."),

    SHAPE_RESHAPE_RESUMABLE("A reshape is paged and rate-bounded, hands back a cursor and "
            + "its counts, and a re-run finds only what is still behind."),

    SHAPE_REFUSED_OBJECT_LEFT_BEHIND("An object no converter covers is named and left "
            + "behind rather than stranding the rest; the run reports it and the next run "
            + "tries again."),

    SHAPE_STAMP_OUTLIVES_ITS_PACK("A stamp is a fact about a past accept: withdrawing or "
            + "re-numbering a pack version leaves stock stamped with it findable, "
            + "countable and convertible."),

    SHAPE_NEWER_DATA_REFUSED("An object stamped above what the tenant's pack declares for "
            + "that shape is refused on every read — naming the object, the stamp and the "
            + "pack's version — never served best-effort and never silently omitted from a "
            + "search."),

    SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER("The refusal is a distinct, documented error a consumer "
            + "can gate on, told apart from a fault, a permission and a malformed request."),

    SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING("Claiming stock for conversion elsewhere writes "
            + "nothing and holds nothing: the version check on the way back is the only "
            + "guard, so an abandoned claim strands no data and a duplicated one converges."),

    SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE("Converted forms handed back are re-accepted through "
            + "the face — validated, re-stamped, version-checked — and accounted exactly as "
            + "the in-process lane accounts, so the hardest conversions do not run with the "
            + "least discipline."),

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
