# Resolved questions and known risks (§7)

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

   **VERDICT (spike dbo#1, 2026-08-14): ADOPT.** All scenarios pass
   (`spike/dbos-felix/`, Felix 7 in-JVM, Postgres via Testcontainers):

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

   Production notes carried out of the spike: export only the annotations
   package (later replaced by dbo-process's own annotations); the
   `StepRunner`-style DBO-owned boundary held with zero DBOS types leaking;
   one embedding-bundle copy serves N runtimes.
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

   Spike item — ANSWERED by dbo#1 (see §7.1 verdict): `DBOS` is an
   instantiable class, and multiple launched runtimes against different
   system databases coexist in one JVM with isolated workflow state. The
   per-tenant plane needs no workaround.
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
6. **Migration path off Medplum — resolved.** Full inventory of every Medplum
   dependency dimension in
   [medplum-usage-inventory.md](medplum-usage-inventory.md); the path itself in
   [medplum-migration.md](medplum-migration.md). Central finding: the hard
   coupling is not storage (search/CRUD/conditional writes sit inside DBO
   tier 1, behind a clean client seam) but **identity and tenancy expressed in
   Medplum's proprietary vocabulary** — Project-per-tenant, roles on
   `ProjectMembership`, credentials in `Project.setting[]`, and `Project.link[]`
   zone chains (no FHIR equivalent). Hence: identity-first, storage-second.
   Phases: (0) the already-planned Spring Authorization Server workstream
   extracts identity while still on Medplum; (1) DBO reaches tier-1 parity
   behind the per-tenant base-URL seam, starting with the embedded in-JVM
   store replacing the Medplum testcontainer in dev/test; (2) tenant-by-tenant
   greenfield flip (recreate from git; R4 NDJSON export→ingest only where
   clinical data must survive; R5/R6 via upgrade-on-read later); (3) edge
   drops Medplum+Redis, hosting DBO bundles in the edge JVM; (4) decommission.
   No dual-write, no live-sync, no compat layer beyond the FHIR surface jengu
   actually uses.

