# Distributed work

**Status** — doctrine decided and written; **#71, #147, #149, #150 and #77 are
closed**: the declaration seam (steps, actions, mandatory-steps incident
classification), introduction over the link, run inputs filling declared slots,
milestones on the checkpoint, and the participant with both halves of reach.
**#79 is closed**: the runner drives a lane it can only reach across a
boundary and cannot tell, and killing a participant mid-work loses neither
half — the run is owed again above, the checkpointed work is not redone below.
What gates what is left is one precondition — a profile for the rendered
`Task`, the half of #91 the published run vocabulary did not cover — and behind
it the console (#75/#76). It does not gate the replication toolset (#80): a
lane is not a FHIR client. See `Sequence` for the order and what each is
waiting on.

**Issues** — the participation cluster, formerly under the closed #46.
Open: [#80](https://github.com/jengu-net/dbo/issues/80) (replication toolset —
most of it built; see `Sequence`) ·
[#148](https://github.com/jengu-net/dbo/issues/148) (vital signs on the
link — its carrier is delivered) ·
[#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (console).
[#151](https://github.com/jengu-net/dbo/issues/151) (the edge's work
lane: claim advancement across it, edge-originated work as upstream —
posed by platform#917's work-lane-first sequencing).
Closed: [#69](https://github.com/jengu-net/dbo/issues/69) /
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
on the checkpoint).

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
| 8 | **The run vocabulary, discoverable** — [#91](https://github.com/jengu-net/dbo/issues/91)'s precondition: the systems a rendered run carries, published as `CodeSystem`s the same tenant serves. | **PARTLY DONE** — the vocabulary half is published, fetchable and split (`urn:dbo:run:output` is no longer also `urn:dbo:run`), proven by `VocabularyIsDiscoverableIT`. **What remains is a profile for the rendered `Task`**, and that is what gates serving runs over HTTP |
| 9 | **The reference runner: transport first, DBOS below** ([#79](https://github.com/jengu-net/dbo/issues/79)) — the participation link exercised end to end. | **DONE** 2026-08-27 — the seam holds from this side (`ARemoteLaneIsIndistinguishableIT`, kept that way by `ALaneStaysTransportShapedTest`), and both layers now hold at once when a participant dies mid-work: the run is owed again above and the checkpointed half is not redone below (`DbosBelowResumesItsOwnHalfFinishedWorkIT`). The wire itself stays the consumer's (ADR 0062) |
| 10 | **Vital signs on the link** ([#148](https://github.com/jengu-net/dbo/issues/148)) — what rides the carrier, and a presence display that does not page about a healthy idle fleet. | **NEXT** — its carrier is delivered; the runner already publishes vitals on the declaration record |
| 11 | **The replication toolset** ([#80](https://github.com/jengu-net/dbo/issues/80)) — moving the work and the data it names between two appliances. | **PARTLY DONE** — the batch, the idempotent-and-reorder-safe apply, the epoch, echoed markers, mirrored filing, work-driven expiry and the process allowlist are all built and proven (`TwoAppliancesOneTenantIT`), and a peer back from a weekend converges without dragging over what it holds no work for. **What remains**: audit replicating as recorded — blocked on a decision, since `PolicyObjectStore` refuses every direct `AuditEntry` write and nothing yet wires `Lanes` to a policy-wrapped store, so admitting the lane through that refusal has no production shape to aim at yet — inherits "slots are part of what must be present for the work being held" |
| 12 | **The edge's work lane** ([#151](https://github.com/jengu-net/dbo/issues/151)) — claim advancement across the lane, edge-originated work as upstream. | **BLOCKED by the consumer's sequencing** — posed by [platform#917](https://github.com/jengu-net/jengu-platform/issues/917), whose work-lane-first order owns when this is answered |
| 13 | **The console** ([#75](https://github.com/jengu-net/dbo/issues/75) / [#76](https://github.com/jengu-net/dbo/issues/76)) — describing the catalogue and the runs, with a tenant context and an identity when it acts. | **LAST, needs 8** — it reads runs over HTTP, so the `Task` profile gates it too. It is also what would answer `PROC_NETWORK_MAP`, still honestly `PLANNED` |

**The critical path** is 8 → 13: the `Task` profile unblocks serving runs over
HTTP, which the console stands on. Step 9 never needed it — the seam it was
thought to gate turned out provable without HTTP, because a lane is not a FHIR
client. Steps 10 and 11 hang off 9, which is now done, so both are ready and
independent of each other. Step 12 waits on somebody else's calendar, not on
this repository.

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

**Two catalogues mid-migration is how they drift.** The original reason #71
followed platform#851. Resolved by the emergent-catalogue decision above:
nothing is ported, so the seam landed early without creating a second copy —
but the trap still governs anyone tempted to *translate* the platform's enums
across wholesale. They arrive as introductions, or they wait.

## Not doing

**The socket.** Framing, handshake, tenant authentication of the edge lane
are the consumer's (ADR 0062). dbo builds the store-level toolset the lane
uses, and the same runner embeds on either end.

**Partitioning.** Competition is fine at small N; a partition hint belongs on
the run only once one step has measurably outgrown claim-racing.

**Trusting introduced steps.** A step introduced over the link (#147) grants
its introducer nothing — what it may take stays the intersection of its
scopes and what the step admits, like every declaration.

## Verifying

```bash
./gradlew :core:dbo-work:test :core:harness:test --tests '*ParticipantsPullAndClaimIT' --tests '*ExecutorIsRecordedIT' --tests '*RunsRenderIT' --tests '*StepsAreDeclaredIT' --tests '*MandatoryStepsClassifyIncidentsIT' --tests '*StepRunnerIT' --tests '*ReportsGoThroughDeclaredActionsIT' --tests '*RunNamesItsInputsIT' --tests '*MilestonesOnTheCheckpointIT' --tests '*StepsArriveByIntroductionIT' --tests '*ClaimIsTheIntersectionIT' --tests '*ARemoteLaneIsIndistinguishableIT' --tests '*ALaneStaysTransportShapedTest' --tests '*DbosBelowResumesItsOwnHalfFinishedWorkIT'
```
(The link scenarios get their ITs with the transport exercise in #79.)
