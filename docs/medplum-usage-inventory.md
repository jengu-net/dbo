# Medplum usage inventory (evidence for the migration path)

Snapshot 2026-08-14: every non-search dependency on Medplum across jengu-platform,
lab, VA and jengu-infra. (Search shapes are catalogued separately in
[search-usage-inventory.md](search-usage-inventory.md).) This is the evidence base
for [medplum-migration.md](medplum-migration.md).

Headline: there is **no Medplum SDK anywhere** — all integration is hand-rolled
HTTP against Medplum's REST surface, concentrated in `MedplumClient` +
`PagedSearch` + the client registries. The client *layer* is a clean seam; the
weight is in the **semantics** assumed behind it.

## 1. Identity & auth (deep)

- OAuth2 `client_credentials` machine identity, reimplemented per tier
  (platform, lab edge, VA connector).
- User password auth: `/auth/login` → `/auth/profile` (membership selection) →
  `/oauth2/token`; Medplum is the password authority; roles/practitioner/project
  read from the login response.
- **jengu roles persisted on `ProjectMembership.identifier[]`** — the platform's
  authorization model lives in a Medplum-proprietary resource.
- `UserSecurityRequest` + `/auth/setpassword` drive jengu's invite/reset UX.
- `POST /admin/projects/{id}/client` and `/invite` — ClientApplication and
  user provisioning (non-FHIR admin REST).
- 401-recovery logic tied to Medplum's Login-invalidation behaviour (#836).
- Super-admin vs tenant-scoped clients: REQ-TDI-NO-PRIVILEGED-CREDENTIALS-
  RESIDENT is defined *in Medplum's privilege vocabulary* and test-enforced.
- Edge PIN auth = cached projection of Medplum Practitioner extensions
  (5-minute sync; PIN hash in a jengu extension).
- Delegated/eID JIT provisioning deliberately mints no Medplum User — jengu JWT
  only (the auth-migration direction already reduces this dimension).

## 2. Multi-tenancy (very deep)

- **Project-per-tenant + Project-per-zone + Project-per-MPI** — the tenancy
  model *is* Medplum's Project model; `Project.identifier` is durable tenant
  identity; `Project/$init` creates them.
- **`Project.link[]` zone chains** — tenant projects resolve zone terminology,
  canonicals, devices through Medplum-proprietary cross-project linking with
  **no FHIR equivalent**. The single hardest item.
- **`Project.setting[]`/`secret[]` as credential + config + sync-cursor store**
  (client credentials, `configIsolated`, git `lastSyncedSha`) — secrets live in
  the FHIR store, load-bearing for restart recovery.
- AccessPolicy: *not* load-bearing in the platform (authorization moved
  in-process; legacy jengu repo only).

## 3. Storage/API semantics (deep)

- Conditional create/update/delete as the idempotency workhorse (audit flush,
  git→Medplum canonical sync); batch bundles with per-entry independence;
  optimistic concurrency `If-Match` → 412; JSON Patch; Binary CRUD.
- **Offset paging with duplicate-under-concurrent-write semantics** assumed at
  ~100 call sites (dedupe on type+id, resumable per-page cursors) — wrapped by
  `PagedSearch` after #704.
- **Validation-on-write against `meta.profile` and its version-suffix silent
  disarm** (ADR 0042): the entire shape-versioning design (stamp in
  `meta.extension`, `MetaProfileNormalizer`, `ShapeVersionStamp` in the base
  client) exists *because of* Medplum's profile-resolution quirk.
- Field-level encryption transparent through `EncryptingMedplumClient`
  (relies on cleartext `Patient.identifier` for MPI search).
- `Basic` resources as a generic platform document store (SMART launch
  contexts, integration clients).
- **Direct SQL into Medplum's Postgres schema** for the edge clinical wipe
  (ADR 0011) — CamelCase tables, `_History`, JSONB `content` — schema-level
  coupling, breaks on any store swap.

