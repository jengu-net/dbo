# DBO requirement catalogue

The founding requirements ([requirements.md](requirements.md)) and concepts
([concepts.md](concepts.md)) distilled into stable REQ-style IDs, grouped by
capability area. Each REQ is a business-readable promise; the source column
traces it back. Tier-2/3 search features and migration-program exit criteria
deliberately have no REQs yet — they get them when scheduled.

## CORE — object engine

| REQ | Promise | Source |
|---|---|---|
| REQ-CORE-PAYLOAD-IS-TRUTH | A stored object's payload is the single source of truth; every searchable projection is derived from it and can always be rebuilt. | §2 |
| REQ-CORE-DECLARED-TRUTH-FORM | Which representation is authoritative for a type (payload or normalized form) is declared by its personality, never implicit. | §6 |
| REQ-CORE-REINDEX-IS-AN-OPERATION | Changing how objects are indexed is a background operation, never a data migration. | §2 |
| REQ-CORE-EXTERNAL-IDENTIFIERS | Every object has one internal id and any number of `{system, value}` identifiers, rebuilt from the payload on each write and searchable together. | §2 |
| REQ-CORE-REFERENCE-EDGES | References between objects are extracted as owned edges on write and power referential reads. | §2 |
| REQ-CORE-VERSIONED-HISTORY | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | §2, D1 |
| REQ-CORE-READ-YOUR-WRITES | A write returns only after its data and its change event are committed in one transaction. | D1 |
| REQ-CORE-UPGRADE-ON-READ | Old payload versions are upgraded lazily by registered converters; a schema-version transition never requires a big-bang rewrite. | §2 |
| REQ-CORE-PARAMETERIZED-SQL | No value is ever concatenated into SQL text. | D2 |
| REQ-CORE-SIBLING-MODELS | Non-FHIR object models ride the same engine as FHIR resources, not beside it. | R6 |

## CONT — container & embedding

| REQ | Promise | Source |
|---|---|---|
| REQ-CONT-FRAMEWORK-FREE-CORE | The core is plain Java; no Spring/Micronaut-class framework dependency anywhere in the engine. | R1, R2 |
| REQ-CONT-DYNAMIC-TENANT-SERVICES | Tenants arrive, move and leave as OSGi service-registry dynamics — never a process restart. | R2, §4 |
| REQ-CONT-EMBEDDED-IN-JVM | A host application can boot the full store inside its own JVM for dev/test; the only shared dependencies are Felix and the OSGi API. | R2 |
| REQ-CONT-PRIVATE-DEPENDENCIES | Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and served through DBO-owned whiteboard interfaces; their types never cross bundle boundaries. | §7.1, §7.3 |
| REQ-CONT-FAST-COLD-START | Store startup against an already-current schema is fast enough for embedded test use; schema setup detects currency instead of replaying changelogs. | §9 |

## TEN — tenancy & isolation

| REQ | Promise | Source |
|---|---|---|
| REQ-TEN-STRUCTURAL-SCOPING | No code path can read or write data without an explicit tenant context. | R3 |
| REQ-TEN-DEDICATED-DATABASE-TIER | A tenant can run on a dedicated database; this tier is the design anchor. | R5 |
| REQ-TEN-CREDENTIAL-BLIND-PROVISIONING | Tenant databases and buckets are provisioned by an external operator; credentials exist only as platform secrets and are never readable by tenant-manager code. | R5, §4 |
| REQ-TEN-REGISTRY-SCOPED-ACCESS | Application code obtains a tenant's data services from the service registry and can use them without ever seeing credentials. | R5, §4 |
| REQ-TEN-ERASURE-BY-DROP | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. | §7.4, §9 |
| REQ-TEN-SHARED-TIER-ISOLATION | Tenants on the shared tier are isolated by tenant-keyed schemas and row-level security with the same API surface as the dedicated tier. | §3 |
| REQ-TEN-FAIRNESS-QUOTAS | Per-tenant quotas and rate limits are first-class configuration, enforced at the serving pod. | §9 |

## VER — version plurality (personalities)

