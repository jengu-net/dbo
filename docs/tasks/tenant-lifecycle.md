# The tenant lifecycle, and applying to a tenant that is already up

**Status** — all thirteen slices built and proven, and the queue this opened
with is gone. The lifecycle has a change transition now: declarations are
records, what is served comes from what was applied, tenants come up together,
a redeclaration is noticed and classified, a change a tenant can take is
applied to it — including what it streams from another tenant — and a declarer
hands its own content over as one recorded pass. What is left is two things
step 10 deliberately did not build: the automation switch, and a preview that
classifies without applying. The topic stays open on the consumer half.

**Issues** — [#188](https://github.com/jengu-net/dbo/issues/188) (the tenant
queue, which is the symptom this document reframes). The consumer half has
its own issue in the consuming platform's tracker.

**Concepts** —
[processes and work](../arc42-008-crosscutting/processes-and-work/README.md) ·
[change, and who is listening](../arc42-008-crosscutting/change-and-who-is-listening/README.md) ·
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

Everything below is what is true after the twelve slices, oldest finding first.
The bring-up queue that opened this document is gone; most of what follows was
found on the way to it.

- **Applying could not update anything** (found and fixed in step 5). It wrote
  every declaration as a create, so the second application of a changed
  declaration was refused — the identity was already claimed — and became a
  card saying so, with the store left holding the version from before the
  change. Nothing had noticed because its only test applied distinct new
  declarations. Applying is keyed on what the declaration says it is now, read
  from the type's own registration rather than from the caller.
- **Applying: reached** (step 2). `ConfigApplication` had no production caller
  and no citation in the catalogue; bring-up's own vocabulary publication —
  the second hand-rolled applier, whose whole account was a log line — now
  goes through it, and `REQ-DBO-PROC-CONFIG-APPLIES-AS-A-SWEEP` is the promise
  it answers. What a tenant booted with is a pass an operator can read, and a
  definition the face declares that this store cannot hold is a card rather
  than a warning nobody sees.
- **The applier now has a seam for how a thing is applied.** The default is
  still the store write the zone loader needs; a caller with its own meaning —
  a CodeSystem has to arrive at the grain concepts are kept in — passes its
  own, and the pass, tally, cards and closure are identical either way.
- **Fetching: does not exist.** `apply` takes a `List<Declared>` from its
  caller. Nothing produces one outside a test.
- **Withdrawal: declared, never inferred** (step 4). A read says whether it is
  complete; a partial or unreadable one takes nothing away. What is held is the
  applier's to answer and undoing is the applier's to do — one that cannot say
  withdraws nothing, one that cannot undo makes a card. And a read carrying a
  declaration nobody could apply subtracts nothing at all, because the
  unreadable declaration is usually the one that cannot be identified either:
  its own record would be the record taken away, a typo deleting the thing the
  typo was in.
- **Dependency streams have their own loop** (step 11), and run several at a
  time within it, each still drained before it gives way. They already wrote
  runs; what they were missing was not being behind the scan.
- **Before step 11: dependency streams shared the loop, and drained to empty.** `syncRound()`
  walks every stream of every tenant on the scan thread and drains each one
  `while (events > 0)` before moving on. They already write runs — `withRuns`
  gives each engine the dependent tenant's own `Runs`, so a parked shadow is a
  card — but nothing claims them, and one upstream with a backlog holds up
  every other stream and every bring-up behind it.
- **A dependency can be added to and taken from a live tenant** (step 12).
  Declaring one catches up from the upstream's whole history; withdrawing one
  stops delivery and leaves the copies, which are what the tenant answers
  from. Naming an upstream that is not up yet is a wait, never a teardown.
- **Storage that has not arrived is a wait, not a fault** (step 1). The
  provisioning seam has a word for it, the scan reads it as COMING_UP with a
  reason, and the tenants behind it come up in the same pass.
- **A failed bring-up leaves nothing mounted** (step 1). It used to leave its
  surfaces up, so every later pass died on its own OIDC context and the ledger
  said `cannot add context to list` instead of naming the spec that was wrong.
- **What a deployment was told to serve is records** (step 5), in the managing
  tenant, applied from the spec directory by the ordinary source and the
  ordinary applier — so `DirectoryConfigSource` arrived with its caller, as
  step 3 said it would.
- **And what it serves comes from those records** (step 6). A tenant stops
  being served because somebody withdrew it, never because a read went wrong;
  a source that cannot be read leaves the records standing, the tenants
  serving, and says so in the ledger under `source:declarations`.
