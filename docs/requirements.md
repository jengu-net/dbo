# DBO — main requirements

The driving problem: jengu's current FHIR store (Medplum) is pinned to FHIR R4 with
no R5/R6 roadmap, while Estonia's national base FHIR is already R5 and jengu's device
strength is significantly upgraded in R5/R6. DBO is the specification of an "ideal"
FHIR storage for jengu's actual needs.

These are the founding requirements. Each will later decompose into precise,
testable REQs; at this stage they fix direction.

## R1 — Java

The engine is written in Java (current LTS; Java 21+ language level, virtual
threads assumed).

## R2 — No heavyweight application framework; OSGi as the container

The core must not depend on Spring Boot, Micronaut, or similar heavyweight
frameworks. The runtime container is **OSGi** (Apache Felix as the reference
implementation), because:

- Tenant management is inherently *dynamic*: per-tenant database pools, per-tenant
  SSO/IdP wiring, per-tenant storage services must come and go at runtime without
  restarting the process. The OSGi service registry is exactly this lifecycle.
- **Embeddability**: in development and test, the FHIR container boots *inside the
  same JVM* as the application (jengu) with no significant dependency conflicts —
  the only surface visible to the host is Felix + the OSGi API. This mirrors the
  proven jengu edge/driver pattern (in-JVM Felix containers in tests).
- The core stays framework-free as plain Java APIs; OSGi bundles are the packaging
  and wiring layer, thin adapters may exist for host frameworks.

## R3 — Multi-tenant by design

Every API, table, index, event and cache is tenant-scoped from the first line.
Tenancy is structural, not a column that queries may forget: there must be no code
path that can read or write data without an explicit tenant context. (The legacy
codebase's `app_code` — present in constraints, absent from predicates — is the
anti-pattern.)

## R4 — PostgreSQL storage, DBOS as the background engine

PostgreSQL is the only supported data store. The implementation should use the same
class of performance techniques DBOS demonstrates (Postgres-native queues and
notification, single-round-trip atomic state transitions, batch projections).

**DBOS is the background engine**: durable tasks, streams, scheduled work, and
communication between instances run on DBOS rather than an external broker. This
replaces the legacy "zone dependency" / Kafka direction — the database itself is the
coordination substrate.

## R5 — Total tenant isolation, credential-blind provisioning

The multi-tenant setup must support *total* isolation: a tenant can run on a
dedicated database whose credentials are never known to the tenant-manager code.

This requires a standard tenant registration procedure in which:

1. Registration is requested through the tenant manager.
2. An **external provisioning service (Kubernetes operator)** creates the database
   and generates credentials, storing them only as Kubernetes secrets — no program
   code ever reads or logs them in the management plane.
3. The secret is delivered directly to the serving pod (mounted secret), where a
   provisioning bundle constructs the tenant's connection pool and registers it as
   an OSGi service.
4. Application code obtains the tenant's `DataSource`/storage services from the
   service registry by tenant id — it can *use* the pool but never see credentials.

Isolation tiers (dedicated database vs. shared-with-RLS) may exist for cost
reasons, but the dedicated-database tier is the design anchor.

## R6 — FHIR-version-plural, and open to sibling models

The store must support **FHIR R4, R5, R6 — and future versions** concurrently
(different tenants, and even different domains within a tenant, on different
versions). The engine core is therefore *version-agnostic*: it stores FHIR-like
objects (id, identifiers, references, opaque payload) and delegates
version-specific knowledge (parsing, validation, search-parameter extraction) to
pluggable per-version bundles.

The same property keeps the engine open to **sibling models that FHIR does not
cover** — the legacy db-objects insight worth keeping: the storage models
"FHIR-like objects", fully expandable, so non-FHIR domain objects ride on the same
engine rather than beside it.

## R7 — Top-notch performance and smart horizontal scaling

Performance is a first-class requirement, not a later optimization: proper envelope
indexing from day one, single-round-trip write paths, DBOS-style Postgres tricks.

Horizontal scaling is *tenant-aware and application-level*, investigating
**distributed OSGi (dOSGi / OSGi Remote Services)** for routing: DBO pods run in
one Kubernetes-managed virtual network, visible to each other; one pod serves one
or more tenants, and one large tenant may be served by multiple pods. Tenant
routing happens at the dOSGi service level — smarter and lighter than
Kubernetes-level scaling. Kubernetes remains responsible only for running parallel
instances and the security layer (network policy, secrets).

## R8 — FHIR eventing is a must-have

FHIR Subscriptions (topic-based, as normalized in R5/R6) are a core capability, not
an add-on. Eventing is built on the DBOS/Postgres substrate (outbox + streams +
`pg_notify`-class delivery), surfaced both as:

- **FHIR-standard subscriptions** (rest-hook, websocket, …) for external consumers,
  and
- an **in-process/OSGi event surface** for co-located consumers (the embedded
  dev/test scenario and same-pod modules), with identical semantics.

Whether the internal implementation is DBOS streams, a thin custom event layer on
Postgres, or both, is a concept-phase decision — but delivery must be durable,
tenant-scoped, and replayable from the outbox.

## Derived requirements (from the legacy post-mortem)

- **D1 — Synchronous read-your-writes.** A create/update returns only after data
  and outbox are committed in one transaction; FHIR interaction semantics
  (versionId, ETag, `Location`) are honored.
- **D2 — Parameterized SQL only.** No value is ever concatenated into SQL text.
- **D3 — Typed search ordering/filtering.** Envelope values carry type so numeric,
  date and token sorts are correct, with matching expression/GIN indexes.
- **D4 — Partitionable event ownership.** No single master processes all tenants'
  events; ownership shards per tenant/domain.
- **D5 — Migration as deployment.** Version-driven promotion (highest-version node
  leads, migrates, others passivate) is retained from the legacy design.
