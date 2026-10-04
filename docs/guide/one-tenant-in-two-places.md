---
title: One tenant in two places
eyebrow: Guide
standfirst: >-
  Definitions a tenant declares arrive from its zone by type, because none of
  them is about anybody. Patient data travels only with the work that names
  it, which is why a second place never slowly becomes a copy of the clinic.
template: essay.html
---

The clinic takes its canonical content from a national zone it does not run,
and it keeps a replica of itself in the building so that a lost connection
is an inconvenience rather than a closed practice. Both halves are content
that belongs somewhere else, arriving because somebody declared that it
should and staying legible about where it came from. What differs is the
bound. The scene is [US-DBO-TWO-PLACES](../arc42-003-context/user-stories/us-dbo-two-places.md),
walked by
[`OneTenantInTwoPlacesIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneTenantInTwoPlacesIT.java).

## What the clinic takes is what it declares

A dependency names the upstream and the types taken from it, and the runtime
wires the stream when the tenant comes up. St Jerome takes Rowling Land's code
systems and value sets, and the hospital's encounters
([its declaration](care-is-recorded.md#the-clinic-declares-its-work)). The story
opens a clinic that takes code systems from the zone and nothing else:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/OneTenantInTwoPlacesIT.java:dependency"
```

`handling` says who authors a type here: `replicated` content is authored
upstream and held as a copy this tenant cannot edit, and `operational` content
is the tenant's own ([tenants and faces](tenants-and-faces.md#handling)). A
clinic declared after the zone published catches up on everything the zone
held before it existed, because canonical content is older than the practices
that use it.

## What the store guarantees

- **A type the clinic did not declare brings nothing**, however much of it the
  zone holds, and dependencies are against the direct upstream only: chains
  compose hop by hop, so nobody inherits a relationship they did not agree to.
- **Any type travels at its own grain.** An encounter the hospital records
  arrives at St Jerome as the same record; a code system arrives whole and is
  taken apart into the clinic's own concepts, which it then answers from.
- **A copy says whose it is.** A streamed record names the upstream it came
  from and what governs it here; the clinic's own code system shadows the
  zone's copy of the same canonical and says that it is standing in front of
  one.
- **Updates keep propagating, and caring stops cleanly.** A clinic that stops
  declaring the dependency keeps what it holds and receives nothing further;
  declaring it again catches up on what it missed. An upstream that is not up
  is a wait, never a teardown.
- **A replica is the same tenant in a second place.** What a run produced
  there lands on the cloud filed under the place that made it, a batch sent
  twice applies once, and a peer resuming a cursor another lane issued is
  refused rather than replayed. A lane admits every declared type except the
  ones about a person, which travel by work or not at all.

The [joins table](../arc42-003-context/user-stories/us-dbo-two-places.md#joins)
names the test behind each.

## What the store cannot do yet

- **The store builds no channel.** It hands a caller a batch and accepts one
  back; something outside carries the bytes, authenticates and reconnects. The
  sample applications carry none, so the story drives the hospital's lane
  itself.
- **Moving work between places is a person's act.** A replica that dies
  holding work it authored keeps that work until it returns.
- **A dependency on a type the upstream does not publish receives nothing**,
  which is indistinguishable from an upstream that has not published yet.
