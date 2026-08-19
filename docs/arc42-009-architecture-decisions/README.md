# Resolved questions and known risks (§7)

## 7. Open questions / known risks

### 7.1 DBOS Java + OSGi interplay

DBOS's Java library (`dev.dbos:transact`) is
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
service dynamics. What remained to establish was classloading — DBOS's
proxying and reflection under a bundle classloader — rather than architecture.
Worst case remains: implement the DBOS *patterns* (Postgres queues, exactly-
once steps) natively in dbo-core behind the same whiteboard interfaces.

**VERDICT: ADOPT — proven.** Every scenario holds, on Felix 7 in-JVM
against a real Postgres:

- *A — runtime in a bundle*: `dev.dbos:transact` 1.0.0 launches inside a
  bundle with all deps private (Bundle-ClassPath nested jars); schema
  migration loads its resources from the nested jar; step checkpointing
  and same-workflow-id idempotency work.
- *B — crash/relaunch/resume*: shut the engine down mid-workflow, new
  `DBOS` instance over the same system DB, `resumeWorkflow` — completed
  with the pre-crash step **not** re-executed. Checkpoint replay works
  with workflow classes from a foreign bundle classloader.
- *C — whiteboard*: the workflow implementation lives in a contributor
  bundle importing only the api + `dev.dbos.transact.workflow`
  (annotations); steps cross the boundary through a DBO-owned
  `StepRunner`; `registerProxy` accepts the cross-classloader impl.
