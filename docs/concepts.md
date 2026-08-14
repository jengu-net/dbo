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
- DBOS state splits into two planes — platform DB for coordination that carries
  no resource content, the tenant's own DB for durable work whose checkpoints
  do (resolved in §7.4).

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

   **Evaluate first: the Karaf ecosystem.** Apache Karaf's dOSGi story
   (**Karaf Cellar**, `cellar-dosgi` feature) is notably more alive than Aries
   RSA — last Cellar release 2025-09, and a full refactoring toward a new major
   Cellar release has started; Karaf itself ships steadily (4.4.x through
   2026). The spike should assess Cellar before we build: does its dOSGi give
   us the registry-proxy model with acceptable control over routing
   properties? The known structural caveat: Cellar rides **Hazelcast** as its
   cluster substrate (discovery, distributed maps, eventing) — a second
   coordination substrate beside DBOS/Postgres, which R4's "database is the
   coordination substrate" direction argues against. Possible middle path:
   adopt Karaf as the container distribution (features, shell, provisioning)
   and Cellar's proxy mechanics as prior art, while keeping discovery on our
   DBOS assignment state.
3. **HAPI as personality dependency.** Direction: yes — each personality bundle
   embeds the HAPI stack for its FHIR version as *private* packages (same
   pattern as the DBOS embedding bundle, §7.1; HAPI jars carry no OSGi
   metadata, so bnd-wrapping is needed either way). It gives parsing,
   validation, and — decisively — a **FHIRPath engine** per version, which
   SearchParameter → envelope extraction requires; hand-building that per
   version is not a realistic alternative.

   Corrected premise: multi-version HAPI is *not* impossible on a flat
   classpath — it is designed for it (distinct `org.hl7.fhir.r4/.r5` model
   packages, `FhirContext.forR4()`/`forR5()` coexisting, one structure jar per
   version). What OSGi isolation actually buys is subtler and still real:

   - **Version-skew freedom.** On a flat classpath, all structure jars share
     one `hapi-fhir-base` + `org.hl7.fhir.utilities`/validator core, so every
     personality upgrades in lockstep, and cross-contamination exists (e.g.
     R4 profile snapshot generation historically pulling in R5 structures).
     Private packaging lets the R6-draft personality track fast-moving ballot
     snapshots while R4/R5 tenants stay on pinned, boring versions.
   - **A clean boundary rule**: HAPI types never cross the bundle boundary.
     The personality API toward dbo-core speaks payload bytes + typed envelope
     values + validation outcomes only.

   That boundary rule has a consequence to decide deliberately: the jengu
   platform's canon is "the typed HAPI R4 object *is* the domain model". In
   embedded mode the host's own HAPI and the personality's private HAPI are
   different classloaders even at the same version — so the host↔DBO surface
   is canonical JSON, not shared HAPI objects. Either we accept re-parse at
   that edge (cheap enough for dev/test; measure), or a personality may
   *optionally export* its model packages for a host that wants to share them.

   R6 status: no released `hapi-fhir-structures-r6`; R6 normative ballot
   started 2026-01, final publication 2027 at the earliest; draft R6 model
   code lives in the `org.hl7.fhir.core` validator stack. So the R6
   personality begins life on ballot-snapshot artifacts — exactly the
   fast-moving dependency the bundle isolation is for.

   Remaining spike items: per-personality footprint (structures + validator
   jars are tens of MB each) and startup cost (`FhirContext` is expensive —
   one per personality, created once, ideally lazily); whether we depend on
   full HAPI or only the leaner `org.hl7.fhir.core` stack per version.
