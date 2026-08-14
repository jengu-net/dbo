# DBO — conceptual solution sketch

Working notes toward the architecture. Everything here is a proposal to be argued
with; [requirements.md](requirements.md) is the contract.

## 1. Layering

```
┌───────────────────────────────────────────────────────────────┐
│ OSGi container (Felix)                                        │
│                                                               │
│  ┌──────────────┐  ┌───────────────┐  ┌────────────────────┐  │
│  │ dbo-core     │  │ dbo-fhir-r4   │  │ dbo-fhir-r5 / r6…  │  │
│  │ engine (no   │  │ version       │  │ (parsing, profile  │  │
│  │ FHIR, no     │  │ personality   │  │ validation, search │  │
│  │ framework)   │  │ bundle        │  │ param extraction)  │  │
│  └──────┬───────┘  └───────┬───────┘  └─────────┬──────────┘  │
│         │                  └──────────┬─────────┘             │
│  ┌──────┴──────────┐  ┌───────────────┴──────┐               │
│  │ dbo-postgres    │  │ per-tenant service    │               │
│  │ (JDBC, virtual  │  │ sets (DataSource,     │               │
│  │ threads, DBOS)  │  │ storages, SSO client) │               │
│  └─────────────────┘  └──────────────────────┘                │
└───────────────────────────────────────────────────────────────┘
```

- **dbo-core** — the version-agnostic object engine: envelope model, identifiers,
  references, outbox, search criteria SPI. Plain Java, zero framework, zero FHIR.
- **Version personality bundles** (`dbo-fhir-r4`, `-r5`, `-r6`, and non-FHIR
  siblings) — everything that knows what the payload *means*: resource parsing,
  validation, SearchParameter → envelope extraction, Subscription topic evaluation.
  A tenant/domain binds to one personality; several personalities coexist in one
  container.
- **dbo-postgres** — JDBC on virtual threads (no reactive driver), DBOS-style SQL,
  Liquibase with advisory session locks.
- **Per-tenant service sets** — registered/retracted dynamically in the OSGi
  service registry as tenants arrive, move, or leave (see §4).

Embedded mode: a host application (jengu cloud in dev/test, story-e2e harnesses)
starts Felix in-JVM, installs the same bundles, and talks to DBO through its Java
API — the only shared dependencies are Felix and the OSGi API.

## 2. Object model (from the legacy durable core)

- **Payload** — the resource as opaque canonical JSON, the single source of truth.
  Never partially updated; a write replaces the payload.
- **Envelope** — a derived, typed, searchable projection recomputed from the payload
  on every write by the bound personality (for FHIR: from SearchParameter
  definitions). Stored beside the payload; carries all indexes. Because it is
  derived, it can always be rebuilt — reindexing is an operation, not a migration.
- **Identity** — one internal id + N external `{system, value}` identifiers,
  extracted from the payload, unique per (tenant, type, system, value).
- **References** — extracted edges `(owner, refType, target)`, owner-controlled
  (delete + reinsert on owner write), powering `_include`/`_revinclude`-class reads
  and referential queries.
- **History** — every write appends a version row (FHIR `versionId`, `_history`);
  the outbox row is written in the same transaction (D1: read-your-writes).
- **Type/version upgrade-on-read** — payloads carry their schema version; converters
  registered by personalities upgrade old payloads lazily, so FHIR version
  transition (R5 → R6) inside a tenant is a converter + reindex, not a big-bang
  migration.

## 3. Postgres layout (per tenant database)

Sketch, per domain (a physical table-group knob kept from legacy):

```
<domain>_data        (tenant-implicit: whole DB is one tenant in the dedicated tier)
  id, resource_type, version_id, fhir_version, last_updated,
  envelope JSONB (typed values), payload JSONB or BYTEA, deleted
<domain>_history     (append-only version rows)
<domain>_identifier  (system, value, resource_id; unique)
<domain>_reference   (owner_id, ref_type, target_type, target_id)
<domain>_outbox      (DBOS-managed stream / transactional outbox)
```

