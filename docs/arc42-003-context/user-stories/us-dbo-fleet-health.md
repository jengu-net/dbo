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

| Leg | Promised by |
|---|---|
| A trackable may route others, one shape at every depth | `REQ-DBO-PROC-A-TRACKABLE-MAY-ROUTE-OTHERS` |
| Reports arrive as a verb of the lane, and the observer is stamped rather than sent | `REQ-DBO-PROC-A-ROUTED-TREE-TRAVELS-AS-A-LANE-VERB` |
| Presence is derived from a moving cursor; vitals never supply it | `REQ-DBO-PROC-PRESENCE-IS-DERIVED` |
| A runner signs what it reports about itself | `REQ-DBO-PROC-RUNNER-SIGNS-ITS-VITALS` |
| The tree is readable from outside the container, with its own scope | `REQ-DBO-PROC-A-ROUTED-TREE-TRAVELS-AS-A-LANE-VERB` |
| A participant declares itself rather than being configured in | `REQ-DBO-PROC-STEP-DECLARES-ITSELF` |

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