4. **Per-tenant DBOS state — two planes.** Resolution: split by *what the
   workflow state contains*, not by who runs it.

   - **Platform plane** (platform DB): tenant→pod assignment, entry-role
     coordination, election, provisioning workflows. These carry no resource
     content. Hard rule inherited from R5: platform-plane workflow parameters
     and step outputs must never contain tenant credentials or resource
     content — the provisioning workflow tracks *status and references*; the
     operator and mounted secrets carry the actual credentials past it.
   - **Tenant plane** (the tenant's own DB, `dbos` schema): subscription
     delivery, retention sweeps, imports/exports, converter/reindex jobs —
     anything whose checkpoints inevitably contain resource content. This is
     DBOS's own "co-locate workflow state with the data" argument, and it buys
     three things at once: isolation holds (workflow checkpoints are tenant
     data and live behind the tenant's credentials); **erasure-by-drop** (drop
     the tenant DB and its entire durable history goes with it — clean GDPR
     story, and export includes in-flight state); and for a big tenant served
     by multiple pods, the tenant's own DB *is* the coordination substrate
     among exactly its serving pods — queues partition per tenant for free.
   - **Shared-RLS tier**: tenants in a shared database share one DBOS instance
     with tenant-scoped queue/topic naming; the dedicated tier stays the
     anchor.

   Costs accepted: N recovery/scheduler loops and poller connections — bounded
   because only a tenant's *serving* pods attach its DBOS instance (assignment
   decides attachment, §5).

   **Cross-boundary hops.** Every workflow *step* declares its plane at
   definition time — platform or tenant — and both kinds coexist inside one
   bigger process. A **hop** is where the executing plane changes:
   tenant → tenant, tenant → platform, or platform → tenant. Hops are special
   and **always coordinated by the platform**, never a direct tenant-to-tenant
   connection (which would break isolation — tenant A must never reach tenant
   B's database or hold its credentials). The bigger process *is* a
   platform-plane workflow anyway; it invokes tenant-plane sub-workflows in
   each tenant's own DBOS and carries only references between them.

   - **Content routing under the no-content rule**: the platform-plane parent
     passes references; actual resource content moves tenant-plane to
     tenant-plane over the routing layer (§5) — e.g. the sender's tenant-plane
     step delivers to the receiver's ingress, under a platform-issued,
     process-scoped grant. Platform checkpoints stay content-free.
   - **Hops are audit events by definition** — they are boundary crossings.
     Three records per hop: the sender tenant logs egress and the receiver
     logs ingress (full-fidelity, as `AuditEvent`/`Provenance` in each
     tenant's own store, linked to the process instance), while the platform
     logs the hop coordination itself (metadata, participants, process id,
     content hashes — never content).
   - Motivating cases: a healthcare provider communicating through the
     national API provided by the zone tenant; provider ↔ insurer exchange.
     These are exactly the flows that must be auditable at the boundary
     regardless — the hop model makes the audit structural instead of
     per-integration.

   Spike item (feeds §7.1): whether `dev.dbos:transact` supports multiple
   launched runtimes against different system databases in one JVM — the
   standard model is one runtime per process. If not: per-tenant instantiation
   inside the embedding bundle's own classloader, or the native-patterns
   fallback — behind the same whiteboard interfaces either way, so consumers
   never know.
5. **Search completeness — tiered, evidence-based.** Resolved by measuring
   instead of guessing: an inventory of all ~206 production FHIR search call
   sites across jengu-platform, lab and VA
   ([search-usage-inventory.md](search-usage-inventory.md), 2026-08) shows
   jengu uses a narrow, conservative slice — token `identifier=` lookup
   dominates (~88 sites), nearly everything is `_count`-bounded and
   `-_lastUpdated`-sorted, and `_filter`/`_has`/composites/full-text have zero
   production usage.

   **Tier 1 — drop-in parity (day one).** Everything today's call sites use:
   token (`sys|val`, `sys|`, plain), reference, string, canonical `url=`,
   date prefixes on `_lastUpdated`; result params `_count`, typed `_sort`,
   `_elements`, `_summary=count`, `_total`, offset/next-link paging; `_tag`,
   `_profile`, `_id`; modifiers `:missing`, `:not`, `:exact`, `:identifier`;
   **one-level chaining** and **single named `_include`** (both in production
   at low volume); **conditional create/update/delete** on search criteria
   (~50 sites — the bootstrap upsert workhorse); operations `$expand`,
   `$validate`, `$lookup`; Subscription-criteria evaluation. All of tier 1 is
   envelope + identifier + reference-table mechanics — no new machinery.

   **Tier 2 — deletes known workarounds.** Features with a waiting consumer:
   `_has` (the device framework's outbound-candidate contract sketches it and
   currently degrades), `_revinclude`, richer chaining, `$everything`
   (docs-planned), a real `$match` (MPI's `match()` is an `identifier=`
   search wearing the name), `:of-type`, Observation composites. Today these
   gaps force client-side Java post-filtering (audit queries, lab worklists);
   tier 2 is justified by deleting those workarounds.

   **Tier 3 — not until a consumer exists.** `_filter`, `_text`/`_content`
   (Postgres FTS when wanted — the legacy repo's ambition), `:above`/`:below`
   terminology navigation, `_list`/`_query`/`_language`, GraphQL. Explicitly
   out of scope per milestone.

   **Honesty mechanism**: each personality generates its `CapabilityStatement`
   from the actually-implemented parameter set, and search is **strict by
   default** — an unsupported parameter is rejected, never silently ignored
   (a silently dropped filter returns a *wrong result set*, which in a
   clinical system is a safety issue, not a compatibility feature).

   **Custom SearchParameters**: none exist today, but devices/sibling models
   (R6) make personality-registered custom parameters + reindex a
   first-class feature — the envelope's derived-projection design already
   supports it (register extraction rule → rebuild envelope → new index).

   **Migration notes** (feeds §7.6): Medplum-proprietary usages needing
   equivalents or retirement — `_project` (obsolete: tenancy is structural in
   DBO), `_compartment` (one Subscription criteria), `CodeSystem/$import`,
   `Project/$init`, `$expunge` (becomes erasure-by-drop, §7.4), `$meta-add`.
6. **Migration path off Medplum.** Not designed here yet — but R6's
   version-agnostic core means jengu's R4 data can load as an R4 personality
   tenant and upgrade-on-read toward R5/R6 later. Deserves its own concept doc
   once the engine shape settles.