- **Change is noticed and classified** (step 8), and refused where it cannot
  be had: a serving tenant declared differently is compared with what it was
  built from, every field of the declaration is classified, and a cold change
  — the face it speaks, whether its data is encrypted, the zone it identifies
  in — reaches the ledger by name instead of being ignored. A deployment can
  be asked which of its tenants serve something other than what was declared.
- **And applying can be asked for** (step 10): a door on the managing tenant,
  behind a scope granted separately and usually not granted at all, running the
  same pass the deployment runs on its own and answering with what it did.
  There is no second entry point that applies without leaving a record.
- **And a change it can take is applied to it** (step 9). What a tenant only
  says about itself it takes where it stands; what it is made of is rebuilt in
  place, keeping its database, its lanes and their cursors, recording no
  retraction, and wiring its dependents again. What stops for the length of a
  rebuild is the tenant's HTTP surface — a rebuild is a remount, and it has to
  go down first because the container registers a tenant's services when it
  comes up: mounting over a tenant still up would register a second set and
  leak the first.
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
| 2 | `ConfigApplication` gets a production caller: the face's own vocabulary, applied into each tenant at bring-up as a recorded pass | **DONE** 2026-09-04 — `TenantRuntimeIT#theFacesOwnVocabularyArrivesAsARecordedApplication` |
| 3 | A source seam: read, and say what the read is called, so an unchanged source is a read rather than a re-application. The face's own vocabulary is its first source | **DONE** 2026-09-04 — `ConfigAppliesAsASweepIT`. Directory, ConfigMap, git and lane implementations wait for step 6, where their caller is |
| 5 | The tenant spec becomes a declared type, applied into the management tenant like any other configuration | **DONE** 2026-09-04 — `ADeploymentRecordsWhatItWasToldToServeIT` |
| 4 | Withdrawal: only a read a source calls complete may take anything away, and only an applier that can say what it holds | **DONE** 2026-09-04 — `ADeploymentRecordsWhatItWasToldToServeIT`, `ConfigAppliesAsASweepIT`. Withdrawing a spec leaves the record; retracting the tenant is still the sweep's, until step 6 |
| 6 | The sweep reconciles against what was applied rather than a listing it takes itself; the source is read directly only where there is no managing tenant to hold records | **DONE** 2026-09-04 — `ADeploymentRecordsWhatItWasToldToServeIT#anUnreadableSourceRetractsNothing` |
| 7 | Tenants declared together come up together, bounded by what a node can carry — the queue is gone because there is no queue | **DONE** 2026-09-04 — `SeveralTenantsDeclaredAtOnceComeUpTogetherIT` |
| 8 | A changed spec is *noticed*: the re-read declaration compared with the one the runtime holds, and every field classified hot, rebuild or cold | **DONE** 2026-09-04 — `ATenantDeclaredDifferentlyIsNoticedIT`, `EveryDeclaredFieldIsClassifiedTest` |
| 9 | A change a tenant can take is applied to it: taken where it stands, or rebuilt in place with its dependents wired again | **DONE** 2026-09-04 — `ATenantDeclaredDifferentlyIsNoticedIT`, `SpecDeclaredSyncIT#aDependentKeepsStreamingWhenItsUpstreamIsRebuilt` |
| 10 | Applying can be asked for: a door on the managing tenant, behind a scope of its own, running the same pass and answering with what it did | **DONE** 2026-09-04 — `ADeploymentRecordsWhatItWasToldToServeIT`. The automation switch is not built: see below |
| 11 | Coming up and keeping up stop being one queue: two loops, and streams run several at a time | **DONE** 2026-09-04 — `AStreamKeepsMovingWhileATenantComesUpIT` |
| 12 | What a tenant cares about is editable while it serves: a dependency declared today catches up from the whole history, one withdrawn stops delivering and leaves its copies | **DONE** 2026-09-04 — `SpecDeclaredSyncIT` |
| 13 | A declarer hands a set over and it is applied to a tenant as one recorded pass — value sets, profiles, search parameters, whatever it holds | **DONE** 2026-09-04 — `AZoneHandsOverItsContentIT`. Automation still has no home; see the decision above |
| 14 | Promises claimed and stories written, with each slice | **with 1–13, never after** |