| REQ | Promise | Source |
|---|---|---|
| REQ-VER-VERSION-AGNOSTIC-CORE | The engine has no knowledge of any FHIR version; all version meaning lives in personality bundles. | R6, §1 |
| REQ-VER-CONCURRENT-VERSIONS | Tenants (and domains within a tenant) on different FHIR versions run concurrently in one container. | R6 |
| REQ-VER-PERSONALITY-OWNS-MEANING | Parsing, validation, search-parameter extraction and subscription evaluation are personality responsibilities, per version. | §1 |
| REQ-VER-SPECIFIED-VALIDATION | Profile-resolution and validation semantics are specified by DBO — a malformed or versioned canonical reference can never silently disable validation. | §7.6, ADR 0042 lesson |
| REQ-VER-TRANSITION-BY-CONVERTERS | Moving a tenant between FHIR versions is converters plus reindex, not a data migration ceremony. | §2, R6 |

## SRCH — search

| REQ | Promise | Source |
|---|---|---|
| REQ-SRCH-TIER1-PARITY | Every search feature jengu uses in production today works identically ([inventory](search-usage-inventory.md)). | §7.5 |
| REQ-SRCH-STRICT-BY-DEFAULT | An unsupported search parameter is rejected, never silently ignored. | §7.5 |
| REQ-SRCH-HONEST-CAPABILITY | Each personality's CapabilityStatement is generated from what is actually implemented. | §7.5 |
| REQ-SRCH-TYPED-ORDERING | Sorting and range filtering are typed — numeric, date and token semantics are correct, with matching indexes. | D3 |
| REQ-SRCH-DECLARED-INDEXES | Indexing (including side tables for hard parameters) is declared by the personality as part of its search contract, from day one. | §3, §9 |
| REQ-SRCH-CUSTOM-PARAMETERS | A tenant or module can register a custom search parameter; extraction, reindex and the new index follow automatically. | §7.5 |

## FEED — feeds, pagination, synchronization

| REQ | Promise | Source |
|---|---|---|
| REQ-FEED-ONE-PRIMITIVE | Pagination, subscription delivery, content streams and edge sync are all the same primitive: an ordered, replayable sequence with an opaque durable cursor. | §10 |
| REQ-FEED-KEYSET-CURSORS | Cursors are keyset positions, never offsets; a page is stable under concurrent writes. | §10, D3 |
| REQ-FEED-PUSH-ACK-RESUME | Push consumers acknowledge with the cursor; any interrupted stream resumes from the last acknowledged position. | §10 |
| REQ-FEED-IDEMPOTENT-DELIVERY | Delivery is at-least-once with idempotent apply by identity and version. | §10 |
| REQ-FEED-NAMED-CONSUMERS | Every durable consumer holds a named cursor in the store; progress, lag and replay are uniformly observable. | §10 |
| REQ-FEED-LEAN-WIRE-OPTION | Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are assembled only at the FHIR surface. | §10 |

## EVT — eventing & subscriptions

| REQ | Promise | Source |
|---|---|---|
| REQ-EVT-TRANSACTIONAL-OUTBOX | Every change event originates as an outbox row committed with the write. | R8, §6 |
| REQ-EVT-FHIR-SUBSCRIPTIONS | Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 personality) are a core capability. | R8 |
| REQ-EVT-DURABLE-DELIVERY | Subscription delivery is durable, tenant-scoped and replayable, with retries, backoff and dead-lettering. | R8, §9 |
| REQ-EVT-IN-PROCESS-SURFACE | Co-located consumers get the same topics with identical semantics through the in-process/OSGi surface. | R8 |

## WF — durable work & planes

| REQ | Promise | Source |
|---|---|---|
| REQ-WF-POSTGRES-SUBSTRATE | Durable tasks, streams and inter-instance communication run on the DBOS/Postgres substrate; no external broker. | R4 |
| REQ-WF-TWO-PLANES | Workflow state lives where its content belongs: platform plane for coordination, tenant plane for anything carrying resource content. | §7.4 |
| REQ-WF-CONTENT-FREE-PLATFORM-PLANE | Platform-plane workflow parameters and checkpoints never contain tenant credentials or resource content. | §7.4 |
| REQ-WF-DECLARED-STEP-PLANE | Every workflow step declares its plane at definition time. | §7.4 |
| REQ-WF-PLATFORM-COORDINATED-HOPS | Every cross-plane or cross-tenant hop is coordinated by the platform; no direct tenant-to-tenant connection exists. | §7.4 |
| REQ-WF-HOPS-AUDITED | Every hop produces sender egress, receiver ingress and platform coordination records — audit is structural, not per-integration. | §7.4 |
| REQ-WF-GRANTS-FROM-CATALOGUE | A hop grant can only be issued for a hop the declared process shape contains. | §8 |

