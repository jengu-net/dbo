# DBO requirement catalogue

The founding requirements ([founding-requirements.md](../arc42-001-introduction/founding-requirements.md)) and concepts
(the §-numbered sections, see [the docs index](../README.md)) distilled into stable REQ-style IDs, grouped by
capability area. Each REQ is a business-readable promise; the parenthesized
source at its end traces it back. Tier-2/3 search features and
migration-program exit criteria
deliberately have no REQs yet — they get them when scheduled.

## CORE — object engine

| REQ | Promise |
|---|---|
| REQ-DBO-CORE-PAYLOAD-IS-TRUTH | A stored object's payload is the single source of truth; every searchable projection is derived from it and can always be rebuilt. (§2) |
| REQ-DBO-CORE-DECLARED-TRUTH-FORM | Which representation is authoritative for a type (payload or normalized form) is declared by its personality, never implicit. (§6) |
| REQ-DBO-CORE-REINDEX-IS-AN-OPERATION | Changing how objects are indexed is a background operation, never a data migration. (§2) |
| REQ-DBO-CORE-EXTERNAL-IDENTIFIERS | Every object has one internal id and any number of `{system, value}` identifiers, rebuilt from the payload on each write and searchable together. (§2) |
| REQ-DBO-CORE-REFERENCE-EDGES | References between objects are extracted as owned edges on write and power referential reads. (§2) |
| REQ-DBO-CORE-VERSIONED-HISTORY | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. (§2, D1) |
| REQ-DBO-CORE-READ-YOUR-WRITES | A write returns only after its data and its change event are committed in one transaction. (D1) |
| REQ-DBO-CORE-UPGRADE-ON-READ | Old payload versions are upgraded lazily by registered converters; a schema-version transition never requires a big-bang rewrite. (§2) |
| REQ-DBO-CORE-PARAMETERIZED-SQL | No value is ever concatenated into SQL text. (D2) |
| REQ-DBO-CORE-SIBLING-MODELS | Non-FHIR object models ride the same engine as FHIR resources, not beside it. (R6) |
| REQ-DBO-CORE-DECLARED-IDENTITY | Every type in every personality declares exactly one primary identity class — canonical url, designated identifiers, or internal — and the contract fails closed at registration without it. (§12) |
| REQ-DBO-CORE-IDENTITY-SURVIVES-CONVERSION | Conversion between FHIR versions or object shapes never changes identity; canonical urls and identity-bearing identifiers are preserved bit-exact and verified after every conversion. (§12) |
| REQ-DBO-CORE-NO-IMPLICIT-MERGE | Two objects claiming the same identity-bearing identifier are a conflict surfaced to the owner, never an implicit merge. (§12) |
| REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS | Conditional writes are accepted only when keyed on the type's primary identity; a conditional write on any other criterion is rejected. (§12) |

## CONT — container & embedding

| REQ | Promise |
|---|---|
| REQ-DBO-CONT-FRAMEWORK-FREE-CORE | The core is plain Java; no Spring/Micronaut-class framework dependency anywhere in the engine. (R1, R2) |
| REQ-DBO-CONT-DYNAMIC-TENANT-SERVICES | Tenants arrive, move and leave as OSGi service-registry dynamics — never a process restart. (R2, §4) |
| REQ-DBO-CONT-EMBEDDED-IN-JVM | A host application can boot the full store inside its own JVM for dev/test; the only shared dependencies are Felix and the OSGi API. (R2) |
| REQ-DBO-CONT-PRIVATE-DEPENDENCIES | Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and served through DBO-owned whiteboard interfaces; their types never cross bundle boundaries. (§7.1, §7.3) |
| REQ-DBO-CONT-FAST-COLD-START | Store startup against an already-current schema is fast enough for embedded test use; schema setup detects currency instead of replaying changelogs. (§9) |

## TEN — tenancy & isolation