Critical path (as it was set out; all of it is now built): **1** unblocks the consumer. **2 → 6** is the mechanism, and
step 6 is where the second applier dies. **7** is the parallelism, and it costs
almost nothing once 6 has landed. **8 → 10** is the transition the lifecycle
never had, and it is the half that makes the rest worth building — without it
every change is still a retraction. **11** empties the shared loop of its last
job, and **12** is the one a consumer will ask for first, because "tell me when
something I care about changes" is useless if changing what you care about
means being retracted. **13** is the payoff.

## What is left

Two things, both named where they were decided rather than discovered here,
and both now filed.

**Automation has no home** ([#189](https://github.com/jengu-net/dbo/issues/189))
— nothing persists an `Automation`, so switching automatic application off per
scope waits on giving that type a place to live.

**A preview is not a run** ([#190](https://github.com/jengu-net/dbo/issues/190))
— for a tenant declaration the classification happens inside the sweep, so an
authored apply says what it did rather than what it would do. A real preview
needs the classification without the applying, which is reach rather than
logic: `SpecChange.between` is already a pure function of two declarations.

Everything else this document set out to do is built, proven, and cited.

## Decisions

**The face's own vocabulary declares itself incomplete.** It is a real,
knowable set, so calling it complete would be defensible and wrong: a release
that dropped a definition, or a face rolled back, would then withdraw a
tenant's vocabulary. A source claims completeness to license a removal, and
this one has no business licensing that.

**Withdrawal comes after declarations, not before them.** The plan had it
first. It cannot be: deriving a withdrawal needs a source that is honestly
complete, and until the tenant declarations were records the only source was
the face's own vocabulary — which should never be treated as complete, because
a bad read would then retire a tenant's vocabulary. Built first, the withdrawal
path would have shipped with nothing exercising it. Declarations first gives it
a complete source and a meaning that already exists: a withdrawn declaration is
a retraction.

**A source implementation lands with its caller, never before it.** Step 3
built the seam and gave it one source — the face's own vocabulary, which has a
caller today. A directory or ConfigMap reader would have had none until step 6,
and writing it early is precisely the thing this document is about: a toolset
built, proven and reachable by nobody. In cluster the ConfigMap *is* a
directory, so those two are one implementation when their turn comes.

**What a scope last agreed with lives on its run.** Not in a field on the
source: a marker held in memory makes the first pass after every restart a full
re-application, and answers nobody who asks what this tenant is configured
from. On the run it survives the restart and is the answer to that question.

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

**The switch is deferred, and `Automation` is why.** The plan says automatic
application should be switchable off per scope, and dbo has the type for it —
`Automation(process, step, scope, on)`, "declared configuration on the same
chain, not a code path". Nothing persists it and nothing outside
`ExecutorResolution` and its own unit test reads one, so switching applying off
would have meant building storage for a type with no production caller in order
to reach a promise nobody has claimed. That is the shape of work this document
exists to stop. The switch belongs with giving `Automation` a home, which is
its own slice.

**The preview is not the run, yet.** "The classification is computed and
recorded before anything is applied" was written for a dry run. For tenant
declarations the classification happens in the sweep — noticed, classified,
then applied in the same pass — so an authored apply does the thing and answers
with what it did. A real preview needs the classification without the applying,
and nothing asks for one yet.

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

**Parallelism is consumers — and inside one node, the consumers are the
node.** This decision was written imagining a fleet of runner processes
claiming provisioning work, and step 7 found the half of it that was wrong: a
claim has to live in a store, and a run about bringing up a tenant cannot live
in that tenant's store because the tenant does not exist yet. It would live in
the managing tenant, which is optional — so making the fix conditional on it
would have left the deployments that hit the defect without one.

What survives is the substance. A node brings up several tenants at once,
bounded by what it can carry and configurable, and the bound is on the node
because that is what the limit is about: a pool, a schema, a validator's heap.
What was rejected — a second scaling mechanism invisible to the operator —
stays rejected: every bring-up is still a recorded pass in the deployment's own
history, and a tenant that cannot come up is still its own trouble by name.

A consumer fleet outside the JVM stays possible and is still the better answer
for the provisioning half; it needs the provision/mount split and a placement
story, and neither is built. The mount stays serial per node regardless, which
is the correct half to leave serial: milliseconds of in-process wiring, and
single-writer by nature.

**Milestones, not a queue position.** #188 asks for "queued behind N". A
position stops being true the moment claims run in parallel. The declared
milestones of an application — secret, schema, authority, vocabulary, shapes —
let a consumer read where the work actually is, which is what stops the next
consumer picking a timeout constant.

## Traps

**A toolset built, proven and unreachable — this was the fifth.**
`ConfigApplication` passed its own test because a harness *is* the container and
constructs whatever it needs. `new ConfigApplication(` matching nothing in
production sources was the whole signal; it was also uncited, so the catalogue
counted it as nothing at all. Both are closed as of step 2, and the question
stays live for everything added after it. Every piece added here answers the two
questions before it is called done: **who constructs this outside a test**, and
**where does its own state live** — a source can be mounted and correct while
the type it writes is registered for no tenant.

**A catching-up dependency starved bring-up** (fixed in step 11).
`syncRound()` drained each stream `while (events > 0)` before moving to the
next, on the thread that also brought tenants up — so one tenant restoring a
large terminology dependency delayed every other tenant's bring-up, and a
bring-up waiting on somebody else's storage stopped every stream. Both
invisibly, because neither is anybody's failure. Two loops now, and streams
run several at a time.

**The 60s secret await was inside the monitor** (fixed in step 1). `KubernetesSecretProvisioner`
polls for the Secret every 250ms for a minute, from `provision`, from
`bringUp`, from `synchronized scanOnce`. Sequential bring-up is the headline;
this is what turns a busy queue into a stalled one. It appears only in cluster
— the dev provisioner creates the database itself and never waits — so numbers
measured on one topology do not describe the other.

**Absence meant retraction** (fixed in step 6). `scanOnce` took down every
runtime missing from `Files.list`, so a ConfigMap that momentarily read empty,
a mount mid-swap or a partial sync retracted live tenants as "the declaration
was withdrawn". The sweep now reconciles against what was *applied*: a read
that cannot be taken never reaches the records, so absence has a cause
somebody produced. What survives of the old path is the floor — a deployment
with no managing tenant reads its source directly, because nothing can
bootstrap out of a store it has not built yet.

**The runtime was visible before it was wired** (fixed in step 1).
`runtimes.put` preceded `wireDependencies`. Harmless while bring-up is sequential; concurrently a
dependent resolves the upstream mid-wire and calls `feed()` on it. Fix by
ordering, not by a comment claiming it is safe — `UpstreamNotReady` and the
retry exist for exactly the gap the reorder opens.

**Heavy work inside `computeIfAbsent`** (fixed with step 7). `zoneHub` did
store reads, secret lookups and `createContext` inside a `ConcurrentHashMap`
mapping function — invisible sequentially, and with tenants coming up together
it would have serialised every tenant of one zone behind the first, which is
precisely the deployment shape that has two dozen tenants in one zone.

**The R5 validator loads the core package eagerly** — which is why step 7 has
a bound at all, and why the bound is two. Four was the first answer, and the
test proving the step met `OutOfMemoryError` on one tenant of eight, on a JVM
with more heap than a serving node gets. What limits a node is not how many
tenants it can start but how many validators it can hold at once. Concurrent applications multiply it. On a default heap it arrives as `HAPI-2330` with a null message,
three frames above an `OutOfMemoryError` nobody sees — and it will read as
"concurrency broke bring-up".

**A dependent left holding a rebuilt upstream's feed says nothing at all.**
The engines are wired with `upstream.feed()`, which belongs to the runtime
object; rebuild the upstream and a dependent nobody re-wires reads from a pool
that has closed. It does not fail — a stream delivering no events looks exactly
like an upstream with nothing to say, and the test proving it takes four
minutes to go red because all it can do is wait.

**A rebuild can take a tenant down for something that was only a wait.** The
teardown happens before the mount, so every refusal the mount would raise
becomes, on a rebuild, a serving tenant stopped. Adding a dependency on an
upstream that has not come up yet is the case that bites: it is ordinary, it
resolves by itself on the next pass, and before the pre-check it would have
unmounted a tenant that was serving perfectly well. Whatever a rebuild can
check before it tears anything down, it must.

**A test of a concurrency fix can pass for reasons that have nothing to do
with it.** The one for step 11 passed twice while proving nothing: first
because its wait (the suite's 240-second patience) outlived the block it was
supposed to be racing, so the held bring-up gave up and the stream moved
anyway; then because it asked whether the answer body mentioned a url, and a
FHIR search echoes its own query in the bundle's self link — true with zero
results. Both times the fix was real and the test was not. Run the negative:
break the thing deliberately and watch the test go red, or it is decoration.

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
