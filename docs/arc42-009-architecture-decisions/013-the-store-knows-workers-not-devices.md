**Status: Built.** Reflected in [participants](../arc42-008-crosscutting/processes-and-work/participants.md) and the guide's [work leaves and comes back](../guide/work-leaves-and-comes-back.md).

# The store knows workers, not devices

A platform built on the store binds, connects, updates and drives devices.
The trackable record leaned towards the store knowing some of that: a kind,
a state and an attestation, written so a face could project it onto FHIR
`Device`.

**Direction: the store has no device semantics. It tells an application
when it is in contact with the workers of a step, and the application
decides what to record.**

## No device semantics

To the store, an edge is a step executor and nothing more. A step performed
on an edge covers whatever subsystem sits behind it, and the store neither
knows nor asks what that is. Routers and routees stay as neutral participant
concepts, because sealing past a router needs them. It never projects anything onto `Device`. That
answers the `Device` projection named under the devices domain in the
[IHE profiles item](https://github.com/jengu-net/dbo/blob/main/docs/arc42-011-risks-and-technical-debt/008-ihe-profiles/README.md):
a consumer keeps its own `Device` records, and writes them through work.
A run held by a program renders its `Task.owner` as a logical `Device`
reference by the executor's identifier — the store's executor system,
`urn:dbo:executor`, and the executor's name — with no `Device` stored,
because that is FHIR's spelling of an executor; an application that keeps a
`Device` for that worker gives it the same identifier.

## Presence and contact are two words

**Presence** stays what it is: derived from a participant's feed cursor, and
read by resolution to decide whether a declaration is offered work. It
stores nothing new and has no heartbeat.

**Contact** is the application's: whether a node has heard a worker for a
step within a threshold the application chose. It decides nothing in the
store. A worker can be present and out of contact, or the reverse.

**Contact is noticed, not stored.** An application may register, per step,
a contact listener. With none registered the store tracks nothing for the
step, and it costs nothing. A registration must declare its silence
threshold. There is no default, and a registration without one is refused at
startup, naming the listener. The threshold is the listener's because the
store imposes no freshness rule of its own; each hop owns liveness for the
hop below it. Two listeners on one step may declare different thresholds.

**The events.**

- `appeared(tenant, step, worker, node, since, statistics?)`: the first
  activity after unknown.
- `statistics`: each heartbeat's statistics while in contact.
- `unknown(tenant, step, worker, node, lastSeen)`: no activity within this
  listener's threshold.
- `reset(step, node)`: sent to every listener when a node starts, meaning
  everything for this step is unknown on this node and must appear again.

*Unknown*, not *gone*: after silence or a restart the store does not know.

**Activity** is any request from that worker for that step. A poll counts
for each step it names; a claim, checkpoint or close counts for its run's
step; a heartbeat counts for every step the worker has declared on that
lane. **A worker** is its client id plus executor name and version.

**Heartbeat is a lane verb**, `heartbeat(statistics)`, on every transport. A
worker on the substrate is woken rather than polling, and a worker holding a
long claim may say nothing for a long time, so contact read only from
ordinary requests would lose exactly those workers. The lane goes from 22
verbs to 23, and `ALaneStaysTransportShapedTest` records the reason. A
heartbeat writes nothing to the database; it updates the node's contact and
calls the listeners.

**Statistics are an open, structured document**: one JSON object per
heartbeat, nesting allowed, so a worker's own counters and the state of
each thing it routes arrive in one event. The store neither interprets,
validates nor stores it. Top-level keys are namespaced by contributor; the
store reserves `dbo.`. A configurable cap, 64 KB by default, is enforced, and
an oversized heartbeat is refused with the limit named. Statistics carry no
personal data. The store cannot check that, so it is a rule for senders:
heartbeats travel authenticated but outside sealed work, and anything about
a person travels only as sealed work in a run.

**Vitals fold into statistics.** The runner's per-step counters (performed,
failed, mean duration, last error) leave the declaration's `metadata` and
travel under `dbo.runner`, keyed by step. The store reads none of it; the
prefix only stops a contributor from colliding with the runner. A
declaration is re-sent when it changes, no longer on every cycle as a sign
of life. The runner's last error travels as its reason, under the same
no-personal-data rule.

**The listener alone decides what is recorded.** Wanting something stored,
it starts a task through the ordinary work path with an idempotent run key,
such as worker, step, transition and time bucket. That task's step writes
the consumer's records through its result (`writing(...)`), so every
recorded change carries provenance and passes the tenant's validation.
Nothing about contact is in the database unless such a task wrote it. A
Spring listener starts tasks through `DboInitiator`. A plain OSGi listener
gets the same contract as `RunInitiator`, a framework-free service in core
that starts a run on a tenant and hears back how it ended; `DboInitiator`
stays the Spring binding of it.

