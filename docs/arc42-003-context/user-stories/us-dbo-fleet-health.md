# US-DBO-FLEET-HEALTH — the store holds the shape of the fleet, so nobody builds a second copy of it

> Ines can see, right now, that the analyser in one practice has been
> quiet for nine minutes and that the bench it sits behind is fine. She
> did not build a monitoring system to learn that.
>
> Her synchronisation service reports what is behind it. The benches
> report what is behind them. Each layer reports one hop, and the store
> assembles the tree — so an operator asks the store what is under a
> connector and gets the whole depth of it, one shape at every level.

## The scene

The fleet is three deep and the depths are not alike:

- the **synchronisation service**, which reports for itself because its own
  cursor moves;
- a **bench** in each practice, which cannot report for itself and is
  attested by the service that last saw it;
- an **analyser** behind the bench, attested the same way one hop further
  down.

A routee can be a router. That is not an edge case here — it is the ordinary
shape, and it is why the tree is a tree rather than a list.

## Reporting is one hop, always

Nothing reports about a thing it did not see. The service says what is behind
*it*; the bench says what is behind *it*. Neither claims to know the state of
something two hops away, because the only honest attestation is from whoever
last spoke to the thing.

The store stamps the observer from the reporting participant rather than
taking its word for who it is, so a router cannot attest as somebody else.

## Presence is derived, not declared

A participant that reports for itself is present while its cursor moves. That
is deliberate and it is the part builders find surprising: **a component
saying it is healthy is exactly what a stuck component keeps saying.** Vitals
annotate presence; they never supply it.

For something that cannot report for itself, presence is what the last hop
attested — who saw it, and when. The store does not decide what that means.

## Nothing is filtered for being stale

Ines's operator page shows the nine-minute silence and lets her judge it. The
store never drops a row for being old, because how long is too long depends on
the cadence of the hop that reports it — a bench polled every five minutes and
an analyser polled every thirty are not the same silence, and only Ines knows
which is which.

## Asking

