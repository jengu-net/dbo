# Personal-data isolation (§14)

GDPR protection is structural in dbo, not procedural: personal data is
isolated by the storage engine itself, so the rights of access, portability
and erasure are machinery operations — and the operator can run the whole
system, including backup and restore, without ever being able to read a
person's data.

## 14.1 The conflict this resolves

dbo keeps every version of every object immutably (§2), and its maintenance
archives are byte-faithful (§11). The GDPR right to erasure cannot be
honoured by rewriting history without destroying those properties. The
resolution is **crypto-shredding**: personal data is encrypted at rest with
a per-person key; erasure destroys the key. History stays byte-immutable
(ciphertext remains), archives already taken stay valid as files, and the
person's data is cryptographically gone from the live store, from history,
and from every archive that carried ciphertext.

## 14.2 The vault and the pseudonymous main store

Each tenant contains a **person vault**. Identifying material — the
elements that make a record a person's record (names, personal codes,
telecoms, addresses; declared per type/element by the personality's PDI
map) — lives encrypted under that person's key. The main store holds the
record with identifying elements replaced by the person's **pseudonym**;
the engine reassembles the full resource for authorized reads
(element-level split: callers see normal FHIR; the isolation is beneath
the API).

Envelopes are part of the contract: search keys extracted from personal
elements are indexes, and an index is a copy. Personal-classified elements
either index vault-side or are rebuilt on shred — after erasure the person
is unfindable, not merely unreadable.

jengu already practices this shape by hand (the lab module never stores
patient identity; the visit-assistant edge carries no PHI). §14 makes it
the store's standard instead of each module's discipline.

## 14.3 Three keys, three holders

| Key | Encrypts | Held by |
|---|---|---|
| Per-person data key | the person's identifying elements, everywhere (live, history, envelope entries, archives) | the tenant's vault, wrapped by the working key |
| Tenant working key | the vault's person keys | platform secret custody (same pattern as DB credentials; Vault rotation later) — the SERVING MACHINERY, never a human |
| Owner master key | the archive data key (§11, built) | the data owner only |

Consequences:

- **Blind operations.** Backup and restore are machinery-driven end to end:
  archives contain ciphertext (person layer) inside a sealed envelope
  (owner layer). The operator can create, move, store and restore them
  without any ability to read tenant data. Opening an archive outside the
  running system is an owner-only act.
- **Serving without ceremony.** Authorized requests get fully reassembled
  resources because the working key is in the running system's custody —
  no owner interaction per request, no human in the loop.
- **Erasure reaches archives.** Shredding the person key erases the person
  from every ciphertext copy at once, including archives nobody can
  enumerate anymore.

## 14.4 The shred ledger

An archive taken before an erasure contains the person's wrapped key;
restoring it would resurrect the person. Erasures are therefore recorded in
a **shred ledger** (pseudonym + key fingerprint + timestamp — no personal
data), and restore always re-applies the ledger before serving resumes.
Archive retention policy bounds the window. The ledger itself is ordinary
tenant data: exported, restored, and synced like everything else.

## 14.5 GDPR rights as operations

- **Erasure** — shred the person key; rebuild affected envelopes; ledger
  entry. History and archives need no rewriting.
- **Access / portability** — vault-joined export of one person's records
  through the standard maintenance machinery.
- **Restriction** — a flag on the vault entry the serving path honours.

## 14.6 What stays outside

Consent semantics and veto co-ownership (who must agree before the working
key may unwrap a given person's key) are the platform's ADR 0016 track —
§14 provides the key seams they attach to. Anonymisation pipelines
(deriving statistics from shredded records) consume what §14 leaves behind
by construction: pseudonymous, unlinkable records.