| REQ | Promise |
|---|---|
| REQ-DBO-TEN-STRUCTURAL-SCOPING | No code path can read or write data without an explicit tenant context. (R3) |
| REQ-DBO-TEN-DEDICATED-DATABASE-TIER | A tenant can run on a dedicated database; this tier is the design anchor. (R5) |
| REQ-DBO-TEN-CREDENTIAL-BLIND-PROVISIONING | Tenant databases and buckets are provisioned by an external operator; credentials exist only as platform secrets and are never readable by tenant-manager code. (R5, §4) |
| REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS | Application code obtains a tenant's data services from the service registry and can use them without ever seeing credentials. (R5, §4) |
| REQ-DBO-TEN-ERASURE-BY-DROP | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. (§7.4, §9) |
| REQ-DBO-TEN-SHARED-TIER-ISOLATION | Tenants on the shared tier are isolated by tenant-keyed schemas and row-level security with the same API surface as the dedicated tier. (§3) |
| REQ-DBO-TEN-FAIRNESS-QUOTAS | Per-tenant quotas and rate limits are first-class configuration, enforced at the serving pod. (§9) |

## AUTH — tenant authority & surface protection

| REQ | Promise |
|---|---|
| REQ-DBO-AUTH-TENANT-SCOPED-ISSUER | Every tenant is its own OIDC authority with its own issuer URL, discovery document, key set and token endpoint; relying parties trust exactly one tenant's authority, never the store's. A token from any other tenant fails signature verification before any claim is read. (§13) |
| REQ-DBO-AUTH-IDENTITY-AS-RECORDS | Client applications, grants and signing keys are regular records in the tenant's own store — versioned, provenance-stamped, visible to feeds, and carried by the maintenance export: restoring a tenant restores who may access it. (§13, §11) |
| REQ-DBO-AUTH-PRIVATE-SURFACE | The raw store surface is never publicly routed; public interaction with dbo-held data goes through process-based surfaces. The authority exists so authorized services reach the private surface with tenant-rooted trust. (§13) |
| REQ-DBO-AUTH-DENY-BY-DEFAULT | A serving deployment without a working authority refuses to serve tenant endpoints; disabling auth is an explicit embedded/test flag, never a default. (§13) |
| REQ-DBO-AUTH-BEARER-LOCAL-VALIDATION | The serving surface accepts OAuth2 bearer JWTs validated locally against the tenant's own cached key set — no per-request dependency on any other service. (§13) |
| REQ-DBO-AUTH-CREDENTIAL-FACTORS-BY-KIND | A local credential holds factors named by kind (RFC 8176 `amr`), and what may be held is decided per kind: a password only where the tenant is the identity provider for that subject, a bench PIN alongside federation because it serves the case federation cannot. (§13.6, §16.2) |
| REQ-DBO-AUTH-SELF-SERVICE-CHANGE | A signed-in subject can replace their own password by proving possession of the current one. No ticket, no second channel, and no other factor is touched. (§13.6) |
| REQ-DBO-AUTH-RECOVERY-IS-AN-OPERATOR-ACT | A subject who cannot sign in is recovered by provisioning or an operator write, never by a self-service ceremony: recovery needs a channel the authority does not have, and acquiring one would put delivery inside the trust root. (§13.6) |
| REQ-DBO-AUTH-DEACTIVATION-RETIRES-CREDENTIALS | Deactivating a subject retires its credentials — every factor, at once, and never by deletion: history and audit need the record, and a login that vanishes cannot be told from one that never existed. (§13.6) |
| REQ-DBO-AUTH-NO-SUBJECT-ENUMERATION | No authority answer distinguishes a subject that exists from one that does not — not in what it says, not in how long it takes. The authority is the only party that knows, which is why it must not say. (§13.6) |
| REQ-DBO-AUTH-SMART-SHAPED-SCOPES | Authorization vocabulary is the SMART system-scope grammar, so finer service permissions and the future read-only public capability need no new language. (§13) |
| REQ-DBO-AUTH-PORTABLE-AUTHORITY | The issuer string is per-tenant configuration and the key material lives in the tenant database — a tenant can move deployments or present a custom domain without re-keying. (§13) |
| REQ-DBO-AUTH-ORG-MODEL-IS-THE-AUTH-MODEL | Human authorization derives from the tenant's own records — Practitioner is the subject, an active PractitionerRole is the grant, the Organization tree is the scope structure; there is no parallel user database to drift. (§16) |
| REQ-DBO-AUTH-FEDERATED-HUMANS | Human authentication is federated to the configured identity broker; the authority resolves the verified national identifier to a Practitioner through the vault index and owns authorization only. Local credentials are an embedded/dev fallback, never the production path. (§16, §14) |
| REQ-DBO-AUTH-ROLE-GRANTS-AS-RECORDS | The role-to-scope mapping is tenant-administered regular records — auditable, feed-visible, exported; changing who may do what is a recorded act. (§16) |
| REQ-DBO-AUTH-PSEUDONYMOUS-TOKENS | Human tokens carry the practitioner's record id and SMART user scopes — no name, no national code; a captured token identifies no one. (§16, §14) |
| REQ-DBO-AUTH-ONE-CEREMONY-MANY-TENANTS | One national authentication serves every tenant authority in the deployment through the identity hub's session — the upstream broker is invoked once per session, not per tenant; authorization remains strictly per-tenant. (§16.2) |
| REQ-DBO-AUTH-ON-BEHALF-OF | Automated processes act in the name of a human via token exchange — subject stays the practitioner, an act claim names the client, scopes attenuate; durable workflows delegate through Delegation records that outlive tokens and are revocable by ending their period. Every delegated mutation is attributable to both the process and the person. (§16, §15) |

