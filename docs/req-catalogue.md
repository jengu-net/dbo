# DBO requirement catalogue

The founding requirements ([requirements.md](requirements.md)) and concepts
([concepts.md](concepts.md)) distilled into stable REQ-style IDs, grouped by
capability area. Each REQ is a business-readable promise; the source column
traces it back. Tier-2/3 search features and migration-program exit criteria
deliberately have no REQs yet — they get them when scheduled.

## CORE — object engine

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-CORE-PAYLOAD-IS-TRUTH | A stored object's payload is the single source of truth; every searchable projection is derived from it and can always be rebuilt. | §2 |
| REQ-DBO-CORE-DECLARED-TRUTH-FORM | Which representation is authoritative for a type (payload or normalized form) is declared by its personality, never implicit. | §6 |
| REQ-DBO-CORE-REINDEX-IS-AN-OPERATION | Changing how objects are indexed is a background operation, never a data migration. | §2 |
| REQ-DBO-CORE-EXTERNAL-IDENTIFIERS | Every object has one internal id and any number of `{system, value}` identifiers, rebuilt from the payload on each write and searchable together. | §2 |
| REQ-DBO-CORE-REFERENCE-EDGES | References between objects are extracted as owned edges on write and power referential reads. | §2 |
| REQ-DBO-CORE-VERSIONED-HISTORY | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | §2, D1 |
| REQ-DBO-CORE-READ-YOUR-WRITES | A write returns only after its data and its change event are committed in one transaction. | D1 |
| REQ-DBO-CORE-UPGRADE-ON-READ | Old payload versions are upgraded lazily by registered converters; a schema-version transition never requires a big-bang rewrite. | §2 |
| REQ-DBO-CORE-PARAMETERIZED-SQL | No value is ever concatenated into SQL text. | D2 |
| REQ-DBO-CORE-SIBLING-MODELS | Non-FHIR object models ride the same engine as FHIR resources, not beside it. | R6 |

## CONT — container & embedding

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-CONT-FRAMEWORK-FREE-CORE | The core is plain Java; no Spring/Micronaut-class framework dependency anywhere in the engine. | R1, R2 |
| REQ-DBO-CONT-DYNAMIC-TENANT-SERVICES | Tenants arrive, move and leave as OSGi service-registry dynamics — never a process restart. | R2, §4 |
| REQ-DBO-CONT-EMBEDDED-IN-JVM | A host application can boot the full store inside its own JVM for dev/test; the only shared dependencies are Felix and the OSGi API. | R2 |
| REQ-DBO-CONT-PRIVATE-DEPENDENCIES | Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and served through DBO-owned whiteboard interfaces; their types never cross bundle boundaries. | §7.1, §7.3 |
| REQ-DBO-CONT-FAST-COLD-START | Store startup against an already-current schema is fast enough for embedded test use; schema setup detects currency instead of replaying changelogs. | §9 |

## TEN — tenancy & isolation

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-TEN-STRUCTURAL-SCOPING | No code path can read or write data without an explicit tenant context. | R3 |
| REQ-DBO-TEN-DEDICATED-DATABASE-TIER | A tenant can run on a dedicated database; this tier is the design anchor. | R5 |
| REQ-DBO-TEN-CREDENTIAL-BLIND-PROVISIONING | Tenant databases and buckets are provisioned by an external operator; credentials exist only as platform secrets and are never readable by tenant-manager code. | R5, §4 |
| REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS | Application code obtains a tenant's data services from the service registry and can use them without ever seeing credentials. | R5, §4 |
| REQ-DBO-TEN-ERASURE-BY-DROP | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. | §7.4, §9 |
| REQ-DBO-TEN-SHARED-TIER-ISOLATION | Tenants on the shared tier are isolated by tenant-keyed schemas and row-level security with the same API surface as the dedicated tier. | §3 |
| REQ-DBO-TEN-FAIRNESS-QUOTAS | Per-tenant quotas and rate limits are first-class configuration, enforced at the serving pod. | §9 |