- GIN/expression indexes on envelope paths declared by the personality's search
  parameters — indexing is part of the personality contract, not an afterthought.
- In a shared-tier database, the same layout gains a `tenant_id` column in every
  PK + RLS policies; the dedicated tier stays the design anchor (R5).
- DBOS runs in a dedicated schema of the *platform* database for cross-instance
  coordination; per-tenant durable work runs on the tenant's own database where
  isolation demands it. (Open question §7.)

## 4. Tenant lifecycle — credential-blind provisioning

```
tenant manager ──(TenantRegistration CR)──▶ k8s operator
                                              │ creates DB + role,
                                              │ writes k8s Secret
                                              ▼
serving pod ◀──(secret mount / projected volume)
  │ dbo-tenant-provisioner bundle watches mounts,
  │ builds HikariCP pool, registers services:
  │   DataSource        (service.props: tenant=<id>)
  │   ObjectStore       (tenant=<id>, personality=r5)
  │   SubscriptionFeed  (tenant=<id>)
  ▼
application code: registry lookup by tenant id — uses the pool,
                  never sees credentials
```

- The tenant manager knows *that* a tenant exists and *where* it is served — never
  its credentials.
- Tenant SSO follows the same pattern: per-tenant IdP config delivered as secret,
  materialized as a per-tenant OSGi service.
- De-provisioning = retracting the service set + operator-driven teardown; the
  registry dynamics give in-flight callers a clean "tenant unavailable" instead of
  broken pools.

## 5. Scaling — tenant-aware routing over dOSGi

- One Kubernetes-managed flat network; every DBO pod sees every other pod.
- Assignment maps tenants → pods: a pod serves one or more tenants; a big tenant
  spans multiple pods. The assignment itself is durable state (DBOS) with
  version-driven takeover semantics inherited from the legacy election design.
- Routing is **dOSGi-like**: a tenant's `ObjectStore` service is local on its
  serving pods and a remote proxy everywhere else, so callers do a plain registry
  lookup and the topology is invisible. This is application-level, tenant-smart
  routing; Kubernetes only runs instances and enforces the security layer.
- The layer is **our own**, purpose-built (see §7.2): registry programming model
  and remote proxies as in OSGi Remote Services, but discovery driven by the
  durable tenant→pod assignment and a single controlled transport — not a full
  RSA implementation (Aries RSA / ECF serve as prior art only).

### Two-hop routing: locality first, tenancy second

Not every pod needs to be an entry point. A subset of nodes are (internally)
**"public" dOSGi nodes** — e.g. one per zone — and routing happens in two hops
with a different concern at each level:

1. **Kubernetes level — requestor-location-based resolution.** An incoming
   request is routed/redirected to the *closest* public node relative to the
   requestor (topology-aware routing / zone-local Service semantics, or an
   explicit redirect from a resolver endpoint). Kubernetes decides *where you
   enter* the mesh, using what it actually knows: network topology and locality.
2. **dOSGi level — tenant resolution.** The zone/public node then routes to the
   pod(s) actually serving the tenant via the registry-driven routing table.
   dOSGi decides *who serves you*, using what it knows: the tenant → pod
   assignment.

Consequences to design for:

- The tenant → pod assignment (§ above) gains a locality dimension: the
  assigner should prefer placing a tenant's serving pods in the zone where its
  requests originate, so the second hop is usually zone-local and the entry
  node's forward is cheap or a no-op (entry node *is* a serving pod).
- Public nodes are a role, not a separate binary: any DBO pod can be flagged
  into the entry role; the role is part of the durable assignment state.
- Redirect vs. proxy at hop 1 is an open choice per protocol: FHIR REST can use
  HTTP redirect to the tenant's home entry point; websocket subscriptions and
  in-mesh dOSGi calls proxy.

