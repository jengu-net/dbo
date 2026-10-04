# US-DBO-FLEET-HEALTH — the store tells the application who it hears, and the application decides what that means

> Ines can see, right now, that one of the workers behind a practice's
> synchronisation service has been quiet for nine minutes and that the
> service itself is fine. She did not build a monitoring system to learn
> that, and the store did not decide for her what nine minutes means.
>
> Her synchronisation service is a participant: it holds a lane, declares
> what it performs and says, in each heartbeat, how it and everything it
> routes are doing. The store hears it and tells her application. What is
> kept, and for how long, is her application's to say.

## The scene

The fleet is several deep and the depths are not alike:

- the **synchronisation service**, a participant with a lane of its own;
- the **workers it routes**, which hold no lane and are reached through it;
- a **worker behind one of those**, one hop further down.

A routee can be a router. That is the ordinary shape, and it is why the store
keeps one edge per routee: the participant it sits behind, which is what lets
the service hold the claim on work it cannot read and seal it past itself to
the routee that can.

## Reporting is one hop, always

Nothing reports about something it did not reach. The service says whom it
routes; a routee that routes says whom it routes. The store takes the reporter
from the lane the report came on rather than from the report, so a router
cannot report routees for somebody else, and a report is the whole set: a
routee left out is no longer behind the router, and nothing is sealed to it
until a report names it again.

How each routee is doing is not an edge. It travels in the router's heartbeat
statistics — one document, nested as deep as the router likes, under a
namespace of its own — and the store reads none of it.

## Presence and contact are two words

A participant is **present** while its cursor moves, and presence is what the
store reads when it decides whether a declaration is offered work. A component
saying it is healthy is exactly what a stuck component keeps saying, so nothing
a participant says about itself supplies presence.

**Contact** is the application's: whether a node has heard a worker for a step
within a silence the application chose. Ines's application listens for the
step her service performs, is told when the service appears, what each
heartbeat says while it is heard, and when it has been quiet past her
threshold. A node that starts tells her everything about the step is unknown
there. What she keeps of it she asks for as work, which is what makes it a
record of her tenant's like any other.

## Nothing is filtered for being stale

The store never decides that a silence is too long, because how long is too
long depends on the cadence of the hop — a service polled every five minutes
and a worker that answers once a day are not the same silence, and only Ines
knows which is which. Her listener names its threshold, and there is no
default.

## Asking