## VER — version plurality (personalities)

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-VER-VERSION-AGNOSTIC-CORE | The engine has no knowledge of any FHIR version; all version meaning lives in personality bundles. | R6, §1 |
| REQ-DBO-VER-CONCURRENT-VERSIONS | Tenants (and domains within a tenant) on different FHIR versions run concurrently in one container. | R6 |
| REQ-DBO-VER-PERSONALITY-OWNS-MEANING | Parsing, validation, search-parameter extraction and subscription evaluation are personality responsibilities, per version. | §1 |
| REQ-DBO-VER-SPECIFIED-VALIDATION | Profile-resolution and validation semantics are specified by DBO — a malformed or versioned canonical reference can never silently disable validation. | §7.6, ADR 0042 lesson |
| REQ-DBO-VER-TRANSITION-BY-CONVERTERS | Moving a tenant between FHIR versions is converters plus reindex, not a data migration ceremony. | §2, R6 |

## SRCH — search

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-SRCH-TIER1-PARITY | Every search feature jengu uses in production today works identically ([inventory](search-usage-inventory.md)). | §7.5 |
| REQ-DBO-SRCH-STRICT-BY-DEFAULT | An unsupported search parameter is rejected, never silently ignored. | §7.5 |
| REQ-DBO-SRCH-HONEST-CAPABILITY | Each personality's CapabilityStatement is generated from what is actually implemented. | §7.5 |
| REQ-DBO-SRCH-TYPED-ORDERING | Sorting and range filtering are typed — numeric, date and token semantics are correct, with matching indexes. | D3 |
| REQ-DBO-SRCH-DECLARED-INDEXES | Indexing (including side tables for hard parameters) is declared by the personality as part of its search contract, from day one. | §3, §9 |
| REQ-DBO-SRCH-CUSTOM-PARAMETERS | A tenant or module can register a custom search parameter; extraction, reindex and the new index follow automatically. | §7.5 |

## FEED — feeds, pagination, synchronization

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-FEED-ONE-PRIMITIVE | Pagination, subscription delivery, content streams and edge sync are all the same primitive: an ordered, replayable sequence with an opaque durable cursor. | §10 |
| REQ-DBO-FEED-KEYSET-CURSORS | Cursors are keyset positions, never offsets; a page is stable under concurrent writes. | §10, D3 |
| REQ-DBO-FEED-PUSH-ACK-RESUME | Push consumers acknowledge with the cursor; any interrupted stream resumes from the last acknowledged position. | §10 |
| REQ-DBO-FEED-IDEMPOTENT-DELIVERY | Delivery is at-least-once with idempotent apply by identity and version. | §10 |
| REQ-DBO-FEED-NAMED-CONSUMERS | Every durable consumer holds a named cursor in the store; progress, lag and replay are uniformly observable. | §10 |
| REQ-DBO-FEED-LEAN-WIRE-OPTION | Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are assembled only at the FHIR surface. | §10 |

## EVT — eventing & subscriptions

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-EVT-TRANSACTIONAL-OUTBOX | Every change event originates as an outbox row committed with the write. | R8, §6 |
| REQ-DBO-EVT-FHIR-SUBSCRIPTIONS | Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 personality) are a core capability. | R8 |
| REQ-DBO-EVT-DURABLE-DELIVERY | Subscription delivery is durable, tenant-scoped and replayable, with retries, backoff and dead-lettering. | R8, §9 |
| REQ-DBO-EVT-IN-PROCESS-SURFACE | Co-located consumers get the same topics with identical semantics through the in-process/OSGi surface. | R8 |

## WF — durable work & planes

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-WF-POSTGRES-SUBSTRATE | Durable tasks, streams and inter-instance communication run on the DBOS/Postgres substrate; no external broker. | R4 |
| REQ-DBO-WF-TWO-PLANES | Workflow state lives where its content belongs: platform plane for coordination, tenant plane for anything carrying resource content. | §7.4 |
| REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE | Platform-plane workflow parameters and checkpoints never contain tenant credentials or resource content. | §7.4 |
| REQ-DBO-WF-DECLARED-STEP-PLANE | Every workflow step declares its plane at definition time. | §7.4 |
| REQ-DBO-WF-PLATFORM-COORDINATED-HOPS | Every cross-plane or cross-tenant hop is coordinated by the platform; no direct tenant-to-tenant connection exists. | §7.4 |
| REQ-DBO-WF-HOPS-AUDITED | Every hop produces sender egress, receiver ingress and platform coordination records — audit is structural, not per-integration. | §7.4 |
| REQ-DBO-WF-GRANTS-FROM-CATALOGUE | A hop grant can only be issued for a hop the declared process shape contains. | §8 |

