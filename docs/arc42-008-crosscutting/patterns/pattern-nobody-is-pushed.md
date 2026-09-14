---
title: "Nobody Is Pushed"
eyebrow: Pattern
standfirst: >-
  The store never reaches out to the laboratory, the border post or the
  supplier's system. Participants ask what is available to them, take it, and
  report back.
pattern: 6
template: essay.html
---

**Intent — stop the store's ability to distribute work from depending on
whether it can reach the places that do the work.**

## You are

Distributing work to places you do not control. An instrument in a laboratory.
A handheld at a border. A supplier's own system, run by a company that is a
competitor of the next supplier along. Some sit behind a router with no public
address. Some are switched off from Friday evening. Some belong to
organisations whose security teams will not open a port towards you, and are
right not to.

## The question

How does work reach a participant you cannot call?

## The forces

- Pushing requires an address, a route, and somebody willing to accept
  connections from you — three things that are somebody else's decision.
- Retrying a push at an unreachable participant turns an ordinary absence into
  an operational alarm.
- Holding a queue open per participant means the store's memory of what is
  outstanding grows with the number of places that are asleep.

## Therefore

**Invert the direction. Let participants ask.**

--8<-- "assets/diagrams/pattern-nobody-is-pushed.svg"

<p class="diagram-caption">The three places differ in every way except the one
that matters: none of them can be called, and none of them needs to be.</p>

Being unreachable stops being a failure mode and becomes an ordinary state. An
appliance that spent the weekend offline is not behind in a way anybody has to
handle; it asks on Monday and takes what is waiting. An organisation that
never opens anything inbound participates on the same terms as one that does.

The same inversion is what lets the feed serve four different consumers
identically, because a consumer that asks is a consumer with a position, and a
position is a thing you can read.

## What each reader gets

- **A regulator** sees no standing outbound path from the store into a
  clinical network.
- **A security officer** has no inbound rule to justify at the participant's
  firewall, and no callback endpoint to defend.
- **An administrator** stops treating an offline site as an incident, and
  reads how far behind it is instead.
- **The business** can onboard a partner who will not open their network,
  which is most partners worth having.

## Relations

- **Builds on** — [Work Is the Reason](pattern-work-is-the-reason.md).
- **Makes possible** — [Two Parties Bound the Claim](pattern-two-parties-bound-the-claim.md); [Falls to a Person, and Is Counted](pattern-falls-to-a-person-and-is-counted.md); [One Feed, Every Consumer](pattern-one-feed-every-consumer.md).
- **Composed of** — [Polling Consumer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PollingConsumer.html) and [Competing Consumers](https://www.enterpriseintegrationpatterns.com/patterns/messaging/CompetingConsumers.html), from [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/).
- **Related work**
    - The [workflow resource patterns](http://www.workflowpatterns.com/patterns/resource/), where resource-initiated allocation is the participant pulling rather than the engine assigning.
