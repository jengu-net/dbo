# Maintenance: backup = export, restore = import (§11)

DBO provides its maintenance functions as one mechanism, not four: **backup is
a scheduled export; restore is an import.** There is no separate backup
format to maintain, verify or bit-rot — the thing you archive is the thing
you can load, and every backup is implicitly restore-tested by the import
path being in daily use — tenant moves, development seeding and adoption
ingest are all this same importer.

An export bundle contains **two elements, each on demand**:

1. **Latest state — portable, idempotent FHIR.** The current version of every
   resource, exported as idempotent upserts (NDJSON per type with
   conditional-write identity), personality/version-tagged. Importing it into
   the same tenant is a no-op; into a fresh tenant, a full restore; into
   *any other FHIR store*, a migration — portability is the anti-lock-in
   promise applied to ourselves. Blob content (Binary) is included by
   manifest + content, hash-verified.
2. **History — high-fidelity, store-specific.** Full version history,
   audit and feed-consumer state as a Postgres dump. To make this clean, the
   tenant database separates concerns **by Postgres schema** (current state /
   history+audit / dbos), so the history element is simply a schema-scoped
   `pg_dump` — restorable byte-exact, without dragging current-state tables
   into a second source of truth. A state-only restore is a valid tenant
   (fresh history begins); state+history restore is a byte-faithful one.

**Owner-key encryption.** The whole bundle is encrypted with a master key
known only to the tenant owner — the platform operates backups it cannot
read, extending credential-blindness (R5) from live credentials to data at
rest in archives. Envelope encryption: DBO encrypts with a data key, the data
key is wrapped with the owner's master key; restore therefore *requires* the
owner's participation — a backup can never quietly become a platform-readable
copy of a tenant, and a stolen archive is ciphertext. (Key custody follows
the veto/co-ownership direction; recovery
options like key escrow with co-owner quorum are the tenant owner's choice,
not the platform's.)

Consistency: the state element is cut at a single snapshot (repeatable-read
or an outbox fence — the feed primitive §10 gives the fence for free: export
up to cursor C, and an incremental export is simply the feed from C).