## 6. Eventing

- **Source of truth**: the transactional outbox row, committed with the write.
- **Distribution**: DBOS streams/queues + `pg_notify`-class wakeups; no external
  broker.
- **FHIR surface**: R5/R6-style topic-based Subscriptions (backported to the R4
  personality) — rest-hook and websocket channels, per-tenant, durable,
  replayable from the outbox watermark.
- **In-process surface**: the same topics as OSGi events/callbacks for co-located
  consumers — identical semantics in embedded dev/test and in production.
- Legacy's broker-agnostic `Eventing` SPI (compaction keys, tombstones,
  dead-letter) is a good shape for the internal interface.

## 7. Open questions / known risks

1. **DBOS Java + OSGi interplay.** DBOS's Java library (`dev.dbos:transact`) is
   Spring-adjacent in packaging. Planned approach: an **embedding bundle** —
   `dbo-dbos` packs DBOS and its Spring-adjacent dependencies as *private*
   (non-exported) packages, so nothing Spring leaks into the container's wiring;
   the bundle's only exports are DBO-owned interfaces. DBOS capability is then
   served by the **whiteboard pattern**: the bundle registers DBOS *client*
   services in the OSGi registry (per tenant and/or per domain, with service
   properties carrying parallel-scaling info — queue partitions, executor
   concurrency, serving-pod role), and consumers — storages, subscription
   feeds, the tenant assigner — simply look them up; conversely, workflow/step
   implementations register *themselves* into the whiteboard and the embedding
   bundle enrolls them with DBOS. Tenant arrival/departure becomes plain OSGi
   service dynamics. The spike then only has to verify classloading (DBOS's
   proxying/reflection under a bundle classloader) rather than architecture.
   Worst case remains: implement the DBOS *patterns* (Postgres queues, exactly-
   once steps) natively in dbo-core behind the same whiteboard interfaces.
2. **dOSGi layer — build our own.** Aries RSA / ECF activity is low, and the
   full Remote Services spec solves a general problem we don't have. Current
   thinking: a **purpose-built dOSGi-like layer** shaped by our actual needs —
   remote proxies for a *known, small* set of DBO-owned service interfaces;
   discovery from the durable tenant→pod assignment (DBOS state) instead of
   generic topology gossip; tenant id + locality + serving-role as first-class
   routing properties rather than opaque service filters; one transport we
   control (gRPC or plain HTTP/2) with mTLS inside the mesh. The OSGi service
   registry stays the programming model (consumers look up `ObjectStore` for a
   tenant and may get a local instance or a remote proxy — indistinguishable);
   we just don't buy the spec's generality: no dynamic interface export, no
   pluggable discovery providers, no config-admin ceremony. Aries RSA/ECF
   remain reference material for proxy/classloader mechanics. The spike now
   sizes our own layer (proxy generation over a fixed interface set is small)
   rather than auditing someone else's.
3. **HAPI as personality dependency?** HAPI structures per FHIR version inside
   separate bundles would give parsing/validation for free and OSGi would isolate
   the version conflicts that make multi-version HAPI impossible in one flat
   classpath — this is one of the strongest arguments *for* OSGi here. Size/startup
   cost to be measured.
4. **Per-tenant DBOS state.** Does durable-workflow state live in the tenant DB
   (perfect isolation, N schedulers) or platform DB (one scheduler, weaker
   isolation)? Likely tiered like storage itself.
5. **Search completeness.** Full FHIR search (chained params, `_include`,
   modifiers, `_filter`) is a large surface; define the supported subset per
   milestone explicitly rather than implying completeness.
6. **Migration path off Medplum.** Not designed here yet — but R6's
   version-agnostic core means jengu's R4 data can load as an R4 personality
   tenant and upgrade-on-read toward R5/R6 later. Deserves its own concept doc
   once the engine shape settles.
