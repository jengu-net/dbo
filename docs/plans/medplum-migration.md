# Migration path off Medplum

How jengu moves from Medplum to DBO. Evidence base:
[medplum-usage-inventory.md](../evidence/medplum-usage-inventory.md) (all dependency
dimensions, ranked by replacement difficulty) and
[search-usage-inventory.md](../evidence/search-usage-inventory.md) (the search surface,
already fully inside DBO tier 1).

## State (2026-08-16)

**Identity-first has happened.** Epic
[jengu-platform#844](https://github.com/jengu-net/jengu-platform/issues/844)
(slices dbo#28 + platform #845–#848) made every tenant's DBO authority the
only door into jengu-cloud: login is an OIDC redirect to
`/t/<code>/oidc/authorize`, the platform is a confidential relying party
(operator-provisioned client, custody-driven registration, id_token
principal), platform admins authenticate against the SYSTEM tenant's
authority, and the Medplum password/OIDC login path is deleted. Identities
(Practitioner, PractitionerRole, RoleGrant, dev LocalCredential) provision
from the git config repo over the authority's REST admin surface — the same
path for the embedded local-dev container and the k8s dbo-server. Verified
by the rendered browser flow and the full story-e2e suite (every story
green on the migrated stack). Merge to platform main is pending Alan's
call on the `dbo-rp-migration` branch.

**The division of labour right now:**

- **DBO owns**: authentication and authorization (per-tenant authorities,
  grants, tokens, the identity org-model), for every tenant — provisioned
  at bootstrap AND at runtime tenant creation (a tenant whose authority
  cannot be provisioned fails its delivery). Local-dev embeds DBO in-JVM
  (Felix over the published snapshot bundles); prod runs the operator +
  dbo-server images already deployed on Hetzner.
- **Medplum still owns**: clinical/FHIR storage behind the `MedplumClient`
  seam, zone-content sharing (`Project.link[]` chains — ee CodeSystems and
  the service catalogue still reach tenants through Medplum), and the
  M2M surface's resource storage. Machine (integration-client) tokens
  deliberately STAY on the platform's own authorization server: machines
  are the platform's counterparties, humans are the tenants'.
- **Delivered in DBO but not yet wired into the platform**: zone content
  sync (the §6 SYNC chains, dbo#14) and native terminology (dbo#9) — the
  tenant specs the platform writes are identity-only today. Wiring the
  zone as a DBO tenant carrying canonical CodeSystems and replacing
  `Project.link[]` per content grain is the next groomed slice.

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
| Roles on `ProjectMembership.identifier[]` | **DELIVERED** — per-tenant authorities (§13/§16): active `PractitionerRole` grants × `RoleGrant` records → SMART scopes + roles claim; revocation = period end, enforced at refresh |
| `Project.link[]` zone chains | Declarative content dependencies: each tenant declares which canonical artifacts it needs from which upstream tenant; DBOS-based cross-tenant sync streams read-only, provenance-tagged copies into the tenant store (local override by shadowing). See [§6](../arc42-008-crosscutting/eventing-and-feeds.md) |
| Validation-on-write + ADR 0042 silent-disarm trap | Personality validation (HAPI) with *specified* profile-resolution semantics; the unversioned-canonical rule survives, the trap does not |
| `CodeSystem/$import` (pg param-cap workaround) | Native bulk load (Postgres `COPY`) — the workaround dissolves |
| Same-project `$expand` constraint | Tier-1 `$expand` against the tenant store's replicated terminology |
| Edge wipe via direct SQL into Medplum's schema | First-class erasure API — we own the schema, so ADR 0011's bypass becomes an operation |
| `$expunge` | Erasure-by-drop (tenant DB) + first-class expunge operation; test teardown becomes cheap run-scoped isolation |
| Bot + Agent WS protocol | Already vestigial: drivers execute locally in GraalJS/OSGi; the driver framework and jengu's own edge WS replace `Bot.sourceCode` sync and the agent wire protocol |
| Offset paging (duplicates under concurrent writes) | Keyset/cursor paging — strictly better semantics behind the same `PagedSearch` seam |
| Redis, 30–90s first-boot, pinned image, baseline dumps | Gone — DBO is a set of bundles over Postgres; dev/test boots in-JVM |

## Phases

**Phase 0 — identity extraction. DONE, and differently than first planned.**
The Spring-Authorization-Server destination was superseded: the per-tenant
DBO authorities ARE the authorization server (epic jengu-platform#844).
Medplum is no longer password authority, role store or ticket issuer for
humans; machine-credential minting stays on the platform's own
`/oauth2/token` by design. The identity triad that made the exit hard
(tenancy/identity/roles in Medplum vocabulary) is dissolved for the human
plane; Medplum keeps only the storage-side Project scoping.

**Phase 1 — DBO reaches tier-1 parity behind the seam.** DBO serves the same
FHIR REST surface jengu already speaks (search tier 1, conditional writes,
batches, Subscriptions, `$expand`/`$validate`/`$lookup`). The strangler seam is
per-tenant base URL + client registry: a tenant points at DBO or Medplum, and
the story-e2e suites run identically against both to prove parity.

*Progress:* the embedded in-JVM deployment shape exists and runs in every
local-dev boot and story-suite run — but carrying the identity plane, not
yet the clinical store (Medplum's testcontainer still serves FHIR in
dev/test). The first storage step is now more precisely scoped than "the
testcontainer": **zone/terminology content** — provision the zone as a DBO
tenant, bulk-load the ee CodeSystems into the native terminology store,
stream them to tenants over the §6 SYNC chains, and retire the
`Project.link[]` chain per content grain. Groom it with its own REQ chain
before starting (house rule).

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
The separate jengu-bootstrap Job (ADR 0004) dissolves here too: its privilege
half existed to keep Medplum's master credential out of the runtime, and the
DBO world has no such credential — the operator holds the scoped provisioner
role, serving pods hold per-tenant credentials, and the config-materialisation
lane runs on per-tenant `tenant-bootstrap` M2M clients plus one narrow k8s
RBAC grant (create on TenantRegistration). ADR 0004's concern becomes
structural instead of procedural — which also retires the #24 gap, where the
prod deployment ran the main container as ADMIN anyway. Until then the Job
stays: every one of its Medplum-super-admin reasons is live for as long as
Medplum stores clinical data.

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