## PDI — personal-data isolation

| REQ | Promise |
|---|---|
| REQ-DBO-PDI-STRUCTURAL-VAULT | Identifying elements, declared per type/element, live encrypted in the tenant's person vault; the main store holds pseudonymous records and the engine reassembles full resources for authorized reads — isolation is beneath the API, not a caller discipline. (§14) |
| REQ-DBO-PDI-CRYPTO-SHREDDING | Erasure destroys the person's key: history stays byte-immutable, existing archives stay valid as files, and the person's data is cryptographically gone from live store, history, envelopes and archives at once. (§14) |
| REQ-DBO-PDI-UNFINDABLE-AFTER-ERASURE | Search indexes derived from personal elements are vault-scoped or rebuilt on shred — an erased person is unfindable, not merely unreadable. (§14) |
| REQ-DBO-PDI-BLIND-OPERATIONS | Backup and restore are machinery-driven end to end over ciphertext; the operator can run the whole lifecycle without the ability to read personal data, and opening an archive outside the running system is an owner-only act. (§14, §11) |
| REQ-DBO-PDI-SHRED-LEDGER | Erasures are recorded without personal data and re-applied on every restore before serving resumes — an old archive cannot silently resurrect an erased person. (§14) |
| REQ-DBO-PDI-RIGHTS-AS-OPERATIONS | Access, portability and restriction are standard machinery operations over the vault join, not per-request projects. (§14) |

## POL — tenant policies (audit & write discipline)

| REQ | Promise |
|---|---|
| REQ-DBO-POL-DECLARED-AT-CONFIGURATION | Audit level and write discipline are declared in the tenant's configuration next to its FHIR version, validated at registration, and visible in the capability statement. (§15) |
| REQ-DBO-POL-AUDIT-AS-RECORDS | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. (§15, §14) |
| REQ-DBO-POL-ACTOR-FROM-AUTHORITY | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. (§15, §13) |
| REQ-DBO-POL-APPEND-ONLY-DISCIPLINE | Under append-only discipline the engine rejects tombstones (and per-type in-place updates where declared); correction is supersession or entered-in-error, never removal. (§15) |
| REQ-DBO-POL-ERASURE-COMPATIBLE | Append-only discipline and the right to erasure coexist: shredding never rewrites a record — the record remains, the person evaporates. (§15, §14) |
| REQ-DBO-POL-DECLARATIVE-RETENTION | Retention is declared per tenant and type as a floor and a ceiling — keepAtLeast (append-only holds even against policy) and removeAfter (the engine must remove) — composing with write discipline without conflict. (§15) |
| REQ-DBO-POL-RETENTION-SWEEP | A durable scheduled sweep executes removal as the one sanctioned mutation of history, and every removal is audited without retaining the removed data. (§15) |
| REQ-DBO-POL-POLICY-REPLAY-ON-RESTORE | Before a restored tenant serves, the machinery re-applies the shred ledger and the retention sweep — an archive cannot resurrect what policy required gone; archives carry removeAfter themselves. (§15, §14, §11) |
| REQ-DBO-POL-CUSTOM-AUDIT-EVENTS | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. (§15) |
| REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY | Audit entries are exempt from the tenant's write discipline: no update, no tombstone under any policy; retention's sweep is the only removal. (§15) |
| REQ-DBO-POL-FHIR-AUDIT-PROJECTION | On a FHIR tenant the audit stream is served as AuditEvent — native records as the truth form, rendered per personality on read, contribution via mapped POST; write access is scope-gated. (§15, §9) |

## ZONE — jurisdiction overlay

