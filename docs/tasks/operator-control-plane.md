# An operator's control plane

**Status** — the groundwork is done and the plane itself is not started. The
state it wants is already records in tenant stores, the console already queries
all of it for one JVM, and per-tenant answers are now uncontaminated. What is
missing is fleet-level reach and a bounded way to *act*.

**Issues** — open: [#163](https://github.com/jengu-net/dbo/issues/163) (an
exporter bundle) · [#164](https://github.com/jengu-net/dbo/issues/164) (trace
context rides the lane). Closed and load-bearing here:
[#160](https://github.com/jengu-net/dbo/issues/160) (a runner keeps no state
that spans lanes) · [#161](https://github.com/jengu-net/dbo/issues/161) (the
telemetry seam) · [#162](https://github.com/jengu-net/dbo/issues/162)
(`PROC_NETWORK_MAP` split) · [#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (the console: describing, and
an identity when it acts).

**Concepts** —
[processes and work](../arc42-008-crosscutting/processes-and-work.md) ·
[the console](../plans/karaf-console.md)

## What this is

An operator running this store for many tenants has no way to see or steer the
work across them. Each tenant's store answers for itself, the console answers
for one JVM, and nothing answers "which runners and appliances exist, what is
stuck, and make that run claimable again". Durable-workflow products ship a
control plane for exactly this; theirs is scoped to their own workflows, which
is the wrong unit here — the unit is a run on a lane, and most of what an
operator needs to see never runs inside a workflow engine at all.

The problem is not collecting the data. It is reach and authority: how a
process outside every container reads across tenants without becoming a
cross-tenant surface, and how it acts on a tenant's work without becoming a
back door around the rules every other actor obeys.

## Where it stands

- **The read half is largely built and one node wide.** Runs are records;
  declarations carry version, provider and scope; presence is derived from
  cursors; trackables carry what sits behind a router. `dbo-run:list`,
  `dbo-run:describe`, `dbo-process:list` and `dbo-process:describe` query all
  of it — for the JVM the console is attached to.
- **The act half exists as verbs, not as a surface.** `Runs.reopen` makes a
  closed run claimable with its reason recorded; `released`, `releaseLapsed`
  and `claim` are the rest. They are reached through a lane today.
- **Nothing is fleet-level**, and that is a service question rather than a
  data question: the stores already hold the answers.

## Sequence

| # | Step | Status |
|---|---|---|
| 1 | **A runner keeps no state that spans lanes** ([#160](https://github.com/jengu-net/dbo/issues/160)) — without it, anything a control plane reports per tenant is contaminated by other tenants. | **DONE** 2026-08-31 — counters keyed by lane then step, and detach drops that lane's map, which was a second defect hiding behind the first (`ARunnerKeepsNoStateAcrossLanesIT`) |
| 2 | **Telemetry seam** ([#161](https://github.com/jengu-net/dbo/issues/161)) — trends and alerting. Not the control plane's state source; see `Decisions`. | **DONE** 2026-08-31 — `cloud.jengu.dbo.telemetry`, a closed `Label` set, discarding by default, and the runner reporting through it (`NumbersLeaveWithoutTheWordsIT`). The label set is the envelope's fields **minus** identifiers and any correlation echoed from elsewhere — the envelope was written for a tenant reading its own runs, and a label travels further |
| 2a | **An exporter** ([#163](https://github.com/jengu-net/dbo/issues/163)) — a provider bundle a deployment installs, so the numbers reach a collector. | **READY** — needs a live collector to verify, so it cannot be finished on a laptop |
| 2b | **Trace context on the lane** ([#164](https://github.com/jengu-net/dbo/issues/164)) — one run as one chain across two processes. | **READY** — buildable and provable before 2a exists |
| 3 | **Split `PROC_NETWORK_MAP`** ([#162](https://github.com/jengu-net/dbo/issues/162)) — the catalogue half names the node-inventory gap this plane would surface. | **DONE** 2026-08-31 — a node answering its own catalogue is its own promise and proven; what remains under the old code is the per-node inventory, which the console's tests now cite because that module was never wired into the promise index |
| 4 | **Fleet-level read** — one process holding per-tenant credentials, fanning out over the surfaces that already exist, labelling every answer with the node it came from. | **LATER** |
| 5 | **Bounded act** — the same verbs a participant has, through a lane, with an identity and an entitlement. | **LATER** |

**The critical path is step 4**, and it is the first step that is a service
rather than a rule: everything before it makes the per-tenant answers true, and
nothing yet reaches more than one node to ask.

## Decisions

**Read and act are separate surfaces, with separate authority.** The console
already made this split — it describes without an identity (#75) and gains one
only when it acts (#76). Durable-workflow control planes bundle both into one
channel, which is why their protocols read as mostly mutations: cancel, delete,
fork, restart, retention. An operator needs to *look* far more often than to
*act*, and the looking should not require the authority to destroy work.

**Acting goes through the lane, never around it.** `Runs` already checks reports
against the step's declared actions, so that the lane, the console and whatever
comes next meet one rule. A control plane writing to runs directly would be
precisely the back door that rule exists to prevent. It therefore holds an
identity and an entitlement like any other participant, and can do nothing an
operator could not legitimately do through the ordinary door — which is the
property worth having when the thing can cancel somebody's work.

**It lives outside the container, with a scoped role.** The provisioning
operator is the precedent: its own process and pod, a plain jar rather than a
bundle, with a scoped role and never superuser. A control plane is the same
shape, and it holds per-tenant credentials and fans out rather than being
handed a cross-tenant surface — so tenant isolation is preserved by
construction rather than punctured for convenience.

**State comes from the stores, trends come from telemetry.** The two are not
interchangeable. Telemetry is aggregated, delayed and lossy by design — right
for "is the fleet healthy", wrong for "is this run claimable now". A control
plane that read its state from a metrics pipeline would act on a stale view of
somebody's durable work.

**It never becomes a second store.** It queries; it does not copy. A cached
mirror of many tenants' runs would be a second answer to a question the tenant
store already answers authoritatively, and the two would disagree exactly when
it mattered.

## Not doing

**Speaking a third-party control protocol, or standing up an endpoint that
serves one.** Beyond licensing, that protocol's verbs are overwhelmingly
mutations, so an endpoint speaking it holds cancel, delete, fork and retention
rights over every executor that connects — a large authority surface acquired
in order to read counters. Its metric payload is also a flat
`(type, name, long)` with no labels, so nothing of ours could ride it.

**Synthesising executions so a workflow engine emits on our behalf.** Its
metrics are computed from durable rows, so fabricated telemetry is fabricated
state, with real ids, to which recovery and replay then apply.

**Modelling physical placement.** A declaration is idempotent by key, so
identical replicas collapse to one row — deliberately, a fleet is not a
collision. "Where" is answered as scope, provider and version. Hostname-level
answers would mean reopening that.

## Verifying

```bash
./gradlew :core:harness:test --tests '*ARunnerKeepsNoStateAcrossLanesIT*' \
    --tests '*TheConsoleSaysWhoWouldRunAStepIT*' -PdboTestHeap=2g
./gradlew :karaf:commands:test
```

One runner against two lanes, work failing on one, the other's declaration
untouched — checked against the old behaviour as well as the new, because a
regression test that has never been seen to fail proves only that it compiles.
The console's two halves: what a node knows without a store, and which executor
would take a step with one.
