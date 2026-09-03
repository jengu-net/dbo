# US-DBO-VENDOR-CHANGE — the clinic leaves, and takes everything with it

> Whatever the reason, the question is the one every provider should be
> able to ask *before* they sign anything: **can I get everything out,
> and can somebody else read it without asking you?**
>
> The answer here is one mechanism rather than two. Backup and export are
> the same operation, so the thing that runs nightly is the thing that
> leaves. An escape route exercised only on the day somebody leaves is an
> escape route nobody has tested, and it will be tested for the first
> time on the worst day of the relationship.
>
> And the archive is sealed to a key the clinic holds. The party
> operating the store is a **custodian, not a reader**: it cannot open
> what it is keeping.

## The scene

The clinic's estate is a patient, an observation, and everything the store
derived from them. What leaves has to be all of it, and what arrives has to be
usable rather than a pile of documents nobody can find.

## Everything leaves as one sealed file

The export is a consistent snapshot rather than whatever the writer happened
to see mid-stream, and nothing readable survives in the file. A name and a
national identifier are both absent from the bytes the platform is storing.

Somebody else's key opens nothing, and the refusal comes from the seal before
any signature is looked at. That ordering matters: a file that could be
partially interpreted before its key was checked would be a file with a
partial answer for anybody who steals it.

## And goes back in somewhere else

It restores into a store the old vendor does not run, keeping the identities
the clinic's other systems already refer to. Searches hit immediately, because
every projection is derived from the payload and rebuilt on the way in.

That is the difference between a move and a dump. An archive whose contents
cannot be found again is a compliance artefact rather than portability.

History is restored by a choice the operator makes rather than by whatever the
archive happened to contain. A portable restore starts fresh: the new store
does not pretend to have witnessed edits it never saw, which is a different
claim from a backup restored into the store that made it.

## Retrying is safe

Restoring the same archive twice changes nothing. What is already there is
recognised as identical rather than rewritten, so a restore interrupted
halfway can simply be run again — which is the only way anybody actually
operates one.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-MNT-BACKUP-IS-EXPORT` | Backup and export are one mechanism, restore and import another single one; every backup is restorable by the everyday import path. | PROVEN |
| `REQ-DBO-MNT-SNAPSHOT-CONSISTENT` | The state element is cut at a single consistent snapshot; incremental export is the feed from that snapshot's cursor. | PROVEN |
| `REQ-DBO-MNT-OWNER-KEY-ENCRYPTION` | An export bundle is encrypted so that only the tenant owner's master key can open it; the platform operates backups it cannot read, and restore requires the owner. | PROVEN |
| `REQ-DBO-MNT-PORTABLE-STATE-EXPORT` | The latest-state export is idempotent, store-independent FHIR (with blob content, hash-verified) — importable into a fresh tenant, the same tenant, or any other FHIR store. It travels as Bulk Data: NDJSON per type whose resources carry their own id and version, beside the manifest that spec defines — same digests the archive was attested over, so a stranger checking the export and a party checking the signatures cannot get different answers. | PROVEN |
| `REQ-DBO-MNT-HISTORY-BY-SCHEMA` | Version history, audit and consumer state live in their own database schemas, so the high-fidelity history element is a schema-scoped dump, restorable byte-exact. | PROVEN |
| `REQ-DBO-MNT-ARCHIVE-ROOT-OVER-CONTENTS` | An archive's attested root is computed over the manifest's per-entry digests rather than over the archive's bytes, so re-packing, re-compressing or reordering does not invalidate what was attested. | PROVEN |
| `REQ-DBO-MNT-BOTH-PARTIES-ATTEST` | An archive carries two detached signatures over that root — the vendor's and the tenant's — and the tenant countersigns without resealing, so neither party can produce an attested archive alone. | PROVEN |
| `REQ-DBO-MNT-IMPORT-REFUSES-UNATTESTED` | Objects enter a store from an archive by one path only: the root recomputes and both signatures verify, or nothing is written. A refusal names what was wrong with the archive rather than failing part-way through it. | PROVEN |
| `REQ-DBO-MNT-ATTESTATION-READS-AS-FHIR` | An archive's attestation renders as a `Provenance` carrying FHIR's `Signature`, so a customer's own tooling can check what it was handed without learning this store's JSON. A view rendered by the face, never the truth form — an archive of a non-FHIR domain is attested the same way and has no Provenance. | PROVEN |
| `REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED` | A destination records the root it accepted and the two keys that signed it, in the tenant's own audit trail, so what was imported and what both parties said it was stays answerable without the archive. | PROVEN |
| `REQ-DBO-CORE-REINDEX-IS-AN-OPERATION` | Changing how objects are indexed is a background operation, never a data migration. | PROVEN |
| `REQ-DBO-POL-POLICY-REPLAY-ON-RESTORE` | Before a restored tenant serves, the machinery re-applies the shred ledger and the retention sweep — an archive cannot resurrect what policy required gone; archives carry removeAfter themselves. | PROVEN |

Coverage: {PROVEN=12} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Blob content has nowhere to go.** Binary content lives in the payload
  today, so an estate with attachments is an estate the archive carries
  inline.
- **No partial export.** It is the tenant or nothing; there is no "everything
  for these patients".

## Open decisions

- **Whether a receiving store should record where an archive came from** as
  more than provenance on the objects. Today the accepted root is recorded and
  the relationship between two stores is not.