| REQ | Promise |
|---|---|
| REQ-DBO-ZONE-DECLARATIONS-AS-RECORDS | A zone is a tenant whose declarations — identity brokers, identifier domains — are regular records: versioned, audited, exported, and streamable down the same chains as any content. Secrets are never in a record. (§17) |
| REQ-DBO-ZONE-BROKER-CHOICE | The broker set is jurisdictional, the choice organizational: the zone declares the available national brokers; a tenant selects its contracted one and may restrict what it accepts. (§17) |
| REQ-DBO-ZONE-SESSIONS-ACCUMULATE | The per-zone hub's session records which broker performed each ceremony and accumulates ceremonies; cross-broker reuse is the default, tenant acceptance policy the restriction — the strictest tenant is satisfied without invalidating anyone else's session. (§17, §16.2) |
| REQ-DBO-ZONE-SUBJECT-DOMAINS | Subject-resolution identifier systems come from the zone's declared domains — the official national terminology — never from dbo code. (§17, §16.1) |

## VER — version plurality (personalities)

| REQ | Promise |
|---|---|
| REQ-DBO-VER-VERSION-AGNOSTIC-CORE | The engine has no knowledge of any FHIR version; all version meaning lives in personality bundles. (R6, §1) |
| REQ-DBO-VER-CONCURRENT-VERSIONS | Tenants (and domains within a tenant) on different FHIR versions run concurrently in one container. (R6) |
| REQ-DBO-VER-PERSONALITY-OWNS-MEANING | Parsing, validation, search-parameter extraction and subscription evaluation are personality responsibilities, per version. (§1) |
| REQ-DBO-VER-SPECIFIED-VALIDATION | Profile-resolution and validation semantics are specified by DBO — a malformed or versioned canonical reference can never silently disable validation. (§7.6, §9.3) |
| REQ-DBO-VER-VALIDATION-WITHOUT-WRITING | A caller can ask whether a resource would be accepted without writing it (`[Type]/$validate`), and the answer is the write path's own: what it accepts a write accepts, what it rejects a write rejects. Issues carry the locations a refusal carries, so a caller is told what to fix. The verdict is the resource's shape — state a write settles (an identity already claimed, a version moved on) is not promised. (§1, §7.6) |
| REQ-DBO-VER-ONE-READ-PER-REQUEST | Accepting a write reads its payload once, however many parts of the write ask about it — the type, the verdict and the searchable envelope come from one read. A payload rewritten on its way into the engine is read as it now stands, so what is indexed is what is stored. (§1) |
| REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION | A stored version records the exact version it was authored under — a ballot by its full spelling, never the release it anticipates — so a later version has something to convert from and a reader is never told a guess. (§1, R6) |
| REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE | A face brings the definitions it validates and extracts against. Bringing a tenant up fetches nothing over the network and needs no writable cache outside the store's own state. (§1) |
| REQ-DBO-VER-BALLOT-SERVED-AS-AUTHORED | A version still at ballot promises no normalized truth form and no conversion to or from another version: what an author wrote is what a reader receives. Normalising under a ballot's understanding would bake it into bytes that are never rewritten, and the next ballot moving an element would lose what it moved. (§1, §6) |
| REQ-DBO-VER-TRANSITION-BY-CONVERTERS | Moving a tenant between FHIR versions is converters plus reindex, not a data migration ceremony. (§2, R6) |

## SRCH — search

| REQ | Promise |
|---|---|
| REQ-DBO-SRCH-TIER1-PARITY | Every search feature a production healthcare platform actually issues works identically ([inventory](../evidence/search-usage-inventory.md)). (§7.5) |
| REQ-DBO-SRCH-STRICT-BY-DEFAULT | An unsupported search parameter is rejected, never silently ignored. (§7.5) |
| REQ-DBO-SRCH-HONEST-CAPABILITY | The CapabilityStatement is generated from what the server actually serves — the configured types, the interactions their declared handling permits, the conditional writes their identity class allows, the history their durability keeps, the search parameters accepted, and the operations registered by the facades that were wired. An operation is declared because it is routable: the router and the statement read one list, so neither a served-but-undeclared operation nor a declared-but-unanswered one is expressible. (§1, §7.6) |
| REQ-DBO-SRCH-TYPED-ORDERING | Sorting and range filtering are typed — numeric, date and token semantics are correct, with matching indexes. (D3) |
| REQ-DBO-SRCH-DECLARED-INDEXES | Indexing (including side tables for hard parameters) is declared by the personality as part of its search contract, from day one. (§3, §9) |
| REQ-DBO-SRCH-CUSTOM-PARAMETERS | A tenant or module can register a custom search parameter; extraction, reindex and the new index follow automatically. (§7.5) |

