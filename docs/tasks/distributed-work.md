# Distributed work

**Status** — doctrine largely decided and written; code started
(`Participation` exists with pull/claim); the runner, the console and the
transport are open; two elements newly scoped.

**Issues** — the participation cluster, formerly under the closed #46:
[#71](https://github.com/jengu-net/dbo/issues/71) (declaration seam) ·
[#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (console) ·
[#77](https://github.com/jengu-net/dbo/issues/77) (participant) ·
[#79](https://github.com/jengu-net/dbo/issues/79) (reference runner) ·
[#80](https://github.com/jengu-net/dbo/issues/80) (replication toolset) ·
newly scoped: [#147](https://github.com/jengu-net/dbo/issues/147) (a
participant introduces its step) ·
[#148](https://github.com/jengu-net/dbo/issues/148) (vital signs on the link)

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
  executor declaration and resolution (#72, #78), and `Participation`
  (pull/claim) in `dbo-work` — #77's core, ahead of its issue.
- **Open, in dependency order**: #71 (the declaration seam — everything else
  refers to steps it defines) → #77 (finish the participant: report, deadline
  lapse, dedup) → #79 (the reference runner) → #147/#148 (the two new
  elements) → #80 (the replication toolset) → #75/#76 (the console over it
  all).
- **Consumer's half, later**: the WebSocket lane (socket, framing, handshake,
  tenant auth) is the platform's per ADR 0062; the k8s per-step Deployment
  packaging likewise. dbo owes the store-level toolset (#80) and nothing
  transport-shaped.

## Decisions

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

**A caught-up participant's cursor does not move either.** Silence with
nothing waiting is not absence; only silence with work waiting is. Any
presence display or alert built on cursor movement must carry this
distinction or it will page somebody about a healthy idle fleet.

**Two catalogues mid-migration is how they drift.** #71 deliberately follows
platform#851 rather than running beside it — the platform's working catalogue
(`platform-process-api`) is on the other side of the cutover. Starting #71
"early" re-creates the drift it was parked to avoid; check the migration
topic's state first.

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
./gradlew :core:dbo-work:test :core:harness:test --tests '*ParticipantsPullAndClaimIT' --tests '*ExecutorIsRecordedIT' --tests '*RunsRenderIT'
```
(The runner and link scenarios get their ITs with #77/#79.)