## 4. Subscriptions & eventing (medium)

- Rest-hook Subscriptions for the binding index (per zone project; criteria
  filter workaround because Medplum doesn't index Subscription.identifier) and
  edge-local Device subscription.
- `Subscription:CREATE` is a **published customer-facing capability** on the
  LIS/VA actor surfaces with a jengu profile — a contract, not an internal.
- Medplum WebSocket subscriptions: unused — jengu runs its own WS layer.

## 5. Terminology & conformance (very deep)

- **`CodeSystem/$import`** (Medplum-only) for 37k-concept loads — exists to
  dodge Postgres' 65535-bound-parameter cap; chunked 5–10k.
- `$expand` requires the CodeSystem in the *same project* → forces zone-project
  replication (`ZoneTerminologyCodeSystemSync`); `$lookup` for display
  resolution.
- StructureDefinition/IG upload into **every zone project** because Medplum
  resolves `baseDefinition` only within a project.
- HAPI in-process validation as second venue (ADR 0039 §3), with Medplum as
  "system-of-record backstop".

## 6. Proprietary surface (consolidated)

`Project` + `$init` + `link[]` + `setting[]`; `ClientApplication`;
`ProjectMembership`; `User`/`UserSecurityRequest`; `CodeSystem/$import`;
`$expunge` (avoided in prod per ADR 0011, but test teardown depends on it and
its async+serial behaviour is a known flakiness source); `_project`;
`/healthcheck`; **`Bot`** (device driver JS synced git→`Bot.sourceCode`,
executed *locally* in GraalJS, not server-side); **`Agent` + Medplum's agent
WebSocket wire protocol** (`agent:connect/heartbeat/transmit/reloadconfig`)
spoken by the lab edge. Not used: Medplum app/admin UI (rejected, ADR 0025),
server-side Bot execution, `$export`/`$everything` (aspirational only).

## 7. Deployment footprint

- Pinned `medplum/medplum-server:5.1.16` (libs.versions.toml; k8s base manifest
  hardcodes it — drift risk).
- Every deployment carries **Redis** solely for Medplum (jengu itself doesn't
  use it): cloud k8s, self-host compose, edge all-in-one (Alpine + OpenRC +
  Postgres + Redis + Medplum server + edge JAR), testcontainers.
- Dev cold-start: 30–90s first-boot migration worked around by a cached
  `pg_dump` baseline per image digest.
- Config flags relied on: vmcontext Bots, introspection, registerEnabled=false,
  raised rate limits, 50mb batches.

## 8. Operational coupling

- `PlatformBootstrapService` (2476 lines) is a Medplum provisioning program:
  zone/tenant Projects, ClientApplications, link chains, invites, orgs, MPI.
- git→Medplum sync lanes (devices, canonicals, terminology, catalogue,
  participants, process definitions…) built on conditional-PUT batches; cursor
  in `Project.setting[]`.
- Tenant deletion cascade, retention sweeps, shape-migration sweep (which
  Medplum cannot index — the stamp rides `meta.extension`), audit storage
  (all `AuditEvent`s in Medplum projects), edge enrolment credential mint.
- Every integration/story test boots a real Medplum container; `$expunge`
  flakiness is a documented pain (#398, #747).

## Ranked: hardest to replace

1. `Project.link[]` cross-project zone-content sharing (no FHIR analogue)
2. Project/ClientApplication/ProjectMembership tenancy + identity triad
3. Validation-on-write semantics incl. the ADR 0042 version-suffix trap
4. `CodeSystem/$import` + same-project `$expand` terminology model
5. Edge wipe's direct SQL into Medplum's schema
6. Bot + Agent WS protocol (device drivers)
7. `Project.setting[]` credential/config/cursor store
8. Offset-paging semantics assumed at ~100 call sites
9. Auth wire flows (mechanically replaceable behind 3 classes)
10. Deployment footprint (heavy but shallow)