## FEED — feeds, pagination, synchronization

| REQ | Promise |
|---|---|
| REQ-DBO-FEED-ONE-PRIMITIVE | Pagination, subscription delivery, content streams and edge sync are all the same primitive: an ordered, replayable sequence with an opaque durable cursor. (§10) |
| REQ-DBO-FEED-KEYSET-CURSORS | Cursors are keyset positions, never offsets; a page is stable under concurrent writes. (§10, D3) |
| REQ-DBO-FEED-PUSH-ACK-RESUME | Push consumers acknowledge with the cursor; any interrupted stream resumes from the last acknowledged position. (§10) |
| REQ-DBO-FEED-IDEMPOTENT-DELIVERY | Delivery is at-least-once with idempotent apply by identity and version. (§10) |
| REQ-DBO-FEED-NAMED-CONSUMERS | Every durable consumer holds a named cursor in the store; progress, lag and replay are uniformly observable. (§10) |
| REQ-DBO-FEED-LEAN-WIRE-OPTION | Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are assembled only at the FHIR surface. (§10) |

## EVT — eventing & subscriptions

| REQ | Promise |
|---|---|
| REQ-DBO-EVT-TRANSACTIONAL-OUTBOX | Every change event originates as an outbox row committed with the write. (R8, §6) |
| REQ-DBO-EVT-FHIR-SUBSCRIPTIONS | Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 personality) are a core capability. (R8) |
| REQ-DBO-EVT-DURABLE-DELIVERY | Subscription delivery is durable, tenant-scoped and replayable, with retries, backoff and dead-lettering. (R8, §9) |
| REQ-DBO-EVT-IN-PROCESS-SURFACE | Co-located consumers get the same topics with identical semantics through the in-process/OSGi surface. (R8) |

## WF — durable work & planes

| REQ | Promise |
|---|---|
| REQ-DBO-WF-POSTGRES-SUBSTRATE | Durable tasks, streams and inter-instance communication run on the DBOS/Postgres substrate; no external broker. (R4) |
| REQ-DBO-WF-TWO-PLANES | Workflow state lives where its content belongs: platform plane for coordination, tenant plane for anything carrying resource content. (§7.4) |
| REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE | Platform-plane workflow parameters and checkpoints never contain tenant credentials or resource content. (§7.4) |
| REQ-DBO-WF-DECLARED-STEP-PLANE | Every workflow step declares its plane at definition time. (§7.4) |
| REQ-DBO-WF-PLATFORM-COORDINATED-HOPS | Every cross-plane or cross-tenant hop is coordinated by the platform; no direct tenant-to-tenant connection exists. (§7.4) |
| REQ-DBO-WF-HOPS-AUDITED | Every hop produces sender egress, receiver ingress and platform coordination records — audit is structural, not per-integration. (§7.4) |
| REQ-DBO-WF-GRANTS-FROM-CATALOGUE | A hop grant can only be issued for a hop the declared process shape contains. (§8) |

## SCAL — scaling & routing

| REQ | Promise |
|---|---|
| REQ-DBO-SCAL-DURABLE-ASSIGNMENT | The tenant→pod assignment is durable state with version-driven takeover. (§5, D5) |
| REQ-DBO-SCAL-SINGLE-WRITER-TENANT | A tenant's serving pod is its single writer, making local caching and local subscription state correct by construction. (§5, §9) |
| REQ-DBO-SCAL-TRANSPARENT-ROUTING | Callers look up a tenant's service in the registry; local instance or remote proxy is indistinguishable. (§5) |
| REQ-DBO-SCAL-TWO-HOP-LOCALITY | Requests enter at the closest public node (Kubernetes locality), then route to the serving pod (tenant assignment). (§5) |
| REQ-DBO-SCAL-NO-SHARED-STATE-BROKER | The architecture requires no Redis-class shared-state service. (§9) |

## TERM — terminology

