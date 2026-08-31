# An operator's control plane

**Status** — nothing built for it yet, and less is needed than it looks: the
state a control plane wants is already records in tenant stores, and the
console already queries all of it for one JVM. What is missing is fleet-level
reach and a bounded way to *act*.

**Issues** — [#160](https://github.com/jengu-net/dbo/issues/160) (a runner
keeps no state that spans lanes) · [#161](https://github.com/jengu-net/dbo/issues/161)
(fleet observation is telemetry, not records) ·
[#162](https://github.com/jengu-net/dbo/issues/162) (`PROC_NETWORK_MAP` splits).
Closed and load-bearing here: [#75](https://github.com/jengu-net/dbo/issues/75) /
[#76](https://github.com/jengu-net/dbo/issues/76) (the console: describing, and
an identity when it acts).

**Concepts** —
[distributed work](../arc42-008-crosscutting/distributed-work.md) ·
[process catalogue](../arc42-008-crosscutting/process-catalogue.md) ·
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
| 1 | **A runner keeps no state that spans lanes** ([#160](https://github.com/jengu-net/dbo/issues/160)) — without it, anything a control plane reports per tenant is contaminated by other tenants. | **NEXT** |
| 2 | **Telemetry seam** ([#161](https://github.com/jengu-net/dbo/issues/161)) — trends and alerting, on the envelope's field set. Not the control plane's state source; see `Decisions`. | **READY, needs 1** |
| 3 | **Split `PROC_NETWORK_MAP`** ([#162](https://github.com/jengu-net/dbo/issues/162)) — the catalogue half names the node-inventory gap this plane would surface. | **READY** |
| 4 | **Fleet-level read** — one process holding per-tenant credentials, fanning out over the surfaces that already exist, labelling every answer with the node it came from. | **LATER** |
| 5 | **Bounded act** — the same verbs a participant has, through a lane, with an identity and an entitlement. | **LATER** |

**The critical path is step 1**: everything a control plane would say about one
tenant is wrong until a runner's accounting stops spanning lanes.

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

Nothing to verify yet; step 1 carries the first test, and it is named in its
issue — one runner, two lanes, work on the first, the second's declaration
untouched.
