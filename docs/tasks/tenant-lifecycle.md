# The tenant lifecycle, and applying to a tenant that is already up

**Status** — designed, nothing built. Two findings shape it: the applier this
store ships has no production caller (`new ConfigApplication(` matches only the
harness), and **a changed spec is never applied to a running tenant at all** —
not applied, not refused, not reported. The bring-up queue that consumers are
blocked on is one symptom of the first; the second is the larger hole.

**Issues** — [#188](https://github.com/jengu-net/dbo/issues/188) (the tenant
queue, which is the symptom this document reframes). Consumer half:
[platform#965](https://github.com/jengu-net/jengu-platform/issues/965).

**Concepts** —
[processes and work](../arc42-008-crosscutting/processes-and-work.md) ·
[change, and who is listening](../arc42-008-crosscutting/change-and-who-is-listening.md) ·
[an operator's control plane](operator-control-plane.md) ·
[the REQ catalogue](../arc42-006-runtime/req-catalogue.md)

## What this is

This is the **tenant lifecycle**: declared, provisioned, served, *changed*,
retracted, erased. One verb runs through it — apply — and bring-up is simply
apply against an empty tenant. A configuration change to a tenant that is
already up and serving is the same verb against a live one, and that is the
case this store cannot do at all today.

**Apply is a step somebody calls**, not a routine private to a loop. It has
three callers and they are the same path: the bootstrap process calls it
against an empty tenant, a fetch that found a delta calls it against a live
one, and **an administrator calls it directly** — because the source changed
and should be applied now, because a previous application was fixed and should
be re-evaluated, or because automatic application is switched off for this
scope and applying is deliberately a decision somebody makes. What differs
between the three is who authored the work and what the run says about them.
Nothing else.

dbo already has the applying half. `ConfigApplication` — `dbo.config.applied` /
`apply` — takes a declared set and applies it as a sweep: one durable run per
scope, per-item skips with reasons, a tally somebody can read, the declarer's
correlation echoed and never parsed, and closure by re-evaluation so fixing a
declaration closes its card. Forty-six read, forty-four applied, two skipped
with reasons, rather than a zone that silently did nothing.

Nothing in production constructs it. What production has instead is
`TenantRuntimeManager.scanOnce()`: a fetch, a delta and an apply, written for
one source (a directory) and one declared type (a tenant spec), with the
reconcile expressed as a snapshot diff and the parallelism expressed as a
`for` loop. That loop is why a consumer declaring two dozen tenants gets the
first few — but making *it* concurrent would be hardening the second applier
while the first one still has no caller.

The missing half, in both, is the same: **fetch**. Neither knows how to read a
source, compute what changed since the last read, and hand that on. That is the
piece to build, and it is what makes a git repository, a ConfigMap, a directory
and a lane from the cloud four sources rather than four designs.

And beneath that is the hole the queue was hiding. Configuration reaches a
tenant along three paths with nothing in common:

- **Content configuration** — profiles, search parameters, value sets, policy,
  zone declarations — are records, and a live tenant already reacts to them:
  `shapesRound()` watches the store's own feed and rebuilds the validation view
  or reindexes when one arrives "by paths its facade cannot see". Hot apply
  works, and `ConfigApplication` is the front door for writing it that nothing
  calls.
- **Spec configuration** — the tenant's own declaration: face and version,
  types, PDI, dependencies, mandatory steps, zone — is read exactly once, at
  mount. `scanOnce` calls `bringUp` only when the code is absent from
  `runtimes`, and nothing ever compares a re-read spec with `runtime.spec()`.
  Editing a live tenant's declaration therefore does nothing at all. The only
  way it takes effect is withdrawal and re-declaration, which retracts the
  tenant to change a search parameter.

- **What this tenant cares about from another tenant** — its declared
  dependencies. *Nothing syncs undeclared*: a tenant declares which
  dependencies it needs from which upstream, **as ordinary configuration**, and
  the copies stream into its own database because indexing has to be local.
  Declaring it is an apply; keeping it in step is continuous work. Today the
  declaration is read once at mount by `wireDependencies` and never again — so
  a tenant that comes to care about something new cannot say so without being
  retracted — and the keeping-in-step runs on the same scan thread as
  everything else.

A lifecycle with no *change* transition is why every change looks like a
bring-up, and why bring-up throughput became the emergency.

Seen together, the shape is one thing: **the scan thread does four jobs** —
bring tenants up, keep every dependency stream in step, rebuild whatever a
stream delivered, and reconcile the declarations — sequentially, competing with
itself, for every tenant at once. Each of the four is already a sweep in
everything but name. `RunKind.SWEEP` names three of them in its own
documentation: a bring-up, a configuration application, a stream catching up
with its upstream.

## Where it stands

- **Applying: built, proven, unreachable.** `ConfigApplication` in `dbo-sync`,
  proven by `ConfigAppliesAsASweepIT`, constructed nowhere but that test.
- **Fetching: does not exist.** `apply` takes a `List<Declared>` from its
  caller. Nothing produces one outside a test.
- **Withdrawal: does not exist.** `ConfigApplication` applies; nothing in it
  retracts. Tenant declarations need retraction, and today the scan infers it
  from absence.
- **Dependency streams share the loop, and drain to empty.** `syncRound()`
  walks every stream of every tenant on the scan thread and drains each one
  `while (events > 0)` before moving on. They already write runs — `withRuns`
  gives each engine the dependent tenant's own `Runs`, so a parked shadow is a
  card — but nothing claims them, and one upstream with a backlog holds up
  every other stream and every bring-up behind it.
- **A dependency cannot be added to a live tenant.** Same cause as the spec
  change below: `wireDependencies` runs once, at mount.
- **Storage that has not arrived is a wait, not a fault** (step 1). The
  provisioning seam has a word for it, the scan reads it as COMING_UP with a
  reason, and the tenants behind it come up in the same pass.
- **A failed bring-up leaves nothing mounted** (step 1). It used to leave its
  surfaces up, so every later pass died on its own OIDC context and the ledger
  said `cannot add context to list` instead of naming the spec that was wrong.
- **Change does not exist.** A live tenant's spec is never re-read. There is
  no re-mount path, no refusal for a change that cannot be applied hot, and no
  record that a change was seen.
- **The tenant path is the hand-rolled twin.** `scanOnce` lists a directory,
  brings up what is new inline and one at a time, and takes down whatever the
  listing did not contain. In cluster, `KubernetesSecretProvisioner.provision`
  waits up to 60s for the operator's Secret *inside* that loop, inside its
  `synchronized` block.
- **Runs, claims and runners are built and proven**, in-process, over HTTP and
  over the substrate.
- **Placement is not.** Every SCAL promise — durable assignment,
  single-writer, transparent routing — is PLANNED and unproven.

## Sequence

| # | step | status |
|---|---|---|
| 1 | The secret wait comes off the sweep thread, a runtime becomes visible only once wired, and a bring-up that fails leaves nothing mounted | **DONE** 2026-09-04 — `ATenantThatIsNotUpSaysWhyIT` |
| 2 | `ConfigApplication` gets a production caller and a container that can reach it | **READY** — its own defect, and the precondition for the rest |
| 3 | A source seam: read, compute the delta, produce a declared set — directory and ConfigMap first, git and the cloud lane behind the same seam | **READY, needs 2** |
| 4 | Withdrawal becomes part of what a source produces, and what an application applies | **READY, needs 3** |
| 5 | The tenant spec becomes a declared type, applied into the management tenant like any other configuration | **READY, needs 4** |
| 6 | The manager reacts to applied declarations instead of listing a directory: mount per tenant, `scanOnce`'s snapshot diff deleted | **READY, needs 5** |
| 7 | Provisioning claimed by consumers in parallel — the queue is gone because there is no queue | **READY, needs 6** |
| 8 | A changed spec is *noticed*: the re-read declaration compared with the one the runtime holds, and the difference classified onto the run before anything is applied | **READY, needs 6** |
| 9 | Hot changes applied in place; re-wire changes re-mounted without a retraction; cold changes refused by name with what they need | **READY, needs 8** |
| 10 | Apply authored as an administrative act — a task on the surface, through the lane, under its own grant — and automatic application switchable off per scope | **READY, needs 9** |
| 11 | Dependency streams leave the shared loop: one sweep per (tenant, dependency), claimed like any other work, closing when it agrees with its upstream | **READY, needs 7** |
| 12 | A dependency added to or removed from a live tenant is a re-wire change, not a retraction — "what I care about" becomes editable | **READY, needs 9 and 11** |
| 13 | Terminology, shapes, policy and automation ride the same path | **LATER** — the payoff, not the proof |
| 14 | Promises claimed and stories written, with each slice | **with 1–13, never after** |

Critical path: **1** unblocks the consumer. **2 → 6** is the mechanism, and
step 6 is where the second applier dies. **7** is the parallelism, and it costs
almost nothing once 6 has landed. **8 → 10** is the transition the lifecycle
never had, and it is the half that makes the rest worth building — without it
every change is still a retraction. **11** empties the shared loop of its last
job, and **12** is the one a consumer will ask for first, because "tell me when
something I care about changes" is useless if changing what you care about
means being retracted. **13** is the payoff.

## Decisions

**One applier, not two.** The queue is not fixed by making `scanOnce`
concurrent; it is fixed by `scanOnce` ceasing to exist. A second reconciler
that has been made fast is still a second reconciler, and the first one still
has no caller. This is the decision the whole document rests on, and it is why
step 6 deletes code rather than adding it.

**Claims, not threads.** The scan loop's four jobs are not fixed by giving
each one a thread — that is four loops with four backlogs and no way to see
either. Each is already a sweep: one durable run per scope, closing when the
world agrees, checkpointed so the operator's question is about the thing rather
than about the pass. Made claimable, they are read, paced and scaled the same
way, by consumers an operator already knows how to add. A dependency catching
up stops competing with a tenant coming up because neither is a turn in one
loop any more.

**Apply is authored, not triggered.** Work is authored on the surface: a task
posted there, claimed by a consumer, recorded as a run that names what ran it.
That is how the automatic caller and the administrative caller become one path
instead of two, and it is why an administrative apply needs no side door —
authoring the task *is* the API. A second entry point that applies without
authoring would be a mechanism with no record, and the first question anybody
asks after a bad configuration is who applied it and when.

**Applying is its own entitlement.** Changing what a tenant is, is not the same
right as writing records into it, and it must not come free with a tenant
credential. The precedent is supervision: reached through the tenant's own
lane, by a grant given separately and usually not given at all. Configuration
apply takes the same shape — a declared grant, enforced on the lane as well as
at authoring, so an operator's console and an automatic consumer are refused or
admitted by the same rule.

**The run is the preview.** An administrator wants to know what a change would
do before it does it. Rather than a second dry-run path that drifts from the
real one, the classification — what is hot, what re-wires, what is cold and
refused — is computed and recorded on the run *before* anything is applied. An
application that stops there has told the administrator exactly what the real
one would do, in the same words, from the same code.

**A change is classified, never guessed.** A re-read declaration differs from
the live one in exactly three ways, and they need different answers:

- **Hot** — the tenant absorbs it while serving. Search parameters and profiles
  already have their seams (`searchParametersChanged()` with a real reindex,
  `shapesChanged()`), and a reindex is work: it gets a run, because a round
  that quietly spent four minutes is a deployment nobody can account for.
- **Re-wire** — surfaces or dependencies change and the runtime is rebuilt in
  place: additive and idempotent, never a retraction. A tenant that has to stop
  serving to gain a type is a tenant nobody will edit.
- **Cold** — the change cannot be applied to a live tenant at all: a face or
  version change, a PDI toggle, anything that moves the crypto or storage
  layout beneath existing data. These are **refused by name**, saying what they
  would need, and never half-applied. A silently ignored cold change is what we
  have today; a half-applied one would be worse.

The classification is the tenant's own business, not the applier's — which is
the same seam as the decision below, seen from the other side.

**A tenant declaration is configuration, not a special case.** It is a declared
thing from a source, applied to a scope, with an effect. That the effect
happens to be a running tenant rather than a value set is the manager's
business, not the applier's. If tenant specs need a bespoke path, so will the
next declared type, and the shape of that mistake is already in the tree.

**Applying records; it never gates.** A deployment with no management tenant
records nothing today and must keep serving tenants — and the management tenant
is brought up before any store exists to hold a run about it. So the applied
declaration is the schedule and the record; direct bring-up stays the mechanism
underneath, and `manages(Path)` stays synchronous and direct. This is the
existing rule at `recordServing`, kept rather than invented.

**The directory stays as the floor.** Declarations as records cannot bootstrap
the store that holds them. Directory intake is what the management tenant and a
storeless deployment come up on; ConfigMap, git and a cloud lane are sources
above it, never replacements for it.

**Withdrawal is an event, never an inference.** Today anything absent from
`Files.list` is retracted. Under a delta, a withdrawal is something a fetch
produced, and a fetch that failed or read a partial snapshot produces none. The
whole value of the delta model is in that sentence.

**Provision is movable; the mount is not.** The mount is `runtimes.put`,
`sharedServer.createContext`, `wireDependencies` and the service registration:
an in-JVM object graph belonging to the node that will serve the tenant.
Provisioning is idempotent, I/O-bound and node-independent, so it is the half
that can be claimed and eventually moved to a pod. Splitting anywhere else
produces a provisioned tenant nobody mounted.

**Parallelism is consumers, and the manager never grows a pool.** Not at the
end and not as an interim: several consumers claiming apply-and-provision work
is the concurrency. A pool inside the manager would be a second scaling
mechanism with its own knob, invisible to the operator, deleted by step 7
anyway. The bound becomes the one an operator already scales — replicas —
rather than a number compiled into the store, and claims bring lease and lapse,
so a consumer dying mid-apply releases and another takes it. The mount stays
serial per node, which is the correct half to leave serial: milliseconds of
in-process wiring, and single-writer by nature.

**Milestones, not a queue position.** #188 asks for "queued behind N". A
position stops being true the moment claims run in parallel. The declared
milestones of an application — secret, schema, authority, vocabulary, shapes —
let a consumer read where the work actually is, which is what stops the next
consumer picking a timeout constant.

## Traps

**A toolset built, proven and unreachable — this is the fifth.**
`ConfigApplication` passes its own test because a harness *is* the container and
constructs whatever it needs. `new ConfigApplication(` matching nothing in
production sources is the whole signal. Every piece added here answers the two
questions before it is called done: **who constructs this outside a test**, and
**where does its own state live** — a source can be mounted and correct while
the type it writes is registered for no tenant.

**A catching-up dependency starves bring-up.** `syncRound()` drains each
stream `while (events > 0)` before moving to the next, on the thread that also
brings tenants up. One tenant restoring a large terminology dependency
therefore delays every other tenant's bring-up, and the delay is invisible
because it is nobody's failure. This is the second reason the queue in #188
drains more slowly exactly as the deployment grows.

**The 60s secret await was inside the monitor** (fixed in step 1). `KubernetesSecretProvisioner`
polls for the Secret every 250ms for a minute, from `provision`, from
`bringUp`, from `synchronized scanOnce`. Sequential bring-up is the headline;
this is what turns a busy queue into a stalled one. It appears only in cluster
— the dev provisioner creates the database itself and never waits — so numbers
measured on one topology do not describe the other.

**Absence means retraction.** `scanOnce` takes down every runtime missing from
`Files.list`. A ConfigMap that momentarily reads empty, a mount mid-swap, a
partial sync — and live tenants are retracted as "the declaration was
withdrawn". Nothing in the current path distinguishes that from a real
withdrawal.

**The runtime was visible before it was wired** (fixed in step 1).
`runtimes.put` preceded `wireDependencies`. Harmless while bring-up is sequential; concurrently a
dependent resolves the upstream mid-wire and calls `feed()` on it. Fix by
ordering, not by a comment claiming it is safe — `UpstreamNotReady` and the
retry exist for exactly the gap the reorder opens.

**Heavy work inside `computeIfAbsent`.** `zoneHub` does store reads, secret
lookups and `createContext` inside a `ConcurrentHashMap` mapping function.
Invisible sequentially; concurrently it serialises every tenant sharing a zone
inside one map bin, which is precisely the deployment shape that has two dozen
tenants in one zone.

**The R5 validator loads the core package eagerly.** Concurrent applications
multiply it. On a default heap it arrives as `HAPI-2330` with a null message,
three frames above an `OutOfMemoryError` nobody sees — and it will read as
"concurrency broke bring-up".

**Green proves nothing.** `EmbeddedContainerIT`, `TenantOsgiIT` and
`ServerDistIT` are the ratchets, and both in-JVM containers must install what
the distribution installs. A source or an applier reachable in the harness and
absent from the container is this document repeating itself.

## Not doing

- **Not making `scanOnce` concurrent.** That is hardening the applier we intend
  to delete. Step 1 makes it stop *blocking*; it does not make it parallel.
- **Not building tenant→node placement.** All of SCAL is unproven, and claims
  scoped to applying and provisioning do not need it. A claimed mount would.
- **Not moving the mount out of the JVM.**
- **Not shaping this around one consumer's 60s timeout.** The defect is the
  store's; a consumer reading milestones needs no constant.
- **No orchestrator, and no new dependency in `dbo-core` or `dbo-postgres`.**
- **No side door.** Nothing applies configuration without authoring the work and
writing the run — not a console command reaching past the lane, not a private
method the manager calls on itself. An apply with no record is the one thing
this whole document is against.

**Not making a change a retraction.** Withdraw-and-redeclare works today and
is the reason nobody noticed change was missing. It drops the tenant's
surfaces, its lanes and its dependents' streams to alter one field, and it
means every edit is an outage. It stays available as an operator act; it stops
being the mechanism.

**Not deleting the serving sweep.** It answers "what is this deployment doing
  about its tenants" at deployment level, beside the per-tenant runs.

## Verifying

```
./gradlew :core:harness:test
./gradlew :core:harness:promiseProjection
.github/scripts/check-branding.sh
```

A slice is done when a capability was exercised, never when it compiled: apply
a changed configuration from a real source against a real container and read
the run it wrote, rather than asserting that a handler was called.