| REQ | Promise |
|---|---|
| REQ-DBO-TERM-NATIVE-FORM | Terminology lives in a normalized, query-optimized form; the FHIR resource form is a wire projection assembled on demand. (§6) |
| REQ-DBO-TERM-BULK-LOAD | Loading a large CodeSystem is a native bulk operation — no chunking workarounds, no parameter-cap ceilings. (§6, §7.6) |
| REQ-DBO-TERM-EVERY-TENANT-ANSWERS | Every served tenant answers `$lookup`, `$expand` and `$validate-code` from its own store's native form, whichever FHIR version it speaks; no tenant is a second-class reader. A terminology write reaches that form rather than being stored whole — a resource that is present and answers nothing is worse than one that is absent. (§6, §7.5) |
| REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM | `$expand`, `$lookup` and `validate-code` are served from the normalized form at tenant-local speed. (§6, §7.5) |

## SYNC — canonical content dependencies

| REQ | Promise |
|---|---|
| REQ-DBO-SYNC-DECLARED-ONLY | Cross-tenant content synchronization happens only for declared dependencies; nothing syncs undeclared. (§6) |
| REQ-DBO-SYNC-ANY-TYPE | Any resource type can be declared as a cross-tenant dependency; each type defines its grain — for terminology, the CodeSystem together with its related ValueSets. (§6) |
| REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES | A streamed terminology dependency rebuilds the receiving tenant's native form: the source sends the whole CodeSystem even though it stores a shell, and the dependent takes it apart into its own concepts. After catch-up the dependent answers `$lookup` and `$expand` locally, which is the only proof that the grain survived the hop — a copy's stored payload never contains a concept at either end. (§6) |
| REQ-DBO-SYNC-CONVERT-ON-APPLY | Streamed objects are converted at apply into the receiving tenant's FHIR version and object shape by the registered converter chains; an unconvertible object dead-letters visibly and degrades the dependency, never silently skips. (§6, §2) |
| REQ-DBO-SYNC-PROVENANCE-COPIES | Streamed copies are read-only and provenance-tagged with source tenant and version; updates and retirements propagate through the same stream. (§6) |
| REQ-DBO-SYNC-LOCAL-SHADOWING | A tenant's own object with the same base identity overrides the streamed copy — version-neutrally, across FHIR versions and business versions; removing the override falls back to the live upstream version. (§6, §12) |
| REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY | A tenant declares dependencies only against its direct upstream; chains compose hop by hop. (§6) |
| REQ-DBO-SYNC-SPEC-DECLARED | A tenant's content dependencies are part of its tenant spec (configuration); the runtime wires declared streams at bring-up and removes them when undeclared. (§6) |
| REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP | A newly declared dependency catches up from the upstream's full history; pre-existing content arrives the same way live changes do. (§6) |

## PROC — process catalogue & map

| REQ | Promise |
|---|---|
| REQ-DBO-PROC-CATALOGUE-IN-STORE | Process and step definitions (with profiles, planes and projections) are part of DBO's own vocabulary; projections are generated, never hand-edited. (§8) |
| REQ-DBO-PROC-DOMAIN-CODE-FILTER | Every process and step carries a free-string process-domain code; views and projections filter by it. (§8) |
| REQ-DBO-PROC-RUN-HAS-A-RECORD | Every run of a step is a record in a tenant's own store — a registered type, so it is envelope-queryable, versioned, carried by the backup and dropped with the tenant. A run in a private table has none of those, and cannot be seen or acted on. (§8) |
| REQ-DBO-PROC-RUN-SAYS-WHO-HOLDS-IT | A run's load-bearing field is who holds it now: automation running, automation with a retry scheduled, a person, or nobody. Every other field answers a question somebody asks after that one. (§8) |
| REQ-DBO-PROC-RUN-TALLY-AND-ITEM-OUTCOMES | A run over N items where K fail records one run with a tally and K item outcomes, and does not abandon the remaining N−K. (§8) |
| REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS | A record that is wrong reaches a person; a store that is unavailable is a retry and nobody's card. Only record-class failures make work, or the queue becomes a graveyard and stops being read. (§8) |
| REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION | Where a condition is machine-checkable, fixing the cause closes the run on the next pass; closing by hand exists only for conditions nothing can re-check. Closing by click is how a card reads resolved while the fault is live. (§8) |
| REQ-DBO-PROC-RUN-KINDS | A pipeline closes when every item is terminal; a sweep closes when the world agrees. A reconciler modelled as a pipeline never ends, and its needs-a-person queue fills with work that is merely still converging. (§8) |
| REQ-DBO-PROC-ONE-PARENT-NEVER-ACROSS-A-BOUNDARY | A run has at most one parent, and parenthood never crosses a domain or a system: items are children, subprocesses and continuations are references. A parent's close must mean something for its children, and cannot across a boundary this runtime does not control. (§8) |
| REQ-DBO-PROC-CORRELATION-TRAVELS-OPAQUE | A correlation carried from another system is echoed and never interpreted, so a cross-system join is queryable from either side without that system's vocabulary entering the engine. (§8, ADR 0060) |
| REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT | A run's envelope carries holder, step, state and counts — never item references or messages. The envelope is a disclosure surface, and progress must not name what was being processed. (§8, ADR 0058) |
| REQ-DBO-PROC-NETWORK-MAP | The network answers which processes are known and running, where and in which version — scanned from bundles and accumulated across nodes. (§8) |
| REQ-DBO-PROC-TRACE-JOIN | From any process instance, the steps and the exact resource diffs and audit records they produced are navigable. (§8) |