An operator credential reads the tree from outside the container: what is
behind this connector, what did this reporter attest, what is the whole
subtree. A participation credential cannot — what a bench may *do* and what a
deployment may *ask about every bench* are different questions, and a bench
that could ask would be reading about benches beside it.

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
| `REQ-DBO-PROC-RUNNER-DECLARES-ITS-VITALS` | The runner re-declares each service with an extensible metadata block, replaced never accumulated; presence stays derived from the cursor, and vitals annotate it. | PROVEN |
| `REQ-DBO-PROC-PRESENCE-IS-DERIVED` | A participant is present while its named feed cursor moves; a declaration whose consumer is behind and unmoving is declared-but-not-present, skipped by resolution and shown as such. No heartbeat and no lease — and a caught-up participant's cursor does not move either, so silence with nothing waiting is not absence. | PROVEN |
| `REQ-DBO-PROC-A-TRACKABLE-MAY-ROUTE-OTHERS` | Something whose state is worth knowing is one record at any depth, and a connected worker may route others: it reports the state of what sits behind it, to arbitrary depth, normalised per trackable so the rule exists once rather than once per router. Presence stays derived where there is a cursor and is attested where there is not, the attestation naming the worker that saw it rather than the parent it sits behind. The store imposes no freshness rule on what a router reports: it has no path of its own to ask, and one threshold across a serial line and a socket would be wrong for both. | PROVEN |
| `REQ-DBO-PROC-A-ROUTED-TREE-TRAVELS-AS-A-LANE-VERB` | A participant reports what it can reach the way it reports what it can do: a verb of the participation lane, beside declare. The observer is stamped from the lane's own participant rather than carried on the wire, so a router cannot attest as somebody else. Vitals do not carry it, because vitals ride a declaration and a declaration is keyed per step — a router declaring two steps would carry one fleet twice, and withdrawing either would drop half of it. | PROVEN |
| `REQ-DBO-PROC-A-DEPARTED-ROUTEE-IS-A-STATEMENT` | A routee missing from a router's report is something the router said, not a gap — distinguishable from a quiet router because the cursor moved. A departed routee is kept with its last attestation and marked no longer reported, so gone reads as last seen by X at T, absent from X's report at T+1. No freshness rule comes with it. | PROVEN |
| `REQ-DBO-PROC-NUMBERS-LEAVE-AS-LABELS-NEVER-AS-TEXT` | What a node reports about work leaves it as measurements labelled from a closed set — whose work, which process and step, what ran it, and how it ended as one word from a fixed vocabulary. A failure's own words stay on the run, in the store of the tenant whose work it was: an open field in a stream declared anonymous is how the declaration stops being true without anybody editing it. Identifiers a caller chose are not labels either, being unbounded, and neither is a correlation echoed from another system, because nobody here knows what is in it. | PROVEN |
| `REQ-DBO-PROC-REPORTING-RUNS-WHERE-NOTHING-COLLECTS` | A node emits whether or not anything is collecting: the discarding destination is the default rather than a fallback, and an exporter that cannot be loaded leaves the node serving and quiet. Emission that switched itself off without a collector would be a path exercised nowhere but in production, and a store that refused to run without a monitoring stack would have made observability a dependency of serving. | PROVEN |
| `REQ-DBO-OPS-NUMBERS-LEAVE-THE-NODE` | A deployment points the telemetry seam at its collector by configuration, never by code, and the node's numbers arrive there in the published protocol — counts as sums, levels as gauges, durations as histograms, labelled from the seam's own closed vocabulary. Reporting is not a dependency of serving: a collector that is absent, slow or refusing costs the caller nothing and is said once, and a node with no endpoint counts and sends nowhere. | PROVEN |
| `REQ-DBO-PROC-SUPERVISION-IS-ITS-OWN-ENTITLEMENT` | Undoing a judgment already made about work is reached through the lane like every other act, and by its own half of an entitlement. A credential that performs a step does not thereby overturn its closures — not even one that speaks for the whole tenant — and a credential that supervises takes no work. Both halves must admit the act: the entitlement names the step and the step declares the action, and a supervisor asked for a step it does not name, or for one whose declaration omits reopening, is refused by name rather than quietly doing nothing. | PROVEN |
| `REQ-DBO-PROC-CLOSED-CAN-BE-REOPENED` | A closed run can be reopened — a deliberate, recorded act through the step's declared reopen action — making the run claimable again with the reason on the record, instead of a second run invented to disagree with the first. | PROVEN |
| `REQ-DBO-TEN-A-PARTNER-MANAGES-TENANTS` | A partner is a tenant that manages other tenants, declared when the managed tenant is created. The relation says which tenants the partner may read at all; within each, the partner is a declared audience saying what of each — runs and their journey, never documents, purposes only if the managed tenant opts in. What the partner is shown is assembled outside the store: a store instance is one tenant's store, and no cross-tenant query is grown to serve a support desk. | PROVEN |
| `REQ-DBO-TEN-SERVED-FROM-WHAT-WAS-APPLIED` | A deployment serves the declarations that were applied, not a listing it takes itself — so a tenant stops being served because somebody withdrew it, never because a read went wrong. A source that cannot be read leaves the records standing, the tenants serving, and says in the ledger that it has stopped moving. A deployment with no managing tenant to hold records reads its source directly, because nothing can bootstrap out of a store it has not built yet. | PROVEN |
| `REQ-DBO-TEN-A-REFUSED-DECLARATION-IS-SAID-ONCE` | A declaration this deployment refused is reported by name with its reason, and a deployment that is not serving something it was told to serve does not answer as though it were. The refusal was already a card in front of a person, which is where it belongs; what it was not is visible, because a spec that will not parse never reaches bring-up and none of the reporting there fires — six tenants of seven reads exactly like six. Said once per declaration and reason, since the pass runs on every beat and a refusal repeated every few seconds is how a log stops being read; said again when the reason changes, because somebody fixing a file works through its problems one at a time. | PROVEN |
| `REQ-DBO-TEN-A-STALE-INDEX-IS-REMEMBERED-UNTIL-IT-IS-REBUILT` | A reindex that did not finish is remembered against the tenant and retried until it does. The feed's events are acknowledged before the rebuild runs — deliberately, so a broken profile is not re-read forever — which left a failed reindex with nothing to bring it back: the index stayed stale behind one warning, and a stale envelope does not slow a search down, it makes it miss, which reads as nobody here. The warning is said once rather than every round, because a log that repeats itself stops being read, and the recovery says so when it comes. | PROVEN |
| `REQ-DBO-TEN-AN-ACTIVITY-DECLARES-WHERE-IT-APPLIES` | A tenant publishes what it is as facts, and an activity states which tenants it is for rather than working it out where it runs: it declares a filter over those facts, or it applies to every tenant on purpose. An activity whose filter a tenant does not match is not performed for it — so provisioning that suits one kind of tenant cannot be applied to another by omission, which is the shape the failure took when subscription dispatching polled a record domain that a face root does not have. A filter that cannot be parsed is refused where it is registered, because one consulted later would match nothing in silence. | PROVEN |
| `REQ-DBO-TEN-A-DECLARED-SET-IS-APPLIED-AS-ONE-PASS` | Configuration a declarer holds — value sets, profiles, search parameters, whatever a loader keeps — is handed over and applied to a tenant as one recorded pass rather than posted a resource at a time: read, applied, and a card per declaration nobody could apply, naming it as the declarer names it. The declarer's own name for the set is echoed and never parsed, and nothing reaches back afterwards — whoever declared it re-evaluates against what the pass says, so the two sides never have to be up together. | PROVEN |
| `REQ-DBO-TEN-APPLYING-IS-ASKED-FOR-AND-RECORDED` | Applying what is declared can be asked for, and the ask is the whole of the interface: it opens the same pass the deployment runs on its own and answers with what that pass did, so there is no second entry point that applies without leaving a record. It is reached behind a scope of its own, granted separately and usually not granted at all, because changing what a tenant is, is not the same right as writing records into it. | PROVEN |
| `REQ-DBO-TEN-A-CHANGE-IS-NOT-A-RETRACTION` | A change a serving tenant can take is applied to it: what it only says about itself it takes where it stands, and what it is made of is rebuilt in place — its database, its lanes and their cursors kept, nothing recorded as withdrawn, and whoever streams from it wired again rather than left reading a pool that has closed. A declaration naming a face nothing serves is refused while the tenant is still running, because a change that cannot work should cost nothing. | PROVEN |
| `REQ-DBO-TEN-A-REDECLARATION-IS-NOTICED` | A serving tenant declared differently from what it was built from is noticed and classified, rather than read once at mount and never again: what can be absorbed while it serves, what has to be rebuilt in place, and what cannot be had at all while it serves — the last refused by name and never half-applied. Every field of a declaration is classified, so a change nobody thought about cannot pass as no change, and a deployment can be asked which of its tenants are serving something other than what somebody declared. | PROVEN |
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

Coverage: {PROVEN=30, PLANNED=7} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **No push.** The tree is polled; a change does not notify anybody. For a
  page that refreshes this is enough, and for an alert it is not.
- **No history.** State is replaced on each report rather than accumulated —
  deliberately, so a state record does not become a metrics history — so
  "when did this last change" is not a question the store answers.
- **Metrics are the participant's own.** The store carries a small opaque
  state map, not a measurement series. Anything that wants trends needs a
  collector beside the store rather than inside it.

## Decided in review

- **A routee that has gone is a statement.** A router reports the full set
  behind it, so a routee missing from that report is something the router
  said, distinguishable from a quiet router because the cursor moved. The
  one change: a departed routee is kept with its last attestation and marked
  no longer reported, rather than deleted — so "gone" reads as *last seen by
  X at T, absent from X's report at T+1*, which is a fact with a timestamp.
