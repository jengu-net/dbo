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
| `REQ-DBO-PROC-A-NODE-ANSWERS-ITS-CATALOGUE` | A node says what it knows how to do — the steps installed in it and the steps a linked participant introduced, each with the party that contributed it, in which version, and which executor would take it here now. It answers while serving no tenant at all, because the catalogue is what is installed rather than what is running, and a node that has stopped serving is exactly when somebody asks. | PROVEN |
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

Coverage: {PROVEN=16} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
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
