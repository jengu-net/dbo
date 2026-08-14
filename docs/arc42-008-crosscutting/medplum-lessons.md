# Lessons from Medplum implementation (§9)

Medplum is also eight years of FHIR-server implementation experience; exiting
it should not mean relearning its lessons. What transfers, and what its Redis
dependency teaches.

### Why Medplum needs Redis — and why DBO doesn't

Medplum's Redis carries four roles: **BullMQ job queues** (subscription
delivery, cron, background workers), a **resource read cache**, **WebSocket
subscription tracking / cross-instance pub-sub** (per-project subscription
registries keyed in Redis), and **rate-limit counters**. The structural reason
is Medplum's scaling model: *any stateless instance serves any project*, so
every piece of shared state must live outside the instance.

DBO removes the need rather than substituting the tool. The tenant→pod
assignment (§5) makes a tenant's serving pod the **single writer** for that
tenant, so: read caching is in-JVM and trivially correct (no cross-instance
invalidation); queues and scheduled work are DBOS (§7.4 planes); change
fan-out is outbox + `pg_notify`/DBOS streams; websocket subscription state is
local to the serving pod; rate-limit counters are local too (entry nodes
route, serving pods count). Redis dissolves because the architecture
eliminates shared-anonymous-instance state, not because Postgres imitates it.

### Carry forward

- **Lookup-table pattern for hard search parameters.** Medplum pairs per-type
  JSONB content with dedicated lookup tables (tokens, human names, addresses)
  because some search shapes — fuzzy name matching, `:contains`, hot sort
  keys — never index well through a generic JSONB GIN. DBO's envelope should
  do the same: the legacy `_identifier` side-table generalizes to
  **personality-declared side tables** for parameters that earn them.
- **Compartments computed on write.** A patient-compartment column stamped at
  write time makes `$everything` (tier 2) and compartment-scoped access checks
  a plain indexed predicate instead of a reference walk.
- **Async subscription delivery discipline**: retries with exponential
  backoff, dead-lettering, per-channel workers — BullMQ's semantics map to
  DBOS queues; the *policies* are the transferable part.
- **Per-tenant quotas and rate limits as first-class config** (jengu had to
  raise Medplum's defaults) — on shared pods this is the multi-tenant
  fairness mechanism; enforced at the serving pod, declared per tenant.
- **Reindex as a versioned operation**: search-parameter definitions carry
  versions; changing them queues a background reindex — reinforces the
  envelope rebuild already in §2.
- **Cold-start matters**: Medplum's 30–90s first-boot migration forced a
  pg_dump baseline hack in dev. DBO's embedded in-JVM mode lives or dies on
  boot time — schema setup must be fast-path (already-current detection, no
  full changelog replay) from day one.

### Binary / blob storage — a gap this comparison exposes

Medplum stores `Binary` content in external object storage with presigned
URLs, not in Postgres — and jengu uses `Binary` today (documents, audio,
edge system backups). The spec had no blob story before this. Direction: per-tenant
object storage (bucket or prefix) provisioned by the same credential-blind
operator flow as the database (R5); `Binary` metadata + hash in the store,
content in the tenant's bucket; presigned or proxied access per deployment
posture; erasure-by-drop extends to the bucket. Small deployments (edge) may
fall back to Postgres large objects behind the same interface.

### Deliberately not carried

Any-instance-serves-anything statelessness (replaced by assignment-based
locality); validation silently disarmed by versioned canonicals (ADR 0042);
offset paging with duplicate windows; Login-invalidation-under-valid-token
semantics; configuration and credentials stored inside the FHIR store.

