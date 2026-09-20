**Status: Resolved.** Reflected in [running it](../arc42-008-crosscutting/running-it/README.md).

# Durable work sits in two planes

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

ANSWERED (see the [record 001](001-dbos-runs-inside-a-bundle.md) verdict): `DBOS` is an
instantiable class, and multiple launched runtimes against different
system databases coexist in one JVM with isolated workflow state. The
per-tenant plane needs no workaround.
