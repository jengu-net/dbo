**Status: Proposed.** Reflected in nothing yet.

# The store knows workers, not devices

A platform built on the store binds devices, connects them, updates them and
drives them. The question is how much of that the store should know. The
trackable record leaned towards knowing some of it: a tree of things with a
kind, a state and an attestation, written so that a face could project it
onto FHIR `Device`.

**Direction: the store has no device semantics. It tells an application
when the workers of a step appear and go silent, and the application decides
what to record.**

## Decided

**No device vocabulary.** From the store's side an edge, an appliance or an
instrument's connector is a worker: a participant that performs steps over a
lane. The store offers neutral seams: lanes and their transports, routers
and routees, observers, and the presence listener below. It never projects
anything onto `Device`. That answers the `Device` projection named under the
devices domain in the [IHE profiles item](https://github.com/jengu-net/dbo/blob/main/docs/arc42-011-risks-and-technical-debt/008-ihe-profiles/README.md):
a consumer keeps its own `Device` records, and writes them through work.

**Presence is noticed, not stored.** An application may register, per step,
a presence listener for that step's workers. With none registered the store
tracks nothing for the step, and it costs nothing. A registration must
declare its silence threshold. There is no default, and a registration
without one is refused at startup, naming the listener. The threshold is the
listener's choice because the store imposes no freshness rule of its own;
each hop owns liveness for the hop below it. Two listeners on one step may
declare different thresholds.

**The events.**

- `appeared(tenant, step, worker, node, since, statistics?)`: the first
  activity after unknown.
- `statistics`: each heartbeat's statistics while appeared, passed on as
  they arrive.
- `unknown(tenant, step, worker, node, lastSeen)`: no activity within this
  listener's threshold.
- `reset(step, node)`: sent to every listener when a node starts, meaning
  everything for this step is unknown on this node and must appear again.

The word is *unknown*, not *quiet* or *gone*. After silence or a restart the
store does not know, and saying gone would claim more than it can see.

**Activity** is any request from that worker for that step: poll, claim,
checkpoint, close or heartbeat. A poll naming several steps counts for each;
a claim, checkpoint or close counts for its run's step. **A worker** is
identified by its client id plus executor name and version.

**Heartbeat is a lane verb**, `heartbeat(statistics)`, carried by every
transport. A worker on the substrate lane is woken rather than polling, and
a worker holding a long claim may say nothing for a long time; presence read
only from ordinary requests would mark exactly those workers unknown. The
lane goes from 22 verbs to 23, and `ALaneStaysTransportShapedTest` records
the reason when the verb is built. A heartbeat writes nothing to the
database: it updates in-memory presence and calls the listeners.

**Statistics are an open, structured document**: one JSON object per
heartbeat, nesting allowed, so an edge's own counters and the state of each
thing it routes arrive in one event. The store neither interprets, validates
nor stores it. Top-level keys are namespaced by contributor so several
contributors on one worker do not collide; the store reserves only `dbo.`.
A configurable size cap, 64 KB by default, is enforced, and an oversized
heartbeat is refused with the limit named. Statistics carry no personal
data. The store cannot check that, so it is a rule for senders: heartbeats
travel over the lane's authenticated transport but outside sealed work, and
anything about a person travels only as sealed work in a run.

**The listener is the only one that decides what is recorded.** Wanting
something stored, it starts a task through the ordinary work path
(`DboInitiator`) with an idempotent run key, such as worker, step,
transition and time bucket. That task's step writes the consumer's records
through its result (`writing(...)`), so every recorded change carries
provenance and passes the tenant's validation. Nothing about presence is in
the database unless such a task wrote it.

**Several nodes may disagree.** Presence is in memory per node. Each event
names the node that observed it, and the consumer decides what present means
across nodes, for instance present if any node heard it recently. Its run
keys collapse duplicate decisions. Central presence in the database was
weighed and rejected: it brings back a write per heartbeat.

**Trackables shrink to the routee edge.** `routes(...)` and the trackable
record overlap with heartbeat statistics for "what is behind me". Sealing
uses one fact from them: `Lane.sealed(run, recipients)` refuses a recipient
that is not `routedBy` the asking participant. The routee's key is not on
the trackable; it is the routee's own enrolment key, looked up through
`Lane.Keys`. So what stays is a trackable's `id` and `routedBy`, the
declaration that a routee sits behind this router. What goes is `kind`,
`state`, `attested` (`observedBy`, `observedAt`), `unreported` and the
departed reading, with the reads built on them: `observedBy`, `subtree` and
the fleet door's state answers. Live state of routed things travels in the
router's heartbeat statistics to the consumer's listener.

## Open, for decision

**A. An edge: routee or direct participant.** As a *routee*, a router such
as a consumer's connector holds an ordinary lane, its routees are reached
over the consumer's own wire, and no lane verb crosses that wire; the
router's statistics carry its routees' state. As a *direct participant*,
each edge enrols in its tenant with its own keys, holds its own lane and
heartbeats itself. The listener sees direct workers only, so a routed edge
reaches the consumer through its router's statistics. Recommendation:
routee where the consumer already owns the wire to the edge, direct where
the edge can hold a lane of its own; the store supports both and need not
choose.

**B. Lane transports.** The store ships two: HTTP (`WireLane`,
`LaneVerbService`, `LaneHandler` in `dbo-runner`) and its own substrate
(`dbo-stream`). Both already hand a body to `LaneVerbService`, which sits in
an exported package named for HTTP. Proposed: publish it as a transport SPI
in a neutral package. A transport hands the store an authenticated caller
and a verb; the store authenticates (tenant credential or enrolled signing
key), authorises (claimant and hold rules, step entitlements) and dispatches.
A consumer can then ship its own transport, a streaming wire for remote
edges for instance, and the store still decides who may ask what. For HTTP:
keep it as the reference remote-worker transport, or remove it.
Recommendation: keep it. The Spring worker assembly, the samples' edge
profile, the separated-mode check and the guide depend on it, and it is the
second implementation that proves the SPI.

**C. Appliance replication** (`dbo-sync` `HttpLanes`, one tenant in two
places): keep it in the store as a neutral mechanism with neutral wording,
or move it to consumers. Recommendation: keep it.

**D. Neutral wording.** Edge, appliance, analyser and driver vocabulary in
the store's comments and docs, about 120 Java files and 100 pages, is
reworded to worker, participant, router and routee, or kept only as clearly
neutral examples. The mechanisms stay. Recommendation: reword, file by file
as each is touched, with one sweep at the end.

## Consequences

Added: the `heartbeat` verb on every transport; an in-memory tracker per
node, only for steps that have a listener; the listener contract, registered
as a Spring bean in the assemblies and as an OSGi service in the container.

Removed: the trackable's state, attestation and departure fields and the
reads over them.

Cost: one verb every transport must carry; memory per node proportional to
the workers of listened steps; a timer per listener threshold; and a
consumer that wants history must write it, as work. Presence that two nodes
disagree on is the consumer's to reconcile.

Promises that change: `PROC_PRESENCE_IS_DERIVED` says *no heartbeat and no
lease*. Its cursor rule still decides resolution, and the sentence about
heartbeats is rewritten. `PROC_A_TRACKABLE_MAY_ROUTE_OTHERS`,
`PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB` and
`PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT` narrow to the routee edge or retire.
How per-declaration vitals (`PROC_RUNNER_DECLARES_ITS_VITALS`) relate to
heartbeat statistics is left open here.

## The promises it would add

- `PROC_A_PRESENCE_LISTENER_IS_OPTIONAL_PER_STEP`
- `PROC_A_PRESENCE_LISTENER_DECLARES_ITS_SILENCE`
- `PROC_A_HEARTBEAT_IS_A_LANE_VERB`
- `PROC_HEARTBEAT_STATISTICS_ARE_OPAQUE_AND_BOUNDED`
- `PROC_A_NODE_START_RESETS_PRESENCE`
- `PROC_PRESENCE_IS_RECORDED_ONLY_THROUGH_WORK`

Proved as legs on Rowling Land: a worker heartbeats with nested statistics,
the listener sees it appear and starts a keyed task, and the task's step
writes a record; the worker falls silent and `unknown` fires at the declared
threshold; a node restart sends `reset`; an oversized heartbeat is refused
with the limit named; a listener with no threshold stops startup.

## Build order

1. The listener contract and the startup refusal, with no events yet.
2. The in-memory tracker fed by poll, claim, checkpoint and close.
3. `reset` at node start.
4. The `heartbeat` verb on the lane, both transports and the verb count.
5. The statistics cap and the `dbo.` reservation.
6. Spring and OSGi registration, and the recording task in the sample.
7. Trim trackables to the routee edge.
8. The promise rewrites above.

## Deliberately not done

No presence storage. No freshness default. No device vocabulary. No
interpretation of statistics.
