# Migration path off Medplum

How jengu moves from Medplum to DBO. Evidence base:
[medplum-usage-inventory.md](medplum-usage-inventory.md) (all dependency
dimensions, ranked by replacement difficulty) and
[search-usage-inventory.md](search-usage-inventory.md) (the search surface,
already fully inside DBO tier 1).

## The central finding

The hard coupling is **not storage**. CRUD, search, conditional writes, batches
and subscriptions map onto DBO tier 1 almost mechanically, behind an already-
clean seam (`MedplumClient`/`PagedSearch`/client registries — no SDK anywhere).
The hard coupling is that **tenancy, machine identity, roles and zone-content
sharing are expressed in Medplum's proprietary vocabulary**: Project-per-tenant,
ClientApplication, roles on `ProjectMembership.identifier[]`, credentials in
`Project.setting[]`, and `Project.link[]` zone chains with no FHIR equivalent.

Consequence: the migration is **identity-first, storage-second** — and each
proprietary mechanism maps to a DBO concept that is *structurally better*, not
just equivalent.

| Medplum mechanism | DBO replacement |
|---|---|
| Project-per-tenant, `$init` | Tenant = dedicated database + OSGi service set (R3/R5); registration via the k8s operator |
| ClientApplication + creds in `Project.setting[]` | Credential-blind provisioning — secrets in k8s, never in the store or the management plane |
| Roles on `ProjectMembership.identifier[]` | jengu's own identity plane (Spring Authorization Server workstream) |
| `Project.link[]` zone chains | Declarative content dependencies: each tenant declares which canonical artifacts it needs from which upstream tenant; DBOS-based cross-tenant sync streams read-only, provenance-tagged copies into the tenant store (local override by shadowing). See concepts.md §6 |
| Validation-on-write + ADR 0042 silent-disarm trap | Personality validation (HAPI) with *specified* profile-resolution semantics; the unversioned-canonical rule survives, the trap does not |
| `CodeSystem/$import` (pg param-cap workaround) | Native bulk load (Postgres `COPY`) — the workaround dissolves |
| Same-project `$expand` constraint | Tier-1 `$expand` against the tenant store's replicated terminology |
| Edge wipe via direct SQL into Medplum's schema | First-class erasure API — we own the schema, so ADR 0011's bypass becomes an operation |
| `$expunge` | Erasure-by-drop (tenant DB) + first-class expunge operation; test teardown becomes cheap run-scoped isolation |
| Bot + Agent WS protocol | Already vestigial: drivers execute locally in GraalJS/OSGi; the driver framework and jengu's own edge WS replace `Bot.sourceCode` sync and the agent wire protocol |
| Offset paging (duplicates under concurrent writes) | Keyset/cursor paging — strictly better semantics behind the same `PagedSearch` seam |
| Redis, 30–90s first-boot, pinned image, baseline dumps | Gone — DBO is a set of bundles over Postgres; dev/test boots in-JVM |

## Phases

**Phase 0 — identity extraction (already planned, runs on Medplum).** The
Spring Authorization Server workstream removes Medplum as password authority,
role store and ticket issuer *before* DBO arrives. Everything in inventory §1
except machine-credential minting collapses into jengu's own auth plane. This
is the prerequisite that makes the rest tractable — and it pays off even if DBO
slipped.

**Phase 1 — DBO reaches tier-1 parity behind the seam.** DBO serves the same
FHIR REST surface jengu already speaks (search tier 1, conditional writes,
batches, Subscriptions, `$expand`/`$validate`/`$lookup`). The strangler seam is
per-tenant base URL + client registry: a tenant points at DBO or Medplum, and
the story-e2e suites run identically against both to prove parity. Embedded
in-JVM DBO replaces the Medplum testcontainer for dev/test first — that is the
lowest-risk, highest-payoff first deployment (kills the container cold-start,
the baseline-dump machinery and `$expunge` flakiness in one move).

**Phase 2 — tenant-by-tenant flip, greenfield style.** Pre-launch posture: no
migration ceremony. New tenants provision onto DBO via the operator flow;
existing dev/demo tenants are *recreated* from git (git is the config source of
truth — bootstrap re-provisions them identically). Any tenant with clinical
data that must survive exports R4 NDJSON from Medplum and imports through the
R4 personality's streaming Bundle/NDJSON ingest; upgrade toward R5/R6 happens
later via upgrade-on-read converters, not at migration time.

**Phase 3 — edge.** The edge all-in-one container drops Medplum + Redis +
its config-file super-admin; the edge JVM hosts DBO bundles directly (one
process: edge app + embedded store). Edge wipe, minimisation and sync cursors
move from schema-poking SQL to DBO's erasure/feed APIs.

**Phase 4 — decommission.** Remove the Medplum client layer, the vmcontext Bot
config, the rotation CronJob, the baseline tooling; retire the pinned image.

## What must be re-proven at parity (phase 1 exit criteria)

- The story-e2e suites green against DBO-backed tenants (world stays real).
- Shape-versioning semantics: profile validation with unversioned canonicals,
  stamp handling, and — what Medplum could never do — the shape stamp as an
  *indexed* search dimension, so migration sweeps stop walking full stores.
- Subscription contract (`jengu-lis-subscription` etc.) — customer-facing;
  channel semantics must match the published profile exactly.
- Field-level encryption pattern (cleartext identifiers for MPI search).
- Audit trail continuity: `AuditEvent` write paths, conditional-create
  idempotency, platform/tenant dual-logging.

## Non-goals

No dual-write period, no live-sync between stores, no Medplum-compat layer
beyond the FHIR surface jengu actually uses. The seam is the FHIR API + the
provisioning workflows; everything proprietary is replaced by a DBO-native
concept, not emulated.