## SCAL — scaling & routing

| REQ | Promise | Source |
|---|---|---|
| REQ-SCAL-DURABLE-ASSIGNMENT | The tenant→pod assignment is durable state with version-driven takeover. | §5, D5 |
| REQ-SCAL-SINGLE-WRITER-TENANT | A tenant's serving pod is its single writer, making local caching and local subscription state correct by construction. | §5, §9 |
| REQ-SCAL-TRANSPARENT-ROUTING | Callers look up a tenant's service in the registry; local instance or remote proxy is indistinguishable. | §5 |
| REQ-SCAL-TWO-HOP-LOCALITY | Requests enter at the closest public node (Kubernetes locality), then route to the serving pod (tenant assignment). | §5 |
| REQ-SCAL-NO-SHARED-STATE-BROKER | The architecture requires no Redis-class shared-state service. | §9 |

## TERM — terminology

| REQ | Promise | Source |
|---|---|---|
| REQ-TERM-NATIVE-FORM | Terminology lives in a normalized, query-optimized form; the FHIR resource form is a wire projection assembled on demand. | §6 |
| REQ-TERM-BULK-LOAD | Loading a large CodeSystem is a native bulk operation — no chunking workarounds, no parameter-cap ceilings. | §6, §7.6 |
| REQ-TERM-OPERATIONS-FROM-NATIVE-FORM | `$expand`, `$lookup` and `validate-code` are served from the normalized form at tenant-local speed. | §6, §7.5 |

## SYNC — canonical content dependencies

| REQ | Promise | Source |
|---|---|---|
| REQ-SYNC-DECLARED-ONLY | Cross-tenant content synchronization happens only for declared dependencies; nothing syncs undeclared. | §6 |
| REQ-SYNC-CODESYSTEM-GRAIN | The dependency unit is the CodeSystem together with its related ValueSets. | §6 |
| REQ-SYNC-PROVENANCE-COPIES | Streamed copies are read-only and provenance-tagged with source tenant and version; updates and retirements propagate through the same stream. | §6 |
| REQ-SYNC-LOCAL-SHADOWING | A tenant's own object with the same canonical identity overrides the streamed copy; removing the override falls back to the live upstream version. | §6 |
| REQ-SYNC-DIRECT-UPSTREAM-ONLY | A tenant declares dependencies only against its direct upstream; chains compose hop by hop. | §6 |

## PROC — process catalogue & map

| REQ | Promise | Source |
|---|---|---|
| REQ-PROC-CATALOGUE-IN-STORE | Process and step definitions (with profiles, planes and projections) are part of DBO's own vocabulary; projections are generated, never hand-edited. | §8 |
| REQ-PROC-DOMAIN-CODE-FILTER | Every process and step carries a free-string process-domain code; views and projections filter by it. | §8 |
| REQ-PROC-NETWORK-MAP | The network answers which processes are known and running, where and in which version — scanned from bundles and accumulated across nodes. | §8 |
| REQ-PROC-TRACE-JOIN | From any process instance, the steps and the exact resource diffs and audit records they produced are navigable. | §8 |

## OPS — operations

| REQ | Promise | Source |
|---|---|---|
| REQ-OPS-TENANT-BLOB-STORAGE | Binary content lives in per-tenant blob storage provisioned credential-blind; erasure-by-drop extends to it; small deployments fall back to Postgres behind the same interface. | §9 |
| REQ-OPS-MIGRATION-AS-DEPLOYMENT | Schema and engine upgrades ride rolling deployment: the highest-version node leads, migrates, and older nodes passivate. | D5 |
