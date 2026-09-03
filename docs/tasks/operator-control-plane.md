# An operator's control plane

**Status** — the read half reaches the fleet: a reader outside every container
fans out over the nodes' and tenants' own doors and labels every answer with
its node, as a command that reads once or a service that reads when asked,
imaged beside the operator. The act half has its door: reopening a closed run
is a lane verb reached by a supervisory entitlement of its own. What is left is
giving the reader that verb.

**Issues** — none open. Closed and load-bearing here:
[#160](https://github.com/jengu-net/dbo/issues/160) (a runner keeps no state
that spans lanes) · [#161](https://github.com/jengu-net/dbo/issues/161) (the
telemetry seam) · [#162](https://github.com/jengu-net/dbo/issues/162)
(`PROC_NETWORK_MAP` split) · [#163](https://github.com/jengu-net/dbo/issues/163)
(an exporter bundle) · [#164](https://github.com/jengu-net/dbo/issues/164)
(trace context rides the lane) · [#75](https://github.com/jengu-net/dbo/issues/75) /
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
- **The act half is on the lane, with an authority of its own.** `released`,
  `closed`, `release-lapsed` and `claim` were already lane verbs; `reopen` was
  not, so the one act an operator opens a console for — making a wrongly
  closed run claimable again — was a primitive nothing outside a test called.
  It is a verb now, reached by the supervisory half of an entitlement:
  `supervise`, or `supervise/<step>`. Proven over real HTTP in
  `SupervisionIsItsOwnEntitlementIT`.
- **The fleet-level read exists as a command.** `core/dbo-fleet` is a plain
  jar in the operator's shape: given the nodes, the deployment's token and a
  secret per tenant, it asks each node what it serves and what it has
  installed, asks each tenant for its runs as envelopes, unions the
  inventories into the network map, and prints the reading. Two runtimes in
  one JVM stand in for two nodes in `TheFleetIsReadFromOutsideEveryNodeIT`,
  and a third node nobody started is in the reading as unreachable.
- **The same jar is the service.** Given an address and a token it serves
  `GET /fleet` and reads the fleet afresh on every ask, holding nothing between
  asks — the second-store decision applied to the service form. The build loop
  pushes `dbo-fleet` beside `dbo-operator` and `dbo-server`.
- **Step 5 has not started.** Acting goes through a lane with an identity and
  an entitlement, and nothing here holds either.

## Sequence

| # | Step | Status |
|---|---|---|
| 1 | **A runner keeps no state that spans lanes** ([#160](https://github.com/jengu-net/dbo/issues/160)) — without it, anything a control plane reports per tenant is contaminated by other tenants. | **DONE** 2026-08-31 — counters keyed by lane then step, and detach drops that lane's map, which was a second defect hiding behind the first (`ARunnerKeepsNoStateAcrossLanesIT`) |
| 2 | **Telemetry seam** ([#161](https://github.com/jengu-net/dbo/issues/161)) — trends and alerting. Not the control plane's state source; see `Decisions`. | **DONE** 2026-08-31 — `cloud.jengu.dbo.telemetry`, a closed `Label` set, discarding by default, and the runner reporting through it (`NumbersLeaveWithoutTheWordsIT`). The label set is the envelope's fields **minus** identifiers and any correlation echoed from elsewhere — the envelope was written for a tenant reading its own runs, and a label travels further |
| 2a | **An exporter** ([#163](https://github.com/jengu-net/dbo/issues/163)) — a provider bundle a deployment installs, so the numbers reach a collector. | **DONE** 2026-09-03 — `dbo-telemetry-otlp`: OTLP over HTTP in the JSON encoding, rendered and sent with the JDK's own client so no protocol library rides in the container; configured by the deployment and idle without an endpoint (`NumbersLeaveTheNodeIT`, against a collector stood up in the test). Building it found the quiet failure: under OSGi the seam's ServiceLoader lookup found nothing, so the seam now declares the consumer capability and a container proof asks the seam what it found (`NumbersLeaveTheContainerIT`) |
| 2b | **Trace context on the lane** ([#164](https://github.com/jengu-net/dbo/issues/164)) — one run as one chain across two processes. | **DONE** 2026-09-01 — a run carries the trace context it was given, never one it invented, across the lane and down to the runs it causes (`OneRunIsOneChainAcrossTwoProcessesIT`) |
| 3 | **Split `PROC_NETWORK_MAP`** ([#162](https://github.com/jengu-net/dbo/issues/162)) — the catalogue half names the node-inventory gap this plane would surface. | **DONE** 2026-08-31 — a node answering its own catalogue is its own promise and proven; what remains under the old code is the per-node inventory, which the console's tests now cite because that module was never wired into the promise index |
| 4 | **Fleet-level read** — one process holding per-tenant credentials, fanning out over the surfaces that already exist, labelling every answer with the node it came from. | **DONE** 2026-09-03 — a node serves its installed catalogue at `/runtime/catalogue` beside `/runtime/tenants`, under the same token; a tenant's fleet door answers `runs` as envelopes; `core/dbo-fleet` reads, unions and labels, as a command or as a service that reads on every ask and keeps nothing (`TheFleetIsReadFromOutsideEveryNodeIT`, which also proves `PROC_NETWORK_MAP`); imaged in the build loop |
| 5 | **Bounded act** — the same verbs a participant has, through a lane, with an identity and an entitlement. | **IN PROGRESS** — first slice 2026-09-03: `supervise` as its own scope, `reopen` as a lane verb over both carriers, and the step's declared actions now enforced on the lane at all (they were not). Left: the reader holding a supervisory credential, so an operator acts with the tool it reads with |

**What is left is the rest of step 5.** The door exists and `curl` reaches it;
what an operator does not yet have is the act in the tool it reads with.

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

**Runs are answered on the tenant's fleet door, not on the FHIR surface.** The
`fleet` scope was minted for what a deployment may ask about its fleet, as
distinct from what a bench may do, and "what is held by a person here" is that
question. A Task search on the store surface would have needed a store-read
credential, which reads documents; the fleet door answers envelopes and nothing
below them, so the reader's credential cannot read anything a run was over.

**The node's inventory is a node question under the deployment's token.** It
names steps the node carries whether or not any tenant is up, and it is
descriptive: nothing is declared to make the map, because a step declared by
two doors is a collision by design and two nodes carrying the same modules
would collide at once. The reader unions inventories; it introduces nothing.

**The reader holds one secret per tenant, from a directory.** A file per tenant
code is what a mounted Secret looks like from inside a pod, and the default
client is the deployment's own `tenant-bootstrap`, which already carries the
fleet scope. A tenant the reader was given no file for is in the reading as
"no credential" and is not asked — reach is exactly what somebody handed it.

**Node names are the deployment's.** A node that could name itself could name
itself as another, and every answer is trusted exactly as far as its label. The
reader is told `name=uri` and labels with the name it was told.

**Sequential fan-out, bounded per ask.** Every ask has a timeout and every
outcome is recorded, so one dead node costs one timeout and one line. Parallel
fan-out buys latency and pays with a second failure mode; it becomes worth
having when a deployment has enough nodes that a serial read is slow, and not
before.

**Supervision is its own scope, not a corner of participation.** `fleet` was
split from `work` because what a bench may *do* and what a deployment may *ask*
are different questions; the same argument splits what a supervisor may *undo*.
A runner that validates lab results has no business overturning the results
somebody judged done, and whoever overturns them performs no work — so neither
scope implies the other, and the bare `work` scope, which says the holder *is*
the tenant, supervises nothing either. That last part is the `erasure` rule
applied again: a credential that may write every type still may not destroy a
person unless somebody wrote the word down, and the same holds for undoing a
judgment. Bounded as `supervise/<step>`, it meets the step's declared `reopen`
action as the other half of reach, which is the intersection rule a claim
already obeys.

**The verb goes on the lane, not on a second surface.** The reading half got
its own door because asking what state a fleet is in is not a participant act;
acting is. Everything the store's rules check about a report — the declared
actions, the run's ownership, the trail — is checked at the primitive the lane
passes through, so a supervisory surface beside the lane would be a second path
to those rules and eventually a second copy of them.

**Narrowing an entitlement narrows both halves.** A lane bounded to some steps
is bounded for every purpose. Keeping the supervisory half whole while
narrowing the working one would leave a reach nobody asked to keep; dropping it
entirely would take away a grant on a field that says it narrows work.

**The service holds nothing between asks.** A reading kept for even a moment
is a second answer to a question the tenant stores answer authoritatively, and
the two disagree exactly when an operator is deciding on it. So every ask is a
fresh fan-out, and an ask takes as long as the slowest node's timeout. The
alternative was being wrong quickly.

**It never becomes a second store.** It queries; it does not copy. A cached
mirror of many tenants' runs would be a second answer to a question the tenant
store already answers authoritatively, and the two would disagree exactly when
it mattered.

## Traps

**A rule at the primitive is only as good as what every caller hands it.**
Reports land through the step's declared actions, and the check lives on
`Runs` precisely so the lane, the authoring surface and the console meet one
copy of it. `Runs` resolves the declaration through a step catalogue, and the
container built the lane's `Runs` without one — so it resolved nothing,
narrowed nothing, and the lane accepted every verb of every step. A step whose
declaration says its closure is a person's act was closed by a participant
reporting done, and nothing anywhere failed.

It survived because the promise's test builds `Runs` with a catalogue by hand,
which is the harness trap in its second form: not "who constructs this outside
a test" but *what does the container hand it that a test hands itself*. The
catalogue is now composed — installed steps plus introduced ones — where the
lane's `Runs` is built, and a lane-level test holds a participant to a
declaration.

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
    --tests '*TheConsoleSaysWhoWouldRunAStepIT*' \
    --tests '*TheFleetIsReadFromOutsideEveryNodeIT*' \
    --tests '*SupervisionIsItsOwnEntitlementIT*' -PdboTestHeap=2g
./gradlew :karaf:commands:test
./gradlew :core:dbo-fleet:installDist && \
    DBO_FLEET_NODES=a=http://127.0.0.1:1 DBO_OPS_TOKEN=t \
    DBO_FLEET_CREDENTIAL_DIR=/nonexistent core/dbo-fleet/build/install/dbo-fleet/bin/dbo-fleet
```

The last one is the reader as a process rather than a class: it must print a
reading with the node unreachable and exit zero, because the read exists to
say which nodes did not answer. With `DBO_FLEET_LISTEN=127.0.0.1:18091` and
`DBO_FLEET_TOKEN` set it serves instead, and `GET /fleet` answers the same
reading behind the token.

One runner against two lanes, work failing on one, the other's declaration
untouched — checked against the old behaviour as well as the new, because a
regression test that has never been seen to fail proves only that it compiles.
The console's two halves: what a node knows without a store, and which executor
would take a step with one.