- *D — multiple runtimes, one JVM*: **`DBOS` is an instantiable class,
  not a singleton** — two runtimes over two system databases in one JVM,
  workflows isolated (neither sees the other's ids). The §7.4 per-tenant-
  plane model needs no workaround.

Facts that de-risked everything: core `transact` has **no Spring
dependency** (deps: jspecify, kotlin-stdlib, cron-utils, HikariCP,
Jackson 3, postgresql, slf4j) — Spring-adjacency is only in the starter,
which DBO does not use.

Landmine log (one entry): JDBC `DriverManager` does not discover drivers
on a bundle classpath — `Class.forName("org.postgresql.Driver")` through
the bundle classloader before first pool creation; caller-visibility then
passes since Hikari shares the classloader. No TCCL fixes, no ServiceLoader
issues, no logging clashes were needed.

Carried into production: export only the annotations
package (later replaced by dbo-process's own annotations); the
`StepRunner`-style DBO-owned boundary held with zero DBOS types leaking;
one embedding-bundle copy serves N runtimes.

### 7.2 dOSGi layer — build our own

Aries RSA / ECF activity is low, and the
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
remain reference material for proxy/classloader mechanics. The work is
sizing our own layer — proxy generation over a fixed interface set is
small — rather than auditing someone else's.

**VERDICT (Cellar evaluation, 2026-08-14): REJECT Cellar — build our own,
as planned.** Source-level review of `cellar-dosgi` at `apache/karaf-cellar`
main:

- **Activity, corrected.** The earlier "more alive" impression does not
  survive contact: the 2025-08/09 flurry was a maintenance release (4.4.8,
  "cleanup and update to support Karaf 4.4.x"), essentially one maintainer
  plus dependabot; nothing since 2025-09. No refactoring toward a new
  major is visible on main. Cellar still pins **Hazelcast 3.12** — a
  long-EOL major — which alone disqualifies it for a medical platform.
- **Mechanics, measured.** The whole dosgi module is ~13 small classes.
  A remote call is a Java-serialized
  `RemoteServiceCall{endpointId, methodName, args}` pushed through
  Cellar's generic Hazelcast command-execution context; the caller gets a
  `Map<Node, Result>` and **returns the first entry of an arbitrary map
  iteration**. Dispatch is by method *name* (overloads ambiguous),
  serialization is Java serialization (schema coupling + a deserialization
  attack surface), there is no property-based routing, no locality, no
  partitioning, no streaming, no transport control. Every dimension DBO
  routing needs (tenant id, locality, serving-role, mTLS, lean frames —
  §5, §10) is absent.
- **Prior-art value**: genuinely useful but small — the
  `RemoteServiceFindHook` + proxy + endpoint-map *shape* confirms a
  purpose-built layer is a modest build, which the own-layer plan already
  assumed. Nothing worth importing as a dependency.
- **Karaf-the-container** (features model, shell, provisioning) remains a
  separate, open option — Karaf itself ships steadily (4.4.x through
  2026) — to be decided when packaging/distribution becomes real work.
  The embedded in-JVM mode runs on plain Felix regardless, which is
  proven.

With this, every §7 question is closed: DBOS adopt (§7.1), own
routing layer with Cellar rejected (§7.2), HAPI personalities confirmed
(§7.3), planes resolved (§7.4), search tiers evidence-based (§7.5),
adoption path set (§7.6).

### 7.3 HAPI as personality dependency

Direction: yes — the HAPI stack rides inside the runtime as embedded packages
rather than as a flat-classpath dependency (same pattern as the DBOS embedding
bundle, §7.1; HAPI jars carry no OSGi metadata, so bnd-wrapping is needed
either way). It gives parsing, validation, and — decisively — a **FHIRPath
engine** per version, which SearchParameter → envelope extraction requires;
hand-building that per version is not a realistic alternative. Where the stack
sits — one shared bundle rather than a private copy per personality — is
resolved below.

Corrected premise: multi-version HAPI is *not* impossible on a flat
classpath — it is designed for it (distinct `org.hl7.fhir.r4/.r5` model
packages, `FhirContext.forR4()`/`forR5()` coexisting, one structure jar per
version). What OSGi isolation actually buys is subtler and still real:

- **Version-skew freedom, if a personality needs it.** All structure jars in
  one bundle share a `hapi-fhir-base` + `org.hl7.fhir.utilities`/validator
  core, so the personalities riding the shared stack upgrade in lockstep —
  which is the trade the shared stack makes, and the right one while they
  all sit on the same released HAPI. A personality that must skew (the
  R6-draft one, tracking fast-moving ballot snapshots) buys it back by
  embedding its own stack privately instead of importing this one: the
  packaging is per personality, not per platform.
- **A clean boundary rule**: HAPI types never cross the bundle boundary.
  The personality API toward dbo-core speaks payload bytes + typed envelope
  values + validation outcomes only.

That boundary rule has a consequence to decide deliberately. A host
platform whose own canon is "the typed HAPI object *is* the domain model"
meets a wall in embedded mode: the host's HAPI and the personality's private
HAPI are different classloaders even at the same version — so the host↔DBO
surface is canonical JSON, not shared HAPI objects. Either we accept
re-parse at that edge, or a personality may *optionally export* its model
packages for a host that wants to share them.

**RESOLVED: both, from one shared bundle.** Private embedding per
personality was measured at 138.0 MB for R4 and 155.7 MB for R5, of which
140.4 MB is byte-identical between them — and Felix caches each installed
bundle per framework, so a deployment giving a tenant its own framework pays
it again every time. The stack is one indivisible engine (the HL7 validator
converts everything up to R5, validates there, and maps back), which is
exactly what makes it a good thing to own once.

So: **one bundle owns the HL7/HAPI stack** — `dbo-fhir-stack` — and exports
it. A personality keeps its own code and the validation resources of its own
version (`hapi-fhir-validation-resources-rX`: R4 ships profile and value-set
XML under `org/hl7/fhir/r4/model/`, R5 ships NPM tarballs under
`org/hl7/fhir/r5/packages/`), exports those resource directories back, and
imports the engine. Version-keyed services are unchanged: a tenant's declared
FHIR version selects a personality, and nothing above it learns what HAPI is.

Two things cross that boundary, and a consumer picks which:

- **Canonical JSON**, for anything that wants no HAPI wire at all. This is
  the boundary rule unchanged, and it stays the only thing on dbo's own
  public API — enforced by `ApiBoundaryTest`.
- **The engine packages**, for hosts and personalities that would rather
  share class identity than re-parse. `org.hl7.fhir.*` and `ca.uhn.fhir.*`
  in full, from the one bundle that has them.

Exporting the engine whole rather than a narrow model-only slice is what lets
a personality keep calling `FhirContext`, `FhirValidator` and `IFhirPath`
directly instead of moving that code inside the shared bundle. The split-package
objection to a wide export does not apply to a single exporter: `org.hl7.fhir.*`
spans dozens of jars, but they are all inside one bundle, so there is exactly
one candidate for every package. What the wide export does demand is that the
list be complete — which is why it is read off the jars at build time, and why
the personalities' engine imports are read off *that* list rather than written
by hand. A missing entry resolves and then throws `NoClassDefFoundError` on
first use.

The reflection hazard argues the same way. `FhirContext` locating structures
by classloader-sensitive lookup is dangerous when it must see classes a
*personality* defines; here a personality defines none and imports them from
the bundle `FhirContext` itself lives in — one wire, one identity, nothing
to discover across a boundary.

R6 status: no released `hapi-fhir-structures-r6`; R6 normative ballot
started 2026-01, final publication 2027 at the earliest; draft R6 model
code lives in the `org.hl7.fhir.core` validator stack. So the R6
personality begins life on ballot-snapshot artifacts — exactly the
fast-moving dependency the bundle isolation is for.

**VERDICT: CONFIRMED — HAPI per personality works.** Every scenario
holds, on HAPI 8.10.1 and Felix 7 in-JVM:
R4 parse + FHIRPath (incl. identifier-extraction expressions), R5
coexisting with genuine divergence (R4 rejects `SubscriptionTopic`),
R4 profile validation flags structural errors, and the boundary rule held
— JSON in/out, zero HAPI types crossed the api.

Measured, with the engine shared and the authoring-side transitives
excluded: `dbo-fhir-stack` **95.7MB**, personalities **5.4MB** (R4) and
**23.2MB** (R5) — their validation resources and their own code. The whole
serving bundle set is 154MB, and a third personality costs its resources
rather than another engine. `FhirContext` init ~0.4s (R4) / ~0.8s (R5), lazy.

Landmine log:
1. **TCCL** — HAPI's cache-provider discovery (`HAPI-2200`) is
   ServiceLoader/TCCL-based; every personality entry point must run with
   the classloader that owns the engine as TCCL (a `withTccl` wrapper).
   That loader is asked for by way of a HAPI class, not the personality's
   own: with a shared stack the two are different bundles, and only the
   engine's loader carries its `META-INF/services`.
2. **R5 FHIRPath eagerly builds `DefaultProfileValidationSupport`** — pure
   path evaluation drags `hapi-fhir-validation` +
   `hapi-fhir-validation-resources-r5` (the base-profile npm package)
   into the bundle. Footprint and memory follow.
3. **Memory**: loading the R5 core-profile package OOMs a 512MB heap;
   2GB is comfortable. Personality memory budgets are real numbers, not
   rounding errors.
4. **Parse-time vs validate-time errors**: HAPI's parser rejects invalid
   required-binding codes at *parse* time (`HAPI-1821`) before any
   validator runs — the REQ-DBO-VER-SPECIFIED-VALIDATION semantics must
   define which errors surface at which stage.
5. **Finding**: the HL7 validator core is internally R5-based, so the R4
   validation path legitimately uses `org.hl7.fhir.r5` model classes. That
   is why the engine is indivisible, and why the R4 personality does not
   import the R5 model even though its validator walks it: the R5 classes
   are reached inside the shared bundle. The duplication was never in what
   a personality uses — it was in what the engine needs.

Still open (non-blocking): whether a leaner `org.hl7.fhir.core`-only
dependency (skipping the `ca.uhn` layer) is worth it per personality —
revisit when the R6 ballot personality is built, since that one starts
from the core stack anyway.

### 7.4 Per-tenant DBOS state — two planes

Resolution: split by *what the
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

ANSWERED (see the §7.1 verdict): `DBOS` is an
instantiable class, and multiple launched runtimes against different
system databases coexist in one JVM with isolated workflow state. The
per-tenant plane needs no workaround.

### 7.5 Search completeness — tiered, evidence-based

Resolved by measuring
instead of guessing: an inventory of all ~206 production FHIR search call
sites across a clinical cloud, a laboratory system and a visit assistant
([search-usage-inventory.md](../evidence/search-usage-inventory.md), 2026-08) shows
a narrow, conservative slice in real use — token `identifier=` lookup
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

**Adoption notes** (feeds §7.6): the server-proprietary usages an incoming
deployment has to replace rather than port — a project search parameter
(obsolete here: tenancy is structural), a compartment search parameter, bulk
terminology import, project initialisation, and expunge (which becomes
erasure-by-drop, §7.4).

### 7.6 Adoption path from an existing FHIR server — resolved

A deployment already running on another FHIR server does not adopt DBO by
flipping a base URL, and the reason is worth stating plainly: **the hard
coupling is never storage**. Search, CRUD and conditional writes sit inside
tier 1 behind a client seam that any deployment already has. The coupling is
**identity and tenancy expressed in the incumbent's proprietary vocabulary** —
a project resource standing in for a tenant, roles hung off a membership
resource, credentials stored in a settings array, cross-tenant links with no
FHIR equivalent. None of that ports; all of it has to be re-expressed.

Hence the order: identity first, storage second.

1. **Extract identity while still on the incumbent.** Whatever issues tokens
   today stops being the incumbent's concern. In DBO the tenant is its own
   authority (§13), so this step is what makes the rest a storage question.
2. **Reach tier-1 parity behind the existing seam.** The embedded in-JVM
   store replaces the incumbent's test container first — development and test
   run on DBO long before production does, which is where the parity gaps
   surface cheaply.
3. **Flip tenant by tenant.** Configuration is recreated from its source of
   truth rather than migrated. Clinical data that must survive moves as
   NDJSON; everything derivable is re-derived. Version transitions come later
   through upgrade-on-read, not during the move.
4. **Move the edge.** An edge runtime hosting DBO bundles in its own JVM
   drops both the incumbent server and its cache tier.
5. **Decommission.**

No dual-write, no live synchronisation, and no compatibility layer beyond the
FHIR surface itself. A compatibility layer for a proprietary vocabulary would
outlive the migration it was built for.

### 7.7 The model behind a face — HL7 core rather than HAPI's shape

§7.3 keeps the HAPI stack for what it decisively provides: parsing, validation,
and a FHIRPath engine per version, without which SearchParameter → envelope
extraction is not realistic. That remains true. What is worth separating from it
is the **model** — the typed objects a personality parses into.

Direction: parse into the HL7 core model (`org.hl7.fhir.core`) rather than
HAPI's structures. Three reasons, in order of weight:

1. **R6 exists there already.** The ballot model, its validator and its
   converters are published in that project before any HAPI release wraps them.
   §7.3 anticipated a personality tracking ballot snapshots and made packaging
   room for it; this is what that personality would parse into, and founding
   requirement R6 asks for the versions to be plural in fact rather than in
   principle.
2. **It is the reference implementation**, not a server framework's rendering of
   one. A store that means to be version-plural is better placed on the thing the
   versions are defined against.
3. **It shortens the distance to a face that is not FHIR.** The less a
   personality's model owes to one server's shape, the smaller the step to a
   sibling model, which is the second half of R6's ask.

**Not resolved — what has to be established first:**

- Which artifacts actually carry which packages. The `org.hl7.fhir.*` package
  names appear in both projects and the split is not obvious from the names; the
  answer decides whether this is a dependency change or a rewrite.
- Whether the validator and the FHIRPath engine can be driven from the core
  model without HAPI's `FhirContext` around them, and what that costs.
- What the R6 ballot artifacts are actually called and how often they move.

**What makes this cheap to be wrong about.** Payload is truth and stored bytes
are never rewritten, so the model is a lens over what arrived rather than the
form it is kept in. A representation that round-trips imperfectly cannot corrupt
storage, and a change of model is reversible — which is the reason to hold the
payload seam (§1) as bytes at the boundary and let a face parse into whatever it
likes behind it. That property is worth more than any particular model, and it is
the one thing here not to trade away.

Protocol-buffer models were weighed and are not the direction, though they are
the natural thing to weigh: they are compact and fast, and generated messages can
be shaped to a ballot or to a domain that is not FHIR. Against that, a generated
schema meets FHIR's extensibility awkwardly — extensions, primitive extensions,
contained resources, and elements no message has been generated for yet — which
is precisely the ground a store means to be exact about. If they are tried, the
way to try them is a face beside the others rather than under them, measured on
the production path, because that is what having faces is for.