## OPS — operations

| REQ | Promise |
|---|---|
| REQ-DBO-OPS-TENANT-BLOB-STORAGE | Binary content lives in per-tenant blob storage provisioned credential-blind; erasure-by-drop extends to it; small deployments fall back to Postgres behind the same interface. (§9) |
| REQ-DBO-OPS-RUNTIME-SAYS-WHAT-IT-SERVES | A runtime can be asked which tenants it is serving, and what it is doing about the ones it is not: serving, coming up, failed to come up — one state per tenant it has been told about. The answer comes from runtime state, never from re-reading the declarations, so a caller comparing the two can find a disagreement rather than confirming its own writes. Cross-tenant, so no tenant credential buys it. (§9) |
| REQ-DBO-OPS-MIGRATION-AS-DEPLOYMENT | Schema and engine upgrades ride rolling deployment: the highest-version node leads, migrates, and older nodes passivate. (D5) |

## MNT — maintenance

| REQ | Promise |
|---|---|
| REQ-DBO-MNT-BACKUP-IS-EXPORT | Backup and export are one mechanism, restore and import another single one; every backup is restorable by the everyday import path. (§11) |
| REQ-DBO-MNT-PORTABLE-STATE-EXPORT | The latest-state export is idempotent, store-independent FHIR (with blob content, hash-verified) — importable into a fresh tenant, the same tenant, or any other FHIR store. It travels as Bulk Data: NDJSON per type whose resources carry their own id and version, beside the manifest that spec defines — same digests the archive was attested over, so a stranger checking the export and a party checking the signatures cannot get different answers. (§11) |
| REQ-DBO-MNT-HISTORY-BY-SCHEMA | Version history, audit and consumer state live in their own database schemas, so the high-fidelity history element is a schema-scoped dump, restorable byte-exact. (§11) |
| REQ-DBO-MNT-OWNER-KEY-ENCRYPTION | An export bundle is encrypted so that only the tenant owner's master key can open it; the platform operates backups it cannot read, and restore requires the owner. (§11) |
| REQ-DBO-MNT-SNAPSHOT-CONSISTENT | The state element is cut at a single consistent snapshot; incremental export is the feed from that snapshot's cursor. (§11, §10) |
| REQ-DBO-MNT-ARCHIVE-ROOT-OVER-CONTENTS | An archive's attested root is computed over the manifest's per-entry digests rather than over the archive's bytes, so re-packing, re-compressing or reordering does not invalidate what was attested. (§11) |
| REQ-DBO-MNT-BOTH-PARTIES-ATTEST | An archive carries two detached signatures over that root — the vendor's and the tenant's — and the tenant countersigns without resealing, so neither party can produce an attested archive alone. (§11) |
| REQ-DBO-MNT-IMPORT-REFUSES-UNATTESTED | Objects enter a store from an archive by one path only: the root recomputes and both signatures verify, or nothing is written. A refusal names what was wrong with the archive rather than failing part-way through it. (§11) |
| REQ-DBO-MNT-ATTESTATION-READS-AS-FHIR | An archive's attestation renders as a `Provenance` carrying FHIR's `Signature`, so a customer's own tooling can check what it was handed without learning this store's JSON. A view rendered by the face, never the truth form — an archive of a non-FHIR domain is attested the same way and has no Provenance. (§11) |
| REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED | A destination records the root it accepted and the two keys that signed it, in the tenant's own audit trail, so what was imported and what both parties said it was stays answerable without the archive. (§11, §15.1) |
