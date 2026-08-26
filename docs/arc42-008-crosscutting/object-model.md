# Object model and persistence (§2–§3)

## 2. Object model (from the legacy durable core)

- **Payload** — the resource as opaque canonical JSON, the single source of truth.
  Never partially updated; a write replaces the payload.
- **Envelope** — a derived, typed, searchable projection recomputed from the payload
  on every write by the bound personality (for FHIR: from SearchParameter
  definitions). Stored beside the payload; carries all indexes. Because it is
  derived, it can always be rebuilt — reindexing is an operation, not a migration.
- **Identity** — one internal id + N external `{system, value}` identifiers,
  extracted from the payload, unique per (tenant, type, system, value).
- **References** — extracted edges `(owner, refType, target)`, owner-controlled
  (delete + reinsert on owner write), powering `_include`/`_revinclude`-class reads
  and referential queries.
- **History** — every write appends a version row (FHIR `versionId`, `_history`);
  the outbox row is written in the same transaction (D1: read-your-writes).
- **Type/version upgrade-on-read** — payloads carry their schema version; converters
  registered by personalities upgrade old payloads lazily, so FHIR version
  transition (R5 → R6) inside a tenant is a converter + reindex, not a big-bang
  migration.
- **Shape stamp** — the version of each tenant-pack profile the object was
  validated against at accept, recorded as an envelope dimension and served in
  `meta`. The second version axis, independent of `payload_version` — see
  [shape versioning](shape-versioning.md).

## 3. Postgres layout (per tenant database)

Sketch, per domain (a physical table-group knob kept from legacy):

```
<domain>_data        (tenant-implicit: whole DB is one tenant in the dedicated tier)
  id, resource_type, version_id, fhir_version, last_updated,
  envelope JSONB (typed values), payload JSONB or BYTEA, deleted
<domain>_history     (append-only version rows)
<domain>_identifier  (system, value, resource_id; unique)
<domain>_reference   (owner_id, ref_type, target_type, target_id)
<domain>_outbox      (DBOS-managed stream / transactional outbox)
```

- GIN/expression indexes on envelope paths declared by the personality's search
  parameters — indexing is part of the personality contract, not an afterthought.
- In a shared-tier database, the same layout gains a `tenant_id` column in every
  PK + RLS policies; the dedicated tier stays the design anchor (R5).
- DBOS state splits into two planes — platform DB for coordination that carries
  no resource content, the tenant's own DB for durable work whose checkpoints
  do (resolved in §7.4).