**Several nodes may disagree.** Contact is in memory per node. Each event
names its node, and the consumer decides what contact means across nodes,
for instance heard by any node recently. Its run keys collapse duplicate
decisions. Central contact in the database was rejected: it brings back a
write per heartbeat.

## Trackables shrink to the routee edge

`routes(...)` and the trackable record overlap with heartbeat statistics for
"what is behind me". Sealing uses one fact from them:
`Lane.sealed(run, recipients)` refuses a recipient not `routedBy` the
asking participant. The routee's key is not on the trackable; it is the
routee's enrolment key, found through `Lane.Keys`. So a trackable keeps `id`
and `routedBy`. It loses `kind`, `state`, `attested`, `unreported` and the
departed reading, with the reads over them: `observedBy`, `subtree` and the
fleet door's state answers. Routed things' live state travels in the
router's statistics.

Today a router can seal to a routee it has dropped from its report, because
the check does not exclude departed rows. With departure gone, *dropped*
means absent from the router's latest `routes` report, and the build removes
the edge then, so the seal is refused.

## Transports

**A published server-side transport SPI, in two layers**, with HTTP kept as
the reference transport.

1. **Verb dispatch.** A transport hands the store a caller and a verb. The
   store authenticates the caller, by tenant credential or enrolled signing
   key, authorises it (claimant and hold rules, step entitlements) and
   dispatches through the shared verb service. That is `LaneVerbService`
   today, which both HTTP and the stream already use, moved from
   `cloud.jengu.dbo.runner.http` to a neutral package.
2. **Stream carrier.** The stream protocol is a signed ask delivered to a
   tenant's door, the answer returned keyed by the ask, large answers held
   until collected, and wake-ups that say *look again*. Today it rides the
   substrate: a durable workflow per tenant, messages and events, spill, and
   the substrate's notifications. The carrier becomes an SPI under the
   protocol, with the substrate as one implementation, so a consumer can
   carry the same protocol over a WebSocket. On the worker side the lane
   already rests on `WireLane.Transport` and `Wakeups`.

The store keeps deciding authentication, authorisation, the signature over
the ask's bytes, sealing, and the feed's cursors. A carrier carries opaque
signed asks, sealed payloads and wake-ups, reads none of them, and can delay
or drop an ask but cannot widen one. HTTP stays because the Spring worker
assembly, the samples' edge profile, the separated-mode check and the guide
depend on it, and it is the second implementation that proves the SPI.

**Appliance replication** (`dbo-sync`'s `HttpLanes`, one tenant in two
places) stays in the store as a neutral mechanism.

**Wording.** Edge, appliance, analyser and driver vocabulary in the store's
comments and docs is reworded to worker, participant, router and routee, or
kept only as a clearly neutral example. The mechanisms are unchanged.

## Consequences

Added: the `heartbeat` verb; an in-memory contact tracker per node, only for
steps with a listener; the listener contract, registered as a Spring bean
and as an OSGi service; `RunInitiator`; the two transport SPIs.

Removed: the trackable's state, attestation and departure fields; vitals on
the declaration.

Cost: one more verb per transport; memory per node for listened workers; a
timer per threshold; history only where a consumer writes it, as work.

Promises that change: `PROC_PRESENCE_IS_DERIVED` keeps its rule, and its
*no heartbeat and no lease* sentence is scoped to resolution.
`PROC_RUNNER_DECLARES_ITS_VITALS` becomes
`PROC_THE_RUNNER_REPORTS_ITS_COUNTS_IN_ITS_HEARTBEAT`.
`PROC_A_TRACKABLE_MAY_ROUTE_OTHERS` and
`PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB` narrow to the routee edge, and
`PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT` retires.

## Promises it adds

- `PROC_A_CONTACT_LISTENER_IS_OPTIONAL_PER_STEP`
- `PROC_A_CONTACT_LISTENER_DECLARES_ITS_SILENCE`
- `PROC_A_HEARTBEAT_IS_A_LANE_VERB`
- `PROC_HEARTBEAT_STATISTICS_ARE_OPAQUE_AND_BOUNDED`
- `PROC_A_NODE_START_RESETS_CONTACT`
- `PROC_CONTACT_IS_RECORDED_ONLY_THROUGH_WORK`
- `PROC_A_DROPPED_ROUTEE_IS_NOT_SEALED_TO`
- `PROC_A_STREAM_RIDES_ANY_CARRIER`

Proved as legs on Rowling Land: a worker heartbeats with nested statistics,
the listener sees it appear and starts a keyed task, and the task's step
writes a record; the worker falls silent and `unknown` fires at the declared
threshold; a node restart sends `reset`; an oversized heartbeat is refused;
a listener with no threshold stops startup; a router's dropped routee is
refused a seal.

## Build order

1. The listener contract and the startup refusal.
2. The contact tracker fed by poll, claim, checkpoint and close; `reset`.
3. The `heartbeat` verb on both transports, the verb count, the cap.
4. Vitals into `dbo.runner`; the declaration's `metadata` goes.
5. Spring and OSGi registration; `RunInitiator`; the recording task in the
   sample.
