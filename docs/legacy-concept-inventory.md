# Legacy concept inventory (db-objects, 2024–2025)

The `legacy` branch preserves the previous db-objects codebase verbatim. This page
records what a full survey of that code and its Antora docs found: which concepts are
durable and carried into the new specification, and which are deliberately dropped.

A caution for future readers: the legacy `CLAUDE.md` was aspirational, not
descriptive. It claimed Spring Boot modules, R2DBC, multi-tenancy, archiving,
full-text search and GDPR erasure — none of which exist in the code. The trustworthy
design intent is in `documentation/modules/ROOT/pages/*.adoc` on the `legacy` branch.

## Durable concepts — carry forward

| Concept | Legacy location |
|---|---|
| **Payload/envelope split** — opaque JSON payload as the source of truth, plus a derived searchable projection (`context JSONB`) recomputed from the payload on every write via a per-storage extraction hook | `PayloadInfo`, `StorageObjectContext`, `Storage.buildContextFor` |
| **Internal id + N external `{system, value}` identifiers**, rebuilt from payload on every write, OR-match lookup across identifiers | `StorageObjectIdentifier`, `_IDENTIFIER` table |
| **Transactional outbox** (`_EVENTS` + `_PROCESSED` watermark) as the natural change feed | `domains-update-template.sql`, `processEvents` |
| **Owner-controlled reference table** — all of an owner's references deleted and re-inserted on each write; opt-in hydration of referenced objects on read | `_REFERENCE`, `resetReferences`, `withReferences()` |
| **Domain** as a physical grouping knob: which object types share a table set, tunable for performance | `DomainConfiguration` |
| **`type` + `version` on every stored object**, upgrade-on-read via converters instead of data-rewriting DDL migrations | design in `index.adoc` |
| **Node status state machine** with explicit legal transitions and self-verification | `parallel/NodeState` |
| **Version-driven leader election** — the node with the highest application version becomes master, so schema/data migration is a *consequence of rolling deployment* (new pod arrives → promoted → migrates → old pods passivate) | `working-in-cluster.adoc` |
| **Database self-describes its writer** via `dbo_version()`/`dbo_type()` immutable SQL functions; nodes passivate on mismatch | `DatabaseDboVersionUpdate` |
| **Single-statement atomic master election** (`WITH upd AS (UPDATE … RETURNING) SELECT … UNION … LIMIT 1`) — take-mastership-or-read-current in one round trip | `SystemDbScripts.syncApplicationState` |
| **`pg_notify` as the cluster bus** — node coordination with no extra broker | `NodeSyncQueue` |
| **Broker-agnostic eventing SPI** with compaction keys, tombstones, dead-letter topics | `eventing/Eventing.java` |
| **FHIR contained-resource container** — flattening nested containment, local `#id` reference resolution | `capability/ResourceContainer` |
| **Streaming Bundle parser** deserializing only registered resource types (bulk ingest shape) | `util/BundleUtil` |
| **Advisory session locks** for cluster-safe migrations (no lock table to wedge after a crash) | `liquibase-sessionlock` |
| **Framework-free core instinct** — own 3-method `ObjectMapper` interface, Jackson not a hard dependency | `io/dbobjects/ObjectMapper.java` |

Also worth mining, though never implemented: the reference-subscription design
(`references.adoc` — a storage declares interest as `(objectType, referenceType,
cardinality)` and receives CRUD callbacks), the blocking-admin-task protocol with
heartbeats, and the data-lifecycle thesis (archiving, temporary restore, changelog,
tracing chains of changes).

## Dropped — do not carry forward

- **Async write path without read-your-writes.** Legacy writes went to the event
  table and a master-node loop projected them into data tables later; tests had to
  call `synchronize()` explicitly. FHIR clients expect a synchronous create with a
  `Location`/ETag. New design: write data + outbox in one transaction.
- **App version baked into table names** (`DBO_<APP>_<VER>_<DOMAIN>_*`) — forks the
  whole table set on every version bump. Row-level versioning instead.
- **Java FQCN as the stored `object_type`** — couples data to package refactors.
  Use `resourceType` strings.
- **Kafka** — already excised in the last legacy commit, never actually wired.
  The outbox plus Postgres logical decoding / DBOS covers the change feed.
- **Vert.x reactive client used synchronously** — async driver, blocking `.get()`
  on every query: complexity of both worlds, benefit of neither. Virtual threads +
  plain JDBC.
- **String-concatenated SQL with inlined identifier values** in JSONPath predicates
  (injection surface) and text-only `->>` ordering (wrong numeric/date sorts).
- **Zero indexes** — `btree_gin` extension created, never used; every JSONB
  predicate was a sequential scan. Envelope indexing is a day-one design item.
- **`app_code` masquerading as isolation** — present in unique constraints, never
  in a read predicate. Real tenancy must be structural (see requirements R3/R5).
- **Config smuggled through JVM system properties** into Liquibase custom changes —
  global mutable state, breaks parallel/multi-tenant init.
- **God objects and dead interfaces** — `Node` implementing four roles; half the
  `Database` SPI marked deprecated by its own author; empty Micronaut modules;
  commented-out classpath scanning that left polymorphic deserialization broken.
- **Single master node processing all domains' events** — throughput ceiling and a
  SPOF; ownership must be partitionable (per tenant / per domain).
