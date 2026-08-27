# Distributed work

**Status** — doctrine decided and written; **#71, #147, #149 and #150 are
closed**: the declaration seam (steps, actions, mandatory-steps incident
classification), introduction over the link, run inputs filling declared
slots, and milestones on the checkpoint. The participant's pull AND
reporting halves are built — #77 stays open for its credential half and the
#91 precondition. The console (#75/#76), the transport exercise (#79's
remainder) and the replication toolset (#80) are what is left.

**Issues** — the participation cluster, formerly under the closed #46:
[#71](https://github.com/jengu-net/dbo/issues/71) (declaration seam — done,
close on CI) ·
[#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (console) ·
[#77](https://github.com/jengu-net/dbo/issues/77) (participant) ·
[#79](https://github.com/jengu-net/dbo/issues/79) (reference runner) ·
[#80](https://github.com/jengu-net/dbo/issues/80) (replication toolset) ·
newly scoped: [#147](https://github.com/jengu-net/dbo/issues/147) (a
participant introduces its step) ·
[#148](https://github.com/jengu-net/dbo/issues/148) (vital signs on the link) ·
[#149](https://github.com/jengu-net/dbo/issues/149) (a run names its inputs) ·
[#150](https://github.com/jengu-net/dbo/issues/150) (milestones on the
checkpoint)

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
`Task` assigned to a process step; every `Task` refers to the objects it is
about, and those travel with the work (*work is the manifest*). The runner
reflects progress back, reports which DBOS instance carried it, may
**introduce a step the catalogue has not declared** (#147 — the dynamic half
of process building), and its link carries **extensible metrics** — health,
throughput — beside the presence the cursor already proves (#148).

## Where it stands

- **Decided and written**: the whole participation doctrine, in
  process-catalogue.md — pull-never-push, claim as conditional write with
  deadline, checkpoint-not-heartbeat, released-is-not-done, two layers owning
  different failures, executor declarations with derived presence, scale by
  adding claimants.
- **Built**: run records (#69), the face rendering a run as `Task` (#70),
  executor declaration and resolution (#72, #78), `Participation` in
  `dbo-work` (pull/claim/checkpoint/release — #77's pull half), **the runner
  itself** (`core:dbo-runner`, #79): an embeddable OSGi bundle — install it
  into the existing container and the activator whiteboard-tracks
  `StepService`s from any bundle and `Lane`s from the host; outside OSGi,
  construct `StepRunner` directly. Vitals ride the declaration record
  (#148's carrier, delivered). And **the declaration seam whole** (#71):
  `StepDeclaration` with domains, shapes, actions and overridability;
  `Steps` installed-not-listed; shape validation through the face; the
  spec's `mandatorySteps` classifying incidents (`StepIncidents`,
  `stepIncidents()`).
- **Built, continued**: #77's reporting half — reports go through the step's
  declared actions, checked in `Runs` itself (the primitive, so lane and
  console meet one rule): `close` and `reopen` are narrowed where declared,
  releasing never is, and `Runs.reopen` makes a closed run claimable again
  with the reason on the record. What keeps #77 open: the credential half
  (scopes ∩ step's admission — arrives with the acting surface) and #91's
  discoverability precondition (run coding systems as fetchable
  `CodeSystem`, a profile for the rendered `Task`), which gates serving
  runs over HTTP.
- **Built, continued (#149)**: runs name their inputs. Slots on the step
  declaration (`taking(slot, shapeRef)`, beside `consumes` — focus vs
  input, FHIR's own split), `Run.inputs` filling them at creation with both
  mismatches refused by name, `Task.input` rendering in declaration order,
  and the in-process lane resolving `Type/id` references from the host's
  store for the claiming identity only. First slice on the promise
  catalogue beyond the pilot: AREA `DISTRIBUTED_WORK` → FEAT
  `WORK_ARRIVES_WHOLE` → four `REQ-DBO-PROC-*` promises, cited by
  `@Proving` in `RunNamesItsInputsIT` and projected into req-catalogue's
  generated block.
- **Built, continued (#150)**: milestones on the checkpoint.
  `StepDeclaration.reaching(...)` declares the order; `Runs.milestone`
  derives the position and refuses strangers by name; the run keeps it
  replaced-never-accumulated across release and retake; `businessStatus`
  renders holder + milestone with the derived text ("validated, 2 of 3").
  `Work.Progress` and `Lane` carry `milestone` as ABSTRACT methods — no
  silent default anywhere on the reporting path, or a decorator drops the
  one thing the report said while passing every test. FEAT
  `WORK_SAYS_WHERE_IT_IS` under the DISTRIBUTED_WORK area, three promises
  PROVEN via `MilestonesOnTheCheckpointIT`.
- **Built, continued (#147)**: a participant introduces the step it
  performs. `IntroductionModel`/`Introductions` in `dbo-work` (record per
  step id, introducer as provenance, kept until withdrawn — presence gates
  candidacy, not the record); `StepService.declaration()` →
  `StepRunner` introduces beside the candidacy → `Lane.introduce`
  (abstract, the no-silent-default rule); `Introductions.composedWith()`
  is the one catalogue view (collision refused across both doors), read by
  `Runs` and by the mandatory-steps classification per tenant — proven by
  a mandatory step satisfied over the link, nothing installed. FEAT
  `THE_CATALOGUE_LEARNS`, three promises PROVEN.
- **Open, in dependency order**: #77's remainder (above) → #79's remainder
  (DBOS below, the transport-first exercise) → #148's remainder if any →
  #80 (the replication toolset — inherits "slots are part of what must be
  present") → #75/#76 (the console over it all).
- **Consumer's half, later**: the WebSocket lane (socket, framing, handshake,
  tenant auth) is the platform's per ADR 0062; the k8s per-step Deployment
  packaging likewise. dbo owes the store-level toolset (#80) and nothing
  transport-shaped.

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

A claimable run today declares no slots (participants pull pipeline runs;
item runs are parked problems for people, filtered out of poll), so #149 is
not an enhancement — it is the only official way documents reach a
distributed runner. `Work.inputs` is the waiting seam; #149 fills it with
no service or runner change.

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
it.

**Work is the manifest.** A run names the versions it produced, so the far
side asks for exactly what it is missing instead of comparing stores. This is
how "the Task's related objects travel automatically" is honest: the run
enumerates, the toolset (#80) moves.

**Scale by adding claimants, never by relaxing the claim.** The k8s answer is
therefore packaging, not mechanism: a Deployment per step, replicas up. A
partition hint is the second lever and is not built until one step measurably
outgrows competition.

**Presence is derived, metrics annotate** (#148). A self-reported "healthy"
from a stuck component is exactly the lie cursor-derived presence exists to
catch — so metrics ride the link and inform, and presence stays computed.

**A step id is opaque and globally stable, fixed with the record, not the
catalogue** — which is what makes #147 (a participant introducing a step)
an addition rather than a migration: a run could always name a step that had
no declaration yet.

## Traps

**A run speaks its step name bare; the catalogue speaks it fully.** A run
records `process` and `step` as separate fields and `poll` filters on the
bare step, while a service declares `<module>.<process>.<step>`. The runner
translates at the boundary — found by the first integration test, which
polled a perfectly good run and matched nothing.

**A caught-up participant's cursor does not move either.** Silence with
nothing waiting is not absence; only silence with work waiting is. Any
presence display or alert built on cursor movement must carry this
distinction or it will page somebody about a healthy idle fleet.

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
./gradlew :core:dbo-work:test :core:harness:test --tests '*ParticipantsPullAndClaimIT' --tests '*ExecutorIsRecordedIT' --tests '*RunsRenderIT' --tests '*StepsAreDeclaredIT' --tests '*MandatoryStepsClassifyIncidentsIT' --tests '*StepRunnerIT' --tests '*ReportsGoThroughDeclaredActionsIT'
```
(The link scenarios get their ITs with the transport exercise in #79.)