An operator credential reads the tenants' work from outside every container.
A participation credential cannot — what a participant may *do* and what a
deployment may *ask about every participant* are different questions, and a
participant that could ask would be reading about the ones beside it.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-OPS-RUNTIME-SAYS-WHAT-IT-SERVES` | A runtime can be asked which tenants it is serving, and what it is doing about the ones it is not: serving, coming up, failed to come up — one state per tenant it has been told about. The answer comes from runtime state, never from re-reading the declarations, so a caller comparing the two can find a disagreement rather than confirming its own writes. Cross-tenant, so no tenant credential buys it. | PROVEN |
| `REQ-DBO-PROC-A-NODE-ANSWERS-ITS-CATALOGUE` | A node says what it knows how to do — the steps installed in it and the steps a participant introduced over a lane, each with the party that contributed it, in which version, and which executor would take it here now. It answers while serving no tenant at all, because the catalogue is what is installed rather than what is running, and a node that has stopped serving is exactly when somebody asks. | PROVEN |
| `REQ-DBO-PROC-NETWORK-MAP` | A deployment answers what its nodes have installed between them, and in which versions — one answer rather than a walk. What each node has is descriptive, an inventory of that node, and never a second declaration of a step somebody else already declared. | PROVEN |
| `REQ-DBO-OPS-FLEET-IS-READ-FROM-OUTSIDE` | A deployment is read from one process outside every container, over the doors its nodes and tenants already serve: a node is asked what it serves and what it has installed under the deployment's own token, and a tenant is asked about its work under a credential its own authority minted, so the reader holds one credential per tenant and is never handed a surface that crosses them. Every answer is labelled with the node it came from, nothing is copied, and a node that does not answer or a tenant the reader holds no credential for is in the reading as such rather than missing from it. | PROVEN |
| `REQ-DBO-OPS-FLEET-IS-ACTED-ON-THROUGH-THE-LANE` | The process that reads a deployment can also act on it, and only through the doors a participant uses: it holds a supervisory credential per tenant, granted separately from the one it reads with and usually not granted at all, and posts the tenant's own lane verb — so every rule about the act is the tenant's and is met on the way in. Looking must not carry the authority to overturn work, so a reader given no supervisory credential is read-only by construction, and where the reader is a service its act surface is not mounted at all unless the deployment named a second token for it. An act says which node carried it, and one that did not happen says why rather than passing quietly. | PROVEN |
| `REQ-DBO-PROC-THE-RUNNER-REPORTS-ITS-COUNTS-IN-ITS-HEARTBEAT` | The runner reports its counts per step — performed, failed, mean duration and the last failure's reason — in each heartbeat under dbo.runner, beside what the worker's own contributors add under namespaces of their own; a contributor claiming dbo. is refused. A declaration carries no counts and is said when it changes or did not land, never as a sign of life; presence stays derived from the cursor. | PROVEN |
| `REQ-DBO-PROC-PRESENCE-IS-DERIVED` | A participant is present while its named feed cursor moves; a declaration whose consumer is behind and unmoving is declared-but-not-present, skipped by resolution and shown as such. Resolution reads no heartbeat and no lease — a heartbeat is contact, which is the application's and decides nothing here — and a caught-up participant's cursor does not move either, so silence with nothing waiting is not absence. | PROVEN |
| `REQ-DBO-PROC-A-TRACKABLE-MAY-ROUTE-OTHERS` | A participant may route others, and the store keeps one fact about each routee: the participant it sits behind, at any depth. That edge is what lets a router seal work past itself to a routee; what a routee is and how it is doing are the router's to say in its heartbeat, and the store imposes no freshness rule on them. | PROVEN |
| `REQ-DBO-PROC-A-ROUTED-TREE-TRAVELS-AS-A-LANE-VERB` | A router reports whom it routes the way it reports what it can do: a verb of the participation lane, beside declare, carrying the whole set each time. The reporter is the lane's own participant rather than anything on the wire, so a routee named with nobody in front of it sits behind that participant and a router cannot report routees for somebody else. | PROVEN |
| `REQ-DBO-PROC-A-DROPPED-ROUTEE-IS-NOT-SEALED-TO` | A routee missing from its router's latest report is no longer behind it: the edge goes with the report that left it out, and the router may not seal work to it or carry home an opening in its name until a report names it again. | PROVEN |
| `REQ-DBO-PROC-A-HEARTBEAT-IS-A-LANE-VERB` | A worker says it is still there with a heartbeat, a verb of the participation lane on every transport, carrying one JSON object of statistics. It counts as activity for every step the worker declared on that lane, so a worker that is woken rather than polling, or one holding a long claim, stays in contact; it writes nothing to the tenant's records and never extends a claim. | PROVEN |
| `REQ-DBO-PROC-A-CONTACT-LISTENER-IS-OPTIONAL-PER-STEP` | An application may register, per step, a listener told when a node comes into contact with a worker of that step and when it loses it; with none registered the node tracks nothing for the step. Activity is any request from that worker for that step — a poll naming it, a claim, checkpoint, release or close of one of its runs, and a heartbeat for every step the worker declared — and a worker is its client, its executor's name and its version. Contact is held in memory on the node that heard the worker, decides nothing in the store, and every event names its node. | PROVEN |
| `REQ-DBO-PROC-A-CONTACT-LISTENER-DECLARES-ITS-SILENCE` | A contact listener declares how long a worker may say nothing before it is unknown, and there is no default: a listener declaring none is refused at startup, by name. A worker heard after it was unknown appears, each heartbeat while it is in contact delivers its statistics, and silence past the listener's own threshold makes it unknown — never gone. Two listeners on one step may declare different silences. | PROVEN |
| `REQ-DBO-PROC-NUMBERS-LEAVE-AS-LABELS-NEVER-AS-TEXT` | What a node reports about work leaves it as measurements labelled from a closed set — whose work, which process and step, what ran it, and how it ended as one word from a fixed vocabulary. A failure's own words stay on the run, in the store of the tenant whose work it was: an open field in a stream declared anonymous is how the declaration stops being true without anybody editing it. Identifiers a caller chose are not labels either, being unbounded, and neither is a correlation echoed from another system, because nobody here knows what is in it. | PROVEN |
| `REQ-DBO-PROC-REPORTING-RUNS-WHERE-NOTHING-COLLECTS` | A node emits whether or not anything is collecting: the discarding destination is the default rather than a fallback, and an exporter that cannot be loaded leaves the node serving and quiet. Emission that switched itself off without a collector would be a path exercised nowhere but in production, and a store that refused to run without a monitoring stack would have made observability a dependency of serving. | PROVEN |
| `REQ-DBO-OPS-NUMBERS-LEAVE-THE-NODE` | A deployment points the telemetry seam at its collector by configuration, never by code, and the node's numbers arrive there in the published protocol — counts as sums, levels as gauges, durations as histograms, labelled from the seam's own closed vocabulary. Reporting is not a dependency of serving: a collector that is absent, slow or refusing costs the caller nothing and is said once, and a node with no endpoint counts and sends nowhere. | PROVEN |
| `REQ-DBO-PROC-SUPERVISION-IS-ITS-OWN-ENTITLEMENT` | Undoing a judgment already made about work is reached through the lane like every other act, and by its own half of an entitlement. A credential that performs a step does not thereby overturn its closures — not even one that speaks for the whole tenant — and a credential that supervises takes no work. Both halves must admit the act: the entitlement names the step and the step declares the action, and a supervisor asked for a step it does not name, or for one whose declaration omits reopening, is refused by name rather than quietly doing nothing. | PROVEN |
| `REQ-DBO-PROC-CLOSED-CAN-BE-REOPENED` | A closed run, or one waiting for people, can be reopened — a deliberate, recorded act through the step's declared reopen action — making the run claimable again with the reason on the record, and saying whether automation may take it, instead of a second run invented to disagree with the first. | PROVEN |
| `REQ-DBO-TEN-A-PARTNER-MANAGES-TENANTS` | A partner is a tenant that manages other tenants, declared when the managed tenant is created. The relation says which tenants the partner may read at all; within each, the partner is a declared audience saying what of each — runs and their journey, never documents, purposes only if the managed tenant opts in. What the partner is shown is assembled outside the store: a store instance is one tenant's store, and no cross-tenant query is grown to serve a support desk. | PROVEN |
| `REQ-DBO-TEN-SERVED-FROM-WHAT-WAS-APPLIED` | A deployment serves the declarations that were applied, not a listing it takes itself — so a tenant stops being served because somebody withdrew it, never because a read went wrong. A source that cannot be read leaves the records standing, the tenants serving, and says in the ledger that it has stopped moving. A deployment with no managing tenant to hold records reads its source directly, because nothing can bootstrap out of a store it has not built yet. | PROVEN |
| `REQ-DBO-TEN-A-REFUSED-DECLARATION-IS-SAID-ONCE` | A declaration this deployment refused is reported by name with its reason, and a deployment that is not serving something it was told to serve does not answer as though it were. The refusal was already a card in front of a person, which is where it belongs; what it was not is visible, because a spec that will not parse never reaches bring-up and none of the reporting there fires — six tenants of seven reads exactly like six. Said once per declaration and reason, since the pass runs on every beat and a refusal repeated every few seconds is how a log stops being read; said again when the reason changes, because somebody fixing a file works through its problems one at a time. | PROVEN |
| `REQ-DBO-TEN-A-STALE-INDEX-IS-REMEMBERED-UNTIL-IT-IS-REBUILT` | A reindex that did not finish is remembered against the tenant and retried until it does. The feed's events are acknowledged before the rebuild runs — deliberately, so a broken profile is not re-read forever — which left a failed reindex with nothing to bring it back: the index stayed stale behind one warning, and a stale envelope does not slow a search down, it makes it miss, which reads as nobody here. The warning is said once rather than every round, because a log that repeats itself stops being read, and the recovery says so when it comes. | PROVEN |
| `REQ-DBO-TEN-AN-ACTIVITY-DECLARES-WHERE-IT-APPLIES` | A tenant publishes what it is as facts, and an activity states which tenants it is for rather than working it out where it runs: it declares a filter over those facts, or it applies to every tenant on purpose. An activity whose filter a tenant does not match is not performed for it — so provisioning that suits one kind of tenant cannot be applied to another by omission, which is the shape the failure took when subscription dispatching polled a record domain that a face root does not have. A filter that cannot be parsed is refused where it is registered, because one consulted later would match nothing in silence. | PROVEN |
| `REQ-DBO-TEN-A-DECLARED-SET-IS-APPLIED-AS-ONE-PASS` | Configuration a declarer holds — value sets, profiles, search parameters, whatever a loader keeps — is handed over and applied to a tenant as one recorded pass rather than posted a resource at a time: read, applied, and a card per declaration nobody could apply, naming it as the declarer names it. The declarer's own name for the set is echoed and never parsed, and nothing reaches back afterwards — whoever declared it re-evaluates against what the pass says, so the two sides never have to be up together. | PROVEN |
| `REQ-DBO-TEN-APPLYING-IS-ASKED-FOR-AND-RECORDED` | Applying what is declared can be asked for, and the ask is the whole of the interface: it opens the same pass the deployment runs on its own and answers with what that pass did, so there is no second entry point that applies without leaving a record. It is reached behind a scope of its own, granted separately and usually not granted at all, because changing what a tenant is, is not the same right as writing records into it. | PROVEN |
| `REQ-DBO-TEN-A-CHANGE-IS-NOT-A-RETRACTION` | A change a serving tenant can take is applied to it: what it only says about itself it takes where it stands, and what it is made of is rebuilt in place — its database, its lanes and their cursors kept, nothing recorded as withdrawn, and whoever streams from it wired again rather than left reading a pool that has closed. A declaration naming a face nothing serves is refused while the tenant is still running, because a change that cannot work should cost nothing. | PROVEN |
| `REQ-DBO-TEN-A-REDECLARATION-IS-NOTICED` | A serving tenant declared differently from what it was built from is noticed and classified, rather than read once at mount and never again: what can be absorbed while it serves, what has to be rebuilt in place, and what cannot be had at all while it serves — the last refused by name and never half-applied. Every field of a declaration is classified, so a change nobody thought about cannot pass as no change, and a deployment can be asked which of its tenants are serving something other than what somebody declared. | PROVEN |
| `REQ-DBO-TEN-A-SLOW-BRING-UP-HOLDS-UP-ONLY-ITSELF` | A tenant slow to come up — storage that is late, a schema another node is still writing — holds up nobody else. A tenant withdrawn meanwhile stops being served on the deployment's next beat, a tenant declared meanwhile is begun as soon as the node has room for it, rather than once every bring-up in front of it is done, and a serving tenant declared differently is rebuilt on that beat without waiting for room at all. | PROVEN |
| `REQ-DBO-TEN-A-DECLARATION-IS-A-RECORD` | What a deployment has been told to serve is records in the managing tenant, applied from whatever source declares them like any other configuration — so what is declared can be asked of the store rather than read off a node's disk, and a declaration that will not parse is a card naming the file rather than a line in a boot log. A deployment with no managing tenant records nothing and serves exactly as before: recording what is declared is not a condition of honouring it. | PROVEN |
| `REQ-DBO-TEN-A-DECLARATION-NAMES-ITS-REFERENT` | A declared record may name its referent by a conditional reference, including one another declaration in the same set creates: the set is applied to a fixed point rather than in the order it arrived, what is stored names the referent by id, and a reference nothing can answer fails that declaration by name rather than landing as a question. | PROVEN |
| `REQ-DBO-TEN-A-CHANGE-CAN-BE-CLASSIFIED-WITHOUT-APPLYING` | What a declaration would do to the tenants it names is answerable without doing it — hot, rebuilt in place, or refused with what it would need — on the door that applies it and under the same grant; nothing is applied and nothing is recorded, because a preview does not happen to the tenant. | PROVEN |
| `REQ-DBO-TEN-FAIRNESS-QUOTAS` | Planned — Per-tenant quotas and rate limits are first-class configuration, enforced at the serving pod. | PLANNED |
| `REQ-DBO-PROC-CONFIG-WITHDRAWAL-IS-DECLARED` | Only a read a source says is complete may withdraw what it no longer names, so a partial read and an unreadable one take nothing away. What is held is the applier's to answer and undoing is the applier's to do: one that cannot say withdraws nothing, and one that cannot undo makes a card rather than a silence — nothing is removed by machinery that was never told how to remove it. | PROVEN |
| `REQ-DBO-PROC-CONFIG-READ-FROM-A-SOURCE` | Configuration is read from a declared source — a repository, a mounted directory, a lane — and what a scope last agreed with is recorded on its own run, so an unchanged source is a read rather than a re-application, and a scope with a card open is re-applied until the card closes. A source that cannot be read says so: it never answers with an empty set, because empty and unreachable are the same sentence to whoever then has to decide what is missing. | PROVEN |
| `REQ-DBO-PROC-CONFIG-APPLIES-AS-A-SWEEP` | Applying a declared set is a sweep: it closes when what is here agrees with what was declared, one declaration nobody can apply is a card naming it and the rest still apply, and the pass tallies what it read, applied and skipped. The store's own bring-up configuration goes through it too — a partial application whose only account is a log line is what presents to whoever declared it as 'my configuration had no effect'. | PROVEN |
| `REQ-DBO-SCAL-DURABLE-ASSIGNMENT` | Planned — The tenant→pod assignment is durable state with version-driven takeover. | PLANNED |
| `REQ-DBO-SCAL-SINGLE-WRITER-TENANT` | Planned — A tenant's serving pod is its single writer, making local caching and local subscription state correct by construction. | PLANNED |
| `REQ-DBO-SCAL-TRANSPARENT-ROUTING` | Planned — Callers look up a tenant's service in the registry; local instance or remote proxy is indistinguishable. | PLANNED |
| `REQ-DBO-SCAL-TWO-HOP-LOCALITY` | Planned — Requests enter at the closest public node (Kubernetes locality), then route to the serving pod (tenant assignment). | PLANNED |
| `REQ-DBO-SCAL-NO-SHARED-STATE-BROKER` | Planned — The architecture requires no Redis-class shared-state service. | PLANNED |
| `REQ-DBO-OPS-MIGRATION-AS-DEPLOYMENT` | Planned — Schema and engine upgrades ride rolling deployment: the highest-version node leads, migrates, and older nodes passivate. (D5) | PLANNED |

Coverage: {PROVEN=34, PLANNED=7} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Contact is per node.** Each node hears its own workers and says so; what
  contact means across several nodes — heard by any of them recently, say —
  is the application's to decide, and its run keys collapse the duplicates.
- **No history.** The store keeps no account of contact or of statistics; what
  an application wants kept, it writes through work.
- **Metrics are the participant's own.** Statistics are a document per
  heartbeat, not a measurement series. Anything that wants trends needs a
  collector beside the store rather than inside it.

## Decided in review

- **The store knows workers, not what they are.** A routee is a participant
  some router reaches, and the store keeps only the edge sealing needs. What a
  routee is, and its state, travel in the router's statistics; an application
  that wants them as records writes them through work.
