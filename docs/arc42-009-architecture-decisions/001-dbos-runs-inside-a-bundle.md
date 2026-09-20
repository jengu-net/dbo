**Status: Adopted.** Reflected in [building blocks](../arc42-005-building-blocks/README.md).

# DBOS runs inside a bundle

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
  workflows isolated (neither sees the other's ids). The [record 004](004-durable-work-sits-in-two-planes.md) per-tenant-
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