6. Trackables trimmed to the routee edge; a dropped routee is not sealed to.
7. `LaneVerbService` to a neutral package, published as the dispatch SPI.
8. The stream carrier SPI, with the substrate as its first implementation.
9. Neutral wording, file by file, then one sweep.
10. The promise rewrites above.

## Deliberately not done

No contact storage. No freshness default. No device vocabulary. No
interpretation of statistics.

## Deviations as built

- **A contributor interface.** The record says statistics are namespaced by
  contributor and does not say how a contributor contributes. Built as
  `HeartbeatStatistics` in the runner: a service in a container, a bean under
  the Spring worker, asked once per heartbeat and put under its namespace. A
  contributor naming `dbo.` is refused when it is registered, and a Spring
  bean at refresh.
- **The startup refusal is made twice.** A Spring bean without a silence stops
  the context at refresh, by its class name; a plain OSGi service is refused
  by the tenant bundle's whiteboard, by name, into the log. Both use one
  sentence.
- **Activity on a run's verb counts once the verb is answered.** A claim,
  checkpoint, milestone, release, close or committed result counts for its
  run's step as the store holds the run, never as the asker's body names it;
  a refused verb counts for nothing, since the store has not checked it
  concerns that worker's run. A poll still counts for each step it names, in
  the bare form a poll uses, so a bare name shared by two steps counts for
  both.
- **The one-way verb went.** The runner told its declarations rather than
  asking, because it re-said them every cycle. Said once, a declaration has
  to know it landed, so `WireLane.Transport.tell` is removed, every verb is
  asked, and the runner says a declaration again only when it changed or did
  not land.
- **The idempotent run key is the step door's run scope.** A run's key was
  already `<step>/<scope>` and the step door already found a run by it, so
  `RunInitiator` takes the caller's key as that scope rather than adding a
  second key.
- **The in-process initiator is per tenant with steps, not per door.** It is
  mounted for every tenant that declares steps, whether or not it has an
  authority, and records no requester: the caller is the tenant's own
  process.
- **The fleet door lost every trackable answer**, not only the state ones:
  `behind`, `trackable` and `all` answered rows whose only remaining field is
  the edge sealing needs, and nothing outside the store asked for them. The
  door answers `runs`.
- **A routee reported with nobody in front of it sits behind the reporter.**
  The report is the whole set, so a dropped routee's own routees go with it.
  Dropped edges are deleted rather than kept, since nothing is left to say
  about them.
- **The vitals promise was renamed with the behaviour, in step 4**, rather
  than in step 10, because the test that proved it changed then.
- **The dispatch SPI publishes the tenant's verbs per tenant.** A
  `LaneVerbService` per tenant is registered as a service with the tenant's
  code and read through `DboTenants.verbs`, so a host carrying verbs over a
  transport of its own can reach it. The access types left `LaneHandler` for
  the neutral package: `Access`, `Grants`, `SignedGrants` and `Lanes`.
- **The carrier SPI is in the runner's neutral package and the protocol stays
  in the stream bundle.** `StreamCarrier` sits beside the dispatch SPI, so a
  host implements it without the substrate's library; `StreamDoor` and
  `StreamLane` keep the protocol, and `SubstrateCarrier` is the substrate's
  implementation. A carrier registered on the whiteboard opens a door for
  every tenant that has verbs, without the substrate's "somebody can ask"
  condition, which exists to save the substrate a workflow per tenant. The
  door also refuses an ask delivered under an id it does not carry, so a
  carrier cannot answer one asker with another's verb.
- **The wording reached identifiers where nothing outside the repository reads
  them.** The story is `US-DBO-WORK-ROUNDTRIP`, the promise
  `PROC_MIRRORED_RUNS_ARE_FILED_BY_SOURCE`, four harness classes and several
  story legs were renamed, and the sample bundle is `samples/ward-bundle`.
  Kept as spelled: stored and wire names (the audit entry's `appliance`
  field, the `edgePin` factor and its route), the worker sample's `edge`
  profile, and the diagram sources, whose drawings are regenerated by a tool
  this build did not have; their captions still say appliance.
- **A run's holder is still rendered as a FHIR `Device` in a task's owner.**
  That is the face writing FHIR's word for a machine that holds a task, not
  the store knowing what the machine is, and changing it would change every
  tenant's tasks; the record's "never projects onto `Device`" is read as about
  routees.
- **A carrier needed nothing more from the SPI to run over a real socket.**
  The samples carry the stream over a WebSocket — a door on the clinic's
  servlet container and an asker on the JDK's client — against
  `StreamCarrier` as published. Two things are the carrier's own and the SPI
  leaves them so: a socket that drops is reconnected on the next ask, and a
  door with no tenant open refuses the connection, which the asker reports as
  the store being unreachable. Neither needed the store to know.