## SCAL — scaling & routing

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-SCAL-DURABLE-ASSIGNMENT | The tenant→pod assignment is durable state with version-driven takeover. | §5, D5 |
| REQ-DBO-SCAL-SINGLE-WRITER-TENANT | A tenant's serving pod is its single writer, making local caching and local subscription state correct by construction. | §5, §9 |
| REQ-DBO-SCAL-TRANSPARENT-ROUTING | Callers look up a tenant's service in the registry; local instance or remote proxy is indistinguishable. | §5 |
| REQ-DBO-SCAL-TWO-HOP-LOCALITY | Requests enter at the closest public node (Kubernetes locality), then route to the serving pod (tenant assignment). | §5 |
| REQ-DBO-SCAL-NO-SHARED-STATE-BROKER | The architecture requires no Redis-class shared-state service. | §9 |

## TERM — terminology

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-TERM-NATIVE-FORM | Terminology lives in a normalized, query-optimized form; the FHIR resource form is a wire projection assembled on demand. | §6 |
| REQ-DBO-TERM-BULK-LOAD | Loading a large CodeSystem is a native bulk operation — no chunking workarounds, no parameter-cap ceilings. | §6, §7.6 |
| REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM | `$expand`, `$lookup` and `validate-code` are served from the normalized form at tenant-local speed. | §6, §7.5 |

## SYNC — canonical content dependencies

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-SYNC-DECLARED-ONLY | Cross-tenant content synchronization happens only for declared dependencies; nothing syncs undeclared. | §6 |
| REQ-DBO-SYNC-CODESYSTEM-GRAIN | The dependency unit is the CodeSystem together with its related ValueSets. | §6 |
| REQ-DBO-SYNC-PROVENANCE-COPIES | Streamed copies are read-only and provenance-tagged with source tenant and version; updates and retirements propagate through the same stream. | §6 |
| REQ-DBO-SYNC-LOCAL-SHADOWING | A tenant's own object with the same canonical identity overrides the streamed copy; removing the override falls back to the live upstream version. | §6 |
| REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY | A tenant declares dependencies only against its direct upstream; chains compose hop by hop. | §6 |

## PROC — process catalogue & map

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-PROC-CATALOGUE-IN-STORE | Process and step definitions (with profiles, planes and projections) are part of DBO's own vocabulary; projections are generated, never hand-edited. | §8 |
| REQ-DBO-PROC-DOMAIN-CODE-FILTER | Every process and step carries a free-string process-domain code; views and projections filter by it. | §8 |
| REQ-DBO-PROC-NETWORK-MAP | The network answers which processes are known and running, where and in which version — scanned from bundles and accumulated across nodes. | §8 |
| REQ-DBO-PROC-TRACE-JOIN | From any process instance, the steps and the exact resource diffs and audit records they produced are navigable. | §8 |

## OPS — operations

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-OPS-TENANT-BLOB-STORAGE | Binary content lives in per-tenant blob storage provisioned credential-blind; erasure-by-drop extends to it; small deployments fall back to Postgres behind the same interface. | §9 |
| REQ-DBO-OPS-MIGRATION-AS-DEPLOYMENT | Schema and engine upgrades ride rolling deployment: the highest-version node leads, migrates, and older nodes passivate. | D5 |

## MNT — maintenance

| REQ | Promise | Source |
|---|---|---|
| REQ-DBO-MNT-BACKUP-IS-EXPORT | Backup and export are one mechanism, restore and import another single one; every backup is restorable by the everyday import path. | §11 |
| REQ-DBO-MNT-PORTABLE-STATE-EXPORT | The latest-state export is idempotent, store-independent FHIR (with blob content, hash-verified) — importable into a fresh tenant, the same tenant, or any other FHIR store. | §11 |
| REQ-DBO-MNT-HISTORY-BY-SCHEMA | Version history, audit and consumer state live in their own database schemas, so the high-fidelity history element is a schema-scoped dump, restorable byte-exact. | §11 |
| REQ-DBO-MNT-OWNER-KEY-ENCRYPTION | An export bundle is encrypted so that only the tenant owner's master key can open it; the platform operates backups it cannot read, and restore requires the owner. | §11 |
| REQ-DBO-MNT-SNAPSHOT-CONSISTENT | The state element is cut at a single consistent snapshot; incremental export is the feed from that snapshot's cursor. | §11, §10 |
