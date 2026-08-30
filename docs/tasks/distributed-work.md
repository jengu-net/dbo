# Distributed work

**Status** — doctrine decided and written; **#71, #147, #149, #150, #77, #80,
#151, #154 and #155 are closed**: the declaration seam (steps, actions, mandatory-steps incident
classification), introduction over the link, run inputs filling declared slots,
milestones on the checkpoint, and the participant with both halves of reach.
**#79 is closed** and **#91's run-path precondition is met**: the runner drives
a lane it can only reach across a boundary and cannot tell, killing a
participant mid-work loses neither half, and the `Task` a run is rendered as
now has a published profile beside the systems it carries. **#154 is closed**:
a host that reaches the store over HTTP obtains a lane, which is what the
consumer's cloud side was missing and the last thing gating their work lane.
Nothing is left gating the console (#75/#76) but building it. See `Sequence` for the order and
what each remaining step is waiting on.

**Issues** — the participation cluster, formerly under the closed #46.
Open: [#148](https://github.com/jengu-net/dbo/issues/148) (vital signs on the
link — its carrier is delivered) ·
[#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (console).
Closed: [#151](https://github.com/jengu-net/dbo/issues/151) (the edge's work
lane: who advances a claimed run, and edge-originated work as upstream) ·
[#156](https://github.com/jengu-net/dbo/issues/156) (refused is not
unanswered — §7.9) ·
[#157](https://github.com/jengu-net/dbo/issues/157) (replication driven from
outside the container) · [#69](https://github.com/jengu-net/dbo/issues/69) /
[#70](https://github.com/jengu-net/dbo/issues/70) (run record and its
`Task`) · [#72](https://github.com/jengu-net/dbo/issues/72) /
[#78](https://github.com/jengu-net/dbo/issues/78) (executor declaration and
resolution) · [#71](https://github.com/jengu-net/dbo/issues/71)
(declaration seam) · [#77](https://github.com/jengu-net/dbo/issues/77)
(the participant, and reach as an intersection) ·
[#79](https://github.com/jengu-net/dbo/issues/79) (the reference runner:
the lane seam from this side, and DBOS below it) ·
[#147](https://github.com/jengu-net/dbo/issues/147)
(introduction over the link) ·
[#149](https://github.com/jengu-net/dbo/issues/149) (a run names its
inputs) · [#150](https://github.com/jengu-net/dbo/issues/150) (milestones
on the checkpoint) ·
[#154](https://github.com/jengu-net/dbo/issues/154) (a lane for a host that
is not the container) ·
[#80](https://github.com/jengu-net/dbo/issues/80) (the replication toolset) ·
[#155](https://github.com/jengu-net/dbo/issues/155) (who may write the audit
trail — §7.8).

**Concepts** —
[process catalogue](../arc42-008-crosscutting/process-catalogue.md)
(§ "How work reaches whoever does it" is this topic's constitution) ·
[eventing and feeds](../arc42-008-crosscutting/eventing-and-feeds.md) ·
consumer-side ADRs 0057, 0060, 0061, 0062

## What this is

dbo's built-in distributed computing: work travels to wherever it is actually
done — another pod, another VM, another building, a person at a screen — over
**dbo's eventing and nothing else**. One component, the participant/step-runner,
covers every case by changing only what sits inside it:

- **a workstation** — a person at a screen; opening a run is the claim,
  finishing it is the report;
- **an automated runner** — DBOS below for local durability, participation
  above for global truth;
- **the cloud-side edge connector** (consumer-built) — a runner with a
  WebSocket server inside it, its counterpart on the edge in client mode
  talking to the edge's own dbo and DBOS;
- **a scalable step executor** — a k8s Deployment per step in the same
  network, replicas = claimants; local LLM executors are the motivating case.

On the business side the work item is the run, rendered on the FHIR face as a
`Task` assigned to a process step; every `Task` names the objects it is about,
and those travel with the work (*work is the manifest*). The runner reflects
progress back — counts, and the milestone where it is — reports which DBOS
instance carried it, **introduces steps the catalogue has not declared** (the
dynamic half of process building), and its link carries **extensible
metrics** — health, throughput — beside the presence the cursor already
proves (#148).

## Where it stands

- **Decided and written**: the whole participation doctrine, in
  process-catalogue.md — pull-never-push, claim as conditional write with
  deadline, checkpoint-not-heartbeat, released-is-not-done, two layers owning
  different failures, executor declarations with derived presence, scale by
  adding claimants.
- **The record and its face**: runs are records in the tenant's store (#69),
  rendered by the face as `Task` and never spelled by the engine (#70).
- **The catalogue**: `StepDeclaration` in `dbo-core` — id, version, the
  storage domains it reads and writes, opaque shape references, the actions
  it contains, its named input slots, its milestone order, and whether
  anybody may override it. `Steps` is installed-not-listed, and
  `Introductions.composedWith()` adds the second door: steps a linked
  participant brought, recorded in the tenant's store with the introducer as
  provenance. One id, one *definition* — identical declarations
  co-introduce, so a fleet of replicas is not a collision. The tenant spec's
  `mandatorySteps` reads that composed view and **classifies incidents**
  (`StepIncidents`, `stepIncidents()`); it never gates.
- **Reporting**: reports land through the step's declared actions, checked
  in `Runs` itself so the lane, the console and whatever comes next meet one
  rule. `close` and `reopen` are narrowed where declared, releasing never
  is, and `Runs.reopen` makes a closed run claimable again with its reason
  on the record. Progress rides the checkpoint: counts always, and the
  declared milestone when a step names one — position derived by the store,
  `businessStatus` saying "validated, 2 of 3".
- **Reach**: what a participant may claim is the intersection of what its
  credential covers and what the step admits. The step's half is at the
  primitive — the baseline always may, anything more local only where the
  step opened itself to that class (ADR 0059) — and the credential's half is
  `Lane.Entitlement`, provisioned with the lane, narrowing what `poll` offers
  and refusing what `claim` may take. `everything()` is a host saying it is
  the tenant; there is no implicit unrestricted.
- **Work arrives whole**: a run fills the slots its step declares, fixed at
  creation and refused by name in both directions; `Task.input` renders them
  in declaration order; the in-process lane resolves them from the host's
  store for the identity that claimed the run, and no verb takes a
  reference.
- **The runner** (`core:dbo-runner`): an embeddable OSGi bundle — install it
  into the existing container and the activator whiteboard-tracks
  `StepService`s from any bundle and `Lane`s from the host; outside OSGi,
  construct `StepRunner` directly. It declares candidacy, introduces what
  its service brings, and publishes vitals on the declaration record
  (#148's carrier).
- **The lane is a seam, and now says so**: the runner drives one it can only
  reach across a boundary — every verb handed to another thread and answered
  there — and the work lands identically, with `milestone` and `introduce`
  arriving as themselves rather than folded into their neighbours, and a
  refusal crossing as a refusal instead of as an empty queue
  (`ARemoteLaneIsIndistinguishableIT`). `ALaneStaysTransportShapedTest` keeps
  it that way: no verb may take or return a live handle, and none may be
  defaulted — both rules the interface's javadoc had asked for in prose, which
  does not fail a build. Deliberately no wire format here; that is the
  consumer's, and a second encoding is the drift the trap below warns about.
- **Promises**: the whole PROC area now lives in the catalogue (migrated
  2026-08-27 from hand-written prose — the old "## PROC — process catalogue
  & map" section is gone). `DISTRIBUTED_WORK` carries the twelve promises
  built this slice; the pre-existing PROC ground — the runner, the
  declaration, reporting, the run record, resolution, the appliance lane,
  the content-under-work rule, the network map and the trace join — landed
  as further features, 42 promises PROVEN against real tests and 5 honestly
  `PLANNED` with a `TODO` on the constant naming what a proof would have to
  show (`PROC_CATALOGUE_IN_STORE`, `PROC_DOMAIN_CODE_FILTER`,
  `PROC_RUN_HAS_A_RECORD`, `PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY`,
  `PROC_NETWORK_MAP`). `PROC_RUN_SAYS_WHO_HOLDS_IT` left that list on
  2026-08-27: `RunsAreRecordsIT` already proved the holder follows the
  failure class and that `holding(PERSON)` is a store query, and now cites
  it. PROC is the first area to leave the SHAPE/PDI pilot behind entirely.
- **Open, in dependency order**: see `Sequence` below — it carries the order,
  what each step waits on and who owns the wait, so it is not repeated here.
- **Replication is drivable from outside the container**: the tenant serves
  the seven verbs at `/t/{code}/replication` and a host holds `HttpLanes` over
  them, every verb landing on the real in-process `Lanes`. The lane's own
  types are registered per tenant, which is where its cursors and placements
  live.
- **The trail replicates**: an appliance's audit entries reach its peer as
  that appliance recorded them — original actor, original time, the appliance
  named — through the audit refusal's one admission (§7.8), and the arrival
  writes no second trail. Bounded by the work like everything else on the
  lane, and effectively-once by a claim on the source's own identity.
- **Consumer's half, later**: the WebSocket lane (socket, framing, handshake,
  tenant auth) is the platform's per ADR 0062 — now driven from their side by
  [platform#917](https://github.com/jengu-net/jengu-platform/issues/917)
  (`edge-appliance-on-dbo.md`); the k8s per-step Deployment
  packaging likewise. dbo owes the store-level toolset (#80) and nothing
  transport-shaped.

## Sequence

Ordered by what must be true before the next thing can start. A step is
**DONE** only when a capability was verified — each one below names the test
that verifies it, and the `Verifying` command at the foot runs them.

| # | Step | Status |
|---|---|---|
| 1 | **A run has a record, and the face renders it** ([#69](https://github.com/jengu-net/dbo/issues/69) / [#70](https://github.com/jengu-net/dbo/issues/70)) — runs are records in the tenant's store, spelled as `Task` by the face and never by the engine. | **DONE** 2026-08-20 — `RunsAreRecordsIT`, `RunsRenderIT` |
| 2 | **An executor declares itself, and resolution walks declarations** ([#72](https://github.com/jengu-net/dbo/issues/72) / [#78](https://github.com/jengu-net/dbo/issues/78)) — deterministic, layered, overridable only where a step says so. | **DONE** 2026-08-20 — `ExecutorIsRecordedIT`, `ExecutorsDeclareThemselvesIT` |
| 3 | **The declaration seam** ([#71](https://github.com/jengu-net/dbo/issues/71)) — `StepDeclaration` in `dbo-core`: domains, shapes, actions, slots, milestones, overridability; `mandatorySteps` classifies incidents and never gates. | **DONE** 2026-08-27 — `StepsAreDeclaredIT`, `MandatoryStepsClassifyIncidentsIT`, `ReportsGoThroughDeclaredActionsIT` |
| 4 | **A run names its inputs** ([#149](https://github.com/jengu-net/dbo/issues/149)) — slots fixed at creation, refused by name in both directions, rendered in declaration order. | **DONE** 2026-08-27 — `RunNamesItsInputsIT` |
| 5 | **Milestones on the checkpoint** ([#150](https://github.com/jengu-net/dbo/issues/150)) — a long-running step says where it is; position derived by the store. | **DONE** 2026-08-27 — `MilestonesOnTheCheckpointIT` |
| 6 | **Introduction over the link** ([#147](https://github.com/jengu-net/dbo/issues/147)) — the second door into the catalogue; identical declarations co-introduce, so a fleet is not a collision. | **DONE** 2026-08-27 — `StepsArriveByIntroductionIT` |
| 7 | **The participant, both halves of reach** ([#77](https://github.com/jengu-net/dbo/issues/77)) — pull, claim, report; and what it may claim is the intersection of what its credential covers and what the step admits. | **DONE** 2026-08-27 — `ParticipantsPullAndClaimIT`, `ClaimIsTheIntersectionIT` |
| 8 | **The run vocabulary, discoverable** — [#91](https://github.com/jengu-net/dbo/issues/91)'s precondition: the systems a rendered run carries, and the shape they ride on, published as definitions the same tenant serves. | **DONE** 2026-08-28 — the vocabulary was already published, fetchable and split; the `Task` a run is rendered as now has a profile beside it (`run-as-task`), naming the systems it carries and fixing what the renderer fixes. `VocabularyIsDiscoverableIT` proves both. Nothing serves runs over HTTP yet, which is exactly why the shape was worth settling now |
| 9 | **The reference runner: transport first, DBOS below** ([#79](https://github.com/jengu-net/dbo/issues/79)) — the participation link exercised end to end. | **DONE** 2026-08-27 — the seam holds from this side (`ARemoteLaneIsIndistinguishableIT`, kept that way by `ALaneStaysTransportShapedTest`), and both layers now hold at once when a participant dies mid-work: the run is owed again above and the checkpointed half is not redone below (`DbosBelowResumesItsOwnHalfFinishedWorkIT`). The wire itself stays the consumer's (ADR 0062) |
| 10 | **A host that is not the container holds a lane** ([#154](https://github.com/jengu-net/dbo/issues/154)) — the tenant serves the participation verbs on its own private surface; a host reaches them and the runner cannot tell. | **DONE** 2026-08-29 — `ALaneOverHttpIsIndistinguishableIT` (a real runner, a real tenant, a real token), `LaneWireCarriesTheRecordAsDeclaredTest` |
| 11 | **Vital signs on the link** ([#148](https://github.com/jengu-net/dbo/issues/148)) — what rides the carrier, and a presence display that does not page about a healthy idle fleet. | **NEXT** — its carrier is delivered; the runner already publishes vitals on the declaration record |
| 12 | **The replication toolset** ([#80](https://github.com/jengu-net/dbo/issues/80)) — moving the work and the data it names between two appliances. | **DONE** 2026-08-29 — the batch, the idempotent-and-reorder-safe apply, the epoch, echoed markers, mirrored filing, work-driven expiry and the process allowlist (`TwoAppliancesOneTenantIT`), and now the trail: an appliance's entries arrive with the actor, the time and the appliance that recorded them, and the arrival writes no second trail (`AuditReplicatesAsRecordedIT`, §7.8, #155) |
| 13 | **The edge's work lane** ([#151](https://github.com/jengu-net/dbo/issues/151)) — claim advancement across the lane, edge-originated work as upstream. | **DONE** 2026-08-30 — the side that authored a run is the side that advances it, and an appliance offers only what it authored. Both enforced by the store rather than shared as a convention between connectors (`TwoAppliancesOneTenantIT`). The second was a live defect: a mirror travelled back and deepened a key every round |
| 14 | **Replication driven from outside the container** ([#157](https://github.com/jengu-net/dbo/issues/157)) — the tenant serves the seven verbs; a host holds `HttpLanes` over them. | **DONE** 2026-08-30 — `LanesHandler` at `/t/{code}/replication`, `HttpLanes`, and the lane's own types registered per tenant, which was the other half of "no production wiring" (`ReplicationDrivenOverHttpIT`) |
| 15 | **Refused is not unanswered** ([#156](https://github.com/jengu-net/dbo/issues/156)) — a caller can tell a decision about itself from a store that never spoke. | **DONE** 2026-08-30 — `StoreUnreachableException` and the 4xx/5xx line, on both surfaces (`ALaneOverHttpIsIndistinguishableIT`) |
| 16 | **The console** ([#75](https://github.com/jengu-net/dbo/issues/75) / [#76](https://github.com/jengu-net/dbo/issues/76)) — describing the catalogue and the runs, with a tenant context and an identity when it acts. | **MOSTLY DONE** 2026-08-30 — more was already built than this row said: the run half (`dbo-run:list`, `dbo-run:describe`) and #76's session (`dbo:login`, `dbo:context`) were there, and the catalogue half landed now (`dbo-process:list`, `dbo-process:describe`, including which executor would run a step here and why that one). **Left**: the executor half of `describe` is untested — it needs a store, so it needs the harness — and `PROC_NETWORK_MAP` stays `PLANNED` until the cross-node half exists |

**The critical path is spent**, and 16 is most of the way through as well: the console reads runs over HTTP, and the shape
what remains of the console is coverage rather than capability. Step 11
(#148) hangs off 9 and is independent of everything else; its carrier is
decided — the declaration record, where the runner already puts it.

## Decisions

**The runner must not have access to the tenant's dbo.** Not a narrowed
store handle — none. Its whole world is the `Lane` interface: poll, claim,
checkpoint, report, declare, and one read of an object the work names. The
host implements it in-process and keeps the store on its own side of the
line; a remote lane implements the same interface over its transport, and
the runner cannot tell — a verb only the in-process side could serve does
not belong on the interface.

**The runner is stateless over tenants.** Tenants arrive as lanes; the
runner holds only the task in hand and the documents the task names. Its
counters are soft accounting — published as vitals, reconstructible from
nothing, lost without loss.

**The step declaration is the input API, and the verb's signature is the
security boundary** (review, 2026-08-27). What a step consumes is declared
centrally — named slots with shape references, which is #71's "shapes it
consumes" promoted to the API; a run's inputs fill those slots, validated at
creation (#149); a runner *joins* a step and has thereby agreed to the API,
because there is nothing else it can receive. The runner-facing read is
`inputs(Run)` — no verb takes a reference, so a runner **cannot ask** for
data, relevant or not, and a lane refuses runs the asking identity has not
claimed. This replaced an earlier `named(reference)` read that enforced
"only what the work names" by convention — and a convention is not a
boundary.

Slots are therefore the only official way documents reach a distributed
runner, rather than an enhancement over some other way — there is no other
way, by construction.

**The catalogue is built up from linked steps** (review direction 2026-08-27,
pinned in #71's grooming the same day): rather than porting the platform's
catalogue wholesale — the drift the #71 trap warns about — the catalogue
*emerges*: installed modules contribute as they always did, and linked
participants introduce theirs (#147). Consistency is a **declared list of
mandatory steps**: the tenant spec's `mandatorySteps`, beside `types`, and
configuration owns it. It **classifies incidents; it never gates** (Alan's
correction to the first cut, which refused bring-up): the system is
asynchronous by design, so a missing step executor buffers runs on the queue
rather than taking the tenant offline — the list decides whether that absence
is an incident, named on `stepIncidents()` and in the log, re-evaluated every
scan as contributions come and go (`StepIncidents`). Only the face contract
genuinely gates, because a missing face capability breaks serving itself.
Every other step is non-critical by construction: free to appear with its
participant and disappear with it, never load-bearing for a critical flow.
This softened #71's sequencing — the seam and the classification landed
before the platform catalogue's migration, because nothing is ported: the
platform's steps arrive as introductions when the platform connects, and
`platform-process-api` retires by attrition. #147 inherits this frame.

**Pull, never push** (ADR 0060). dbo holding a client per external system is
rejected; participants are behind NAT, on edges, offline for weekends.
Pulling makes an offline participant a lagging cursor rather than an outage —
and it is what lets one component run inside the cloud or on a separate
machine with no change.

**The link is the change feed — the fifth use, not a sixth mechanism.** Named
consumer, cursor, ack. Backlog and lag per participant come free, which is
also the automation backlog.

**A claim is a conditional write with a deadline; no lease service.** Two
claimants racing produce one winner and one version conflict. The deadline is
extended by checkpoint, never heartbeat — a tick proves alive, and what the
deadline protects against is alive-and-getting-nowhere. The claim is also the
dedup point, because delivery is at-least-once. (The same argument later
collapsed the reshape hand-back lease — it holds twice.)

**Manual is the baseline; automation is an attachment.** A step nobody has
automated is held by a human; automating it changes the holder and nothing
else. This is why one component is both the workstation and the runner.

**No orchestrator is named in the contract.** DBOS sits *below* the
participant as one local-durability choice among several (a server has DBOS,
an edge may have nothing, a workstation has a person). dbo-core never names
it — and neither does `dbo-runner`: the reference local executor lives in the
test sources, on the far side of `StepService`, because a runner that compiled
against an orchestrator would be naming one. What that buys is now asserted
rather than asserted-about: kill a participant mid-work and the run is owed
again above while the checkpointed half stays done below, each half proven
separately, because a store that re-ran the finished step would pass a test
that only looked at the run.

**Work is the manifest.** A run names the versions it produced, so the far
side asks for exactly what it is missing instead of comparing stores. This is
how "the Task's related objects travel automatically" is honest: the run
enumerates, the toolset (#80) moves.

**Scale by adding claimants, never by relaxing the claim.** The k8s answer is
therefore packaging, not mechanism: a Deployment per step, replicas up. A
partition hint is the second lever and is not built until one step measurably
outgrows competition.

**Every uniqueness rule must survive a fleet** (Alan's correction, 2026-08-27).
Because scaling *is* parallel runners — and DBOS runs parallel consumers — a
replica set comes up racing itself, and a rule written to protect correctness
will forbid normal operation unless it is aimed at the right thing. So a
collision rule guards the **definition, not the door**: the same actor
replaces, a different actor with identical content co-exists without refusal,
and only differing content collides. Lost write races are re-read and judged
rather than thrown. Ask it of any new rule here: *what does a fleet of
identical replicas do to this path?*

**Presence is derived, metrics annotate** (#148). A self-reported "healthy"
from a stuck component is exactly the lie cursor-derived presence exists to
catch — so metrics ride the link and inform, and presence stays computed.

**A host that is not the container gets the lane over the private surface,
not a bundle inside the store** (decided 2026-08-29, #154). Two shapes were
visible. The consumer could ship a bundle into dbo's container that registers
the lane and holds their socket — which matches the activator's whiteboard
literally, and puts their socket and its TLS termination *inside* the store's
membrane rather than at it, against everything else about how that surface is
operated. Or the tenant could serve the participation verbs on the private
surface the consumer already reaches it on, and a host hold a lane over them.
The second, because it changes nothing about who terminates what and it keeps
run semantics in one place: every verb still lands on a real in-process lane
the tenant built, and the alternative worry — the cloud faking a lane, or
reaching into the store some other way — was exactly the thing owning this
contract here exists to prevent. This is not the consumer's edge socket
getting a second encoding: that is a different link between different
parties, and this one is dbo's own private surface, which has always had its
own wire.

**The surface offers the lane and nothing wider.** The temptation on a
store-side transport is to widen while the file is open — a subset query, a
reference read, a freshness flag. Nothing here takes a reference or returns a
handle, because a widened primitive is available to every caller with the
scope, for ever. The handler is a router onto `Lane`, and the test that keeps
the interface transport-shaped keeps this honest for free.

**The entitlement is the credential's, and the executor identity is bounded
with it** (#154). Reach could have arrived as a parameter on the request, and
that is how a surface ends up trusting its caller about what the caller may
do. It comes from the token: the bare participation scope says the holder *is*
the tenant, a suffixed one names the steps it covers, and a credential with
neither reaches no lane at all rather than an empty one. The second half took
a pass to see — the executor identity is what a claim is recorded under and
what the input read is checked against, so a bounded credential free to spell
any name could read the inputs of runs it never claimed. A bounded credential
works only as itself; a tenant-wide one may name any participant, which is
what lets a cloud serve a lane on behalf of an appliance it has already
authenticated by other means.

That last case needed one more thing, and it is **narrowing, never
substitution**. A host relaying for an appliance holds a tenant-wide
credential, so the lane it gets would otherwise be unrestricted — and the
reach that belongs on it is the appliance's, decided when its identity was
issued. So the asker may state what to bound the lane to, and the surface
**intersects** that with what the credential covers. Safe in the only
direction that matters: a host could always have asked for everything it
holds, and nothing it does not hold can be asked into existence. The
alternative — narrowing on the calling side — is the arrangement where the
only thing between a broad credential and the tenant's work is a caller
remembering to do it.

**A refusal and a store that did not answer are different exceptions**
(decided 2026-08-30, #156, recorded as §7.9). They used to be one, and they
need opposite recoveries: stop asking, or ask again. What made it worth a
decision rather than a note is the second-order cost — a participant that
backs off while holding a claim keeps it only until the deadline, and then the
tenant's own housekeeping releases the run and somebody else takes it. So the
confusion does not merely pause a bench; it moves work that was never in
trouble.

One named type rather than a marker interface or a code on the existing
exception: a marker admits more than one transient type later, which is room
to disagree rather than room to grow, and a code keeps callers writing
conditionals where a `catch` would do. The line falls where HTTP already draws
it — 4xx settled, 5xx unanswered — which puts the ambiguous cases right
without the handler being asked to classify its own faults. A pool exhausted
under load is a 500 and is exactly the outage this survives.

**Replication is reached the way participation is** (decided 2026-08-30,
#157). The same asymmetry #154 named, one layer up and pointing the same way:
declarations flow cloud → appliance, so the cloud must PRODUCE outbound
batches, and the cloud is the side whose dbo is a separate deployment.
Pull-not-push does not move it — whoever pulls, the cloud still builds the
batch. So the tenant serves the seven verbs on its private surface and a host
holds `HttpLanes` over them, every verb landing on the real in-process
`Lanes`. One client serves both appliance shapes, because an appliance already
reaches its own store this way for everything else and a connector written
once is worth more than one saved round trip.

**Replication is a whole-tenant act, so the credential is the tenant's.** A
batch carries whatever the travelling work names across every process the
caller lists; there is no version of it bounded to one step. A step-bounded
participation credential is refused outright rather than served a smaller
batch — which would be indistinguishable, from the far side, from a lane that
had caught up.

**"No production wiring" was two things, and the API only showed one.** #157
named the missing constructor and the missing surface. Underneath, `LaneModel`
and `PlacementModel` were registered for no tenant at all, so the surface came
up and the first verb died on a type nobody had declared. A lane's own
bookkeeping is the tenant's records, and now it is registered with the rest.

**A run is advanced only where it was authored, and an appliance offers only
what it authored** (decided 2026-08-30, #151). The two questions #79 left open,
answered together because they are one rule seen from two ends. A mirror is a
read-only account: readable, countable, comparable, and not claimable — because
the lane's latency means "the deadline passed" and "the checkpoint is in flight"
can both be true, and a peer acting on the first has the work done twice. The
cost is stated rather than hidden: a bench that dies holding its own work keeps
it until it returns, and moving it is an operator's act rather than a clock's
inference.

The second half was not a decision at all when we looked: mirrors *were*
travelling back, and each round trip made a new record at the far side with one
more prefix — `cloud@`, then `edge@cloud@` — unbounded, in code closed the day
before. Found by asking what the rule implied and testing it rather than by
reading. The rule is the store's now, enforced at run creation and at every
advance, rather than a convention two connectors would have had to keep
correctly for ever.

**The audit trail's one admission is a named port, not an authority and not a
hole** (decided 2026-08-29, #155, recorded as §7.8). Direct writes to the audit
type are refused for everybody, and the replication lane needed through. A
caller authority would have made a handling vocabulary into an authorization
one — and the append-only shield is written in that vocabulary, so the line it
guards would have blurred exactly where it must be blunt. Writing beneath
policy was what the lane already did and would have been the smallest change;
it bypasses every policy rather than the one in the way, which is a much
broader position than the argument supports. So: one method, on the receiving
store, carrying only the source's own facts, and the refusal names it — an
admission a reader cannot find from the refusal is one somebody reinvents
beside it.

**The receipt and the record are different facts, and both are kept.** The
consequence of keeping the receiving store policy-wrapped is that ordinary
replicated content is audited on arrival. That is not a leak in the design, it
is the design: the edge's entry says who did the work, the cloud's says it
received a copy. Only the trail itself is exempt, because a copy of an event is
not an event — and a store that audited its own replication would grow one
entry per entry, forever.

**The trail travels bounded by the work, like everything else on the lane.**
An entry already joins to the run it was part of, so "the trail of what
travelled" is a query rather than a second mechanism, and the cloud gets the
edge's account of the work it is being told about instead of the edge's whole
history. Entries that arrived from elsewhere carry the appliance that recorded
them and do not go back out, or two appliances hand each other the same entry
forever, each finding it new by a claim it never made.

**A step id is opaque and globally stable, fixed with the record, not the
catalogue** — which is what makes #147 (a participant introducing a step)
an addition rather than a migration: a run could always name a step that had
no declaration yet.

## Traps

**A report writes the state it was handed, so reporting twice from the run as
claimed erases the first report.** `Runs.update` re-reads the stored object for
its version — so the write never conflicts — but builds the payload from the
`Run` the caller passed. The runner held the run as claimed across the whole of
`perform`, so a step that named a milestone and then checkpointed lost the
milestone, and a step that named one and then failed released without it —
which is precisely who needed it, since #150's promise is that the next taker
resumes from a fact. Fixed by threading each report's answer into the next
(`StepRunner.perform`). The in-process tests never saw it because they thread
the returned run by hand, the way a caller reading the signature naturally
would; it took driving the runner over a boundary to notice the runner itself
did not. Worth knowing more generally: `update` looks like optimistic
concurrency and is not — it always writes at the current version, so a stale
caller silently wins.

**A step id is three parts, and a run's process is two of them.** A service
that brings its own declaration (#147) builds a `StepId` from
`<module>.<process>.<step>`; a run records `process` and `step` separately and
the runner looks a service up by joining them. Those agree only when the run's
process is exactly `module.process`. A four-part id throws, and `declare`
catches it into a warning — so the participant keeps working, the catalogue
never learns the step, and nothing is red. The swallow is deliberate (one bad
tenant must not kill a runner) but it is why this cost an afternoon.

**One wait cannot serve a slow answer and a dead one.** The container test
gave bring-up 180 seconds and failed twice on CI with the tenant still
`coming-up` — a slow runner, told as a red. The instinct is a bigger number,
and a bigger number alone makes the *other* case worse: a tenant that reports
`failed` will never come up, so waiting is only a slower way to say so. Now
the wait has room (seven minutes) and breaks the moment the container's own
state says `failed`. That distinction only became available once the failure
started reporting what it saw — which is the argument for diagnostics before
fixes, twice over.

**The console's imports are hand-written and optional, so a new one takes
every command down.** The commands bundle lands in `deploy/` before
`dbo-console:up` installs the dbo set, so its dbo imports are declared
`resolution:=optional` — and the list is hand-written, with a trailing `*`
that picks up anything unlisted as MANDATORY. Adding the catalogue commands
reached into two new packages, and unlisted they would have left the bundle
unresolved at startup, contributing **no commands at all** — including the one
that installs what it needs. The failure names a package rather than a
command, so it reads as the console being broken. Every dbo package the bundle
touches has to be on that list.

**A new package is a new export, and only a container says so.** Moving the
record codec into `dbo-core` so both surfaces could share one encoder left
`cloud.jengu.dbo.core.wire` out of a hand-written `Export-Package`, and
`dbo-sync` then failed to resolve. It compiled, it published, and the two
in-JVM container tests caught it before CI did — which is exactly what they
are for, and the reason both must install what the distribution installs.

**Two of the four write paths silently dropped the replay fields.**
`putIfAbsent` and `putConditional` rebuilt the request from
typeName/id/expectedVersion/payload — every field a `PutRequest` had the day
they were written — and so dropped `recordedVersion`, `recordedAt`,
`restoring` and `shape` as each was added afterwards. An identity-keyed create
is the FIRST arrival of a replicated record, so what they dropped was exactly
the source's version and the source's time on the one write where those are
the entire point. Found by an assertion about a replicated entry's timestamp,
not by anything failing before. The general shape is worth carrying: a record
grows a field, and the code that *rebuilds* it rather than passing it through
keeps working and keeps lying.

**A replayed moment needs a replayed version beside it.** The store keeps the
source's time only for a write that also says which version it is replaying —
`carriesRecordedHistory()` requires both — so a replay carrying a time and no
version quietly becomes the arrival time again. Which is the failure #155's
correction had already fixed once on another path, arriving a second time
through a different door.

**A lane's own bookkeeping lands in the feed the lane reads.** `Lane` and
`Placement` are registered in `WorkModel.DOMAIN`, and `outbound` reads that
domain's change feed — so every `sent()` writes an event into its own input.
The filter skips it, but the cursor still advances, and the consequence is
that **a lane's cursor never stops moving**: there is no quiescent state, and
a caught-up lane is indistinguishable from a draining one by cursor movement
alone. Nor does an empty batch mean caught up — a stretch of non-travelling
housekeeping produces empty batches while the lane is genuinely still
draining. A connector must therefore measure "caught up" as *nothing has
arrived for a few rounds*, which is what `anAbsentPeerConverges` does. Note
this is the mirror of the participant trap below: there a caught-up cursor
looks stalled, here a caught-up lane looks busy. Whether the bookkeeping
belongs in its own domain is undecided — moving it touches erasure-by-drop
and backup coverage.

**A differential follows the snapshot's element order, and Task's is not the
order you would guess.** A profile whose elements are listed out of order is
refused with "no match found" naming the element, which reads like the element
does not exist rather than like it is in the wrong place. `Task.businessStatus`
precedes `Task.intent`; alphabetical and logical orderings both get this wrong.
Caught publishing the run-as-task profile, and worth knowing because the
publish path logs a refusal as a warning and carries on — a definition that
failed to publish looks exactly like one nobody asked for.

**A run speaks its step name bare; the catalogue speaks it fully.** A run
records `process` and `step` as separate fields and `poll` filters on the
bare step, while a service declares `<module>.<process>.<step>`. The runner
translates at the boundary — found by the first integration test, which
polled a perfectly good run and matched nothing.

**A caught-up participant's cursor does not move either.** Silence with
nothing waiting is not absence; only silence with work waiting is. Any
presence display or alert built on cursor movement must carry this
distinction or it will page somebody about a healthy idle fleet.

**A racing create conflicts on the IDENTITY, not the version.** Idempotent
writes keyed by an identifier throw `IdentityConflictException` when two
creates race for one claim, and `VersionConflictException` only when a
*replace* races. Code that catches one and not the other passes every serial
test and fails the moment a replica set comes up together — which is exactly
how the introduction path was found to be wrong. Both are the expected case:
re-read and judge what won.

**A lane wired to the content feed looks exactly like a lane with no work.**
Runs and declarations are work-domain records, and a tenant's own change feed
is its *content* domain. The first wiring of the participation surface passed
that feed to the lane, and everything answered: the handshake worked, the
token was accepted, `poll` returned — an empty list, for ever, because it was
reading a stream runs never appear in. Caught only because the test drove a
real runner to a real outcome instead of asserting that the surface answered.
This is "booting is not serving" wearing the one disguise that survives a
green build: a working surface over the wrong stream.

**An `ensure` that only compares the secret cannot add a scope.** The
tenant's bootstrap credential gained the participation scope, and every
existing tenant kept its old one — because ensuring a client returned early
when the secret still matched. The record then denied a surface with no way
for anyone to see it had not been granted. Ensuring means saying what the
record should be, so it compares the scopes too. Worth carrying beyond this
case: any idempotent "ensure" whose guard is narrower than what it writes
will silently keep yesterday's version of the rest.

**Two catalogues mid-migration is how they drift.** The original reason #71
followed platform#851. Resolved by the emergent-catalogue decision above:
nothing is ported, so the seam landed early without creating a second copy —
but the trap still governs anyone tempted to *translate* the platform's enums
across wholesale. They arrive as introductions, or they wait.

## Not doing

**The socket.** Framing, handshake, tenant authentication of the edge lane
are the consumer's (ADR 0062). dbo builds the store-level toolset the lane
uses, and the same runner embeds on either end. The participation surface
(#154) is not an exception to this: it is the tenant's own private surface,
between a host and the store it is serving from, and it carries nothing
between two appliances.

**Partitioning.** Competition is fine at small N; a partition hint belongs on
the run only once one step has measurably outgrown claim-racing.

**Trusting introduced steps.** A step introduced over the link (#147) grants
its introducer nothing — what it may take stays the intersection of its
scopes and what the step admits, like every declaration.

## Verifying

```bash
./gradlew :core:dbo-work:test :core:harness:test --tests '*ParticipantsPullAndClaimIT' --tests '*ExecutorIsRecordedIT' --tests '*RunsRenderIT' --tests '*StepsAreDeclaredIT' --tests '*MandatoryStepsClassifyIncidentsIT' --tests '*StepRunnerIT' --tests '*ReportsGoThroughDeclaredActionsIT' --tests '*RunNamesItsInputsIT' --tests '*MilestonesOnTheCheckpointIT' --tests '*StepsArriveByIntroductionIT' --tests '*ClaimIsTheIntersectionIT' --tests '*ARemoteLaneIsIndistinguishableIT' --tests '*ALaneStaysTransportShapedTest' --tests '*DbosBelowResumesItsOwnHalfFinishedWorkIT' --tests '*ALaneOverHttpIsIndistinguishableIT' --tests '*TwoAppliancesOneTenantIT' --tests '*AuditReplicatesAsRecordedIT' --tests '*ReplicationDrivenOverHttpIT'
./gradlew :core:dbo-runner:test
```
(The link scenarios get their ITs with the transport exercise in #79.)
