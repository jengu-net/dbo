# Design rationale (§9)

Why the engine is shaped the way it is. Two bodies of experience stand behind
these choices: a mature open-source FHIR server that this design was measured
against, and an earlier storage engine by the same authors whose post-mortem
supplied the derived requirements. Neither is named as a target to beat —
what matters here is which of their properties are structural and which were
circumstantial.

## 9.1 Shared state is designed away, not substituted

The reference implementation this design was measured against needs a Redis
alongside Postgres, for four jobs: background job queues (subscription
delivery, cron, workers), a resource read cache, WebSocket subscription
tracking and cross-instance pub/sub, and rate-limit counters.

The structural reason is its scaling model. *Any stateless instance serves
any tenant*, so every piece of shared state must live outside the instance.
Redis is not a preference there; it is a consequence.

DBO removes the need instead of substituting the tool. Durable tenant→pod
assignment (§5) makes a tenant's serving pod the **single writer** for that
tenant, and every one of those jobs dissolves into a local one:

| Job | Where it goes |
|---|---|
| Read cache | In-JVM, and trivially correct — there is no other writer to invalidate against |
| Queues and scheduled work | DBOS, in the planes of §7.4 |
| Change fan-out | The transactional outbox plus `pg_notify`/DBOS streams |
| WebSocket subscription state | Local to the serving pod that owns the tenant |
| Rate-limit counters | Local too — entry nodes route, serving pods count |

The lesson generalises past Redis: a component that exists to share state
between interchangeable instances is a cost of interchangeability. Give an
instance ownership and the component has no work left to do.

## 9.2 What a mature implementation teaches, and DBO adopts

- **Lookup tables for the search shapes JSONB indexes cannot serve.** Pairing
  per-type JSONB content with dedicated tables for tokens, human names and
  addresses is not a workaround — fuzzy name matching, `:contains` and hot
  sort keys never index well through a generic GIN. DBO's envelope
  generalises this to personality-declared side tables for the parameters
  that earn one.
- **Compartments computed on write.** A patient-compartment column stamped at
  write time turns `$everything` and compartment-scoped access checks into an
  indexed predicate instead of a reference walk.
- **Delivery discipline over delivery mechanism.** Exponential backoff,
  dead-lettering and per-channel workers are the transferable part; whether
  the queue underneath is a job library or DBOS is not.
- **Per-tenant quotas and rate limits as first-class configuration.** On
  shared pods this is the multi-tenant fairness mechanism, and defaults that
  cannot be raised per tenant are a defect. Declared per tenant, enforced at
  the serving pod.
- **Reindex as a versioned operation.** Search-parameter definitions carry
  versions; changing one queues a background reindex rather than a migration.
- **Cold start is a design constraint, not a benchmark.** A first boot that
  replays a full migration changelog costs tens of seconds, which is
  survivable for a server and fatal for an embedded store that boots per test
  run. Schema setup detects an already-current database and does nothing.

## 9.3 What DBO deliberately does not carry

- **Any-instance-serves-anything statelessness**, replaced by assignment-based
  locality — see §9.1.
- **Validation silently disarmed by versioned canonicals.** A profile
  reference that resolves to nothing must fail loudly.
- **Offset paging with duplicate windows.** Keyset cursors, one primitive,
  §10.
- **Session-invalidation semantics that reject a structurally valid token.**
  Token validation is local and answers from the token itself (§13).
- **Configuration and credentials stored inside the FHIR store as proprietary
  resource fields.** Identity artifacts are records with declared handling
  (§13.2); configuration arrives from outside and flows one direction.

## 9.4 Binary content

Binary content — documents, audio, device backups — does not belong in
Postgres rows, and a store that pretends otherwise pays for it in every
backup. Direction: per-tenant object storage (a bucket or a prefix)
provisioned by the same credential-blind operator flow as the database (R5);
`Binary` metadata and hash in the store, content in the tenant's bucket;
presigned or proxied access according to deployment posture; erasure-by-drop
extends to the bucket. Small deployments may fall back to Postgres large
objects behind the same interface.

This is specified and not built (the MNT blob element).

## 9.5 What the earlier engine got right

An earlier storage engine ("db-objects") is not part of this repository, but
its durable concepts are carried into this design rather than rediscovered:

- The **payload/envelope split** — an opaque payload as the source of truth
  plus a derived searchable projection recomputed on every write, with
  reindex as an ordinary operation (§2–§3).
- **An internal id alongside N external `{system, value}` identifiers**,
  rebuilt from the payload on every write, with OR-matching lookup.
- The **transactional outbox** as the natural change feed (§6, §10).
- **Type and version on every stored object**, with upgrade-on-read through
  converters instead of data-rewriting DDL migrations.
- **Two version axes, never conflated** — `payload_version` for the storage
  format, the shape stamp for the tenant-pack profile version an object was
  validated under ([shape versioning](shape-versioning.md)).
- **Requirements as promises in code** — declared once as enum constants
  whose names are their codes, cited from tests and implementation alike,
  with gaps first-class and coverage computed, never asserted
  ([promise](promise.md)).
- **Domain as a physical grouping knob** — which object types share a table
  set, tunable for performance.
- **Version-driven leader election**: the node with the highest application
  version leads, so migration is a consequence of rolling deployment rather
  than a separate operational event (D5).
- **`pg_notify` as the cluster bus** — coordination with no extra broker.
- **A framework-free core** — the engine's own small interfaces, no
  serialization library as a hard dependency.

## 9.6 What the earlier engine got wrong

These are the derived requirements D1–D5 with their reasons attached, and
they are the most useful thing the post-mortem produced.

- **An asynchronous write path without read-your-writes.** Writes landed in
  an event table and a leader projected them into data tables later, so tests
  had to synchronise explicitly. FHIR clients expect a create that returns a
  `Location` and an ETag for a row that is already there. Data and outbox
  commit in one transaction (D1).
- **The application version baked into table names**, forking the whole table
  set on every version bump. Row-level versioning instead.
- **A Java fully-qualified class name as the stored object type**, coupling
  stored data to package refactors.
- **A reactive driver used synchronously** — the complexity of asynchronous
  I/O with the benefit of neither. Virtual threads and plain JDBC.
- **SQL built by string concatenation** with values inlined into JSONPath
  predicates, and text-only ordering that sorted numbers and dates wrongly
  (D2, D3).
- **No indexes at all** — the GIN extension created and never used, so every
  JSONB predicate was a sequential scan. Envelope indexing is a day-one item.
- **A tenant column present in unique constraints and absent from read
  predicates.** This is the anti-pattern R3 exists to forbid: tenancy that is
  structural cannot be forgotten, and tenancy that is a column always will
  be.
- **Configuration smuggled through JVM system properties** into migration
  hooks — global mutable state that breaks parallel and multi-tenant
  initialisation.
- **A single leader processing every domain's events** — a throughput ceiling
  and a single point of failure. Ownership shards per tenant and per domain
  (D4).
