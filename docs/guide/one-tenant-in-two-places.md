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
  refused rather than replayed.
- **A place sends its work home by itself.** In its sync round a site hands
  its runs, what each produced and their trail up the lane it already holds,
  on its own credential, and the cloud files them under that credential: the
  runs mirrored beside its own, the records read with the site as their
  source, `urn:dbo:upstream:<place>`. What the site wrote outside any run
  stays on the site. While the link is down the work waits on the site, and
  once it is back it all goes, each once. The runs that go are those of the
  processes the clinic declares steps of as it declares them now, however much
  of the site's own housekeeping stands before them. A step declared after the
  site came up goes like any other, and runs of its process the site already
  held are handed from its store the round after. A lane admits every declared
  type except the ones about a person, which travel by work or not at all.
- **A place knows what its tenant authorised.** What the clinic authorised of
  its register in the cloud reaches the site with every later authorisation and
  withdrawal, and is read-only there. The site's register at
  `GET /t/clinic/register` reads it as the cloud's does, and its `held` says
  when the record the site holds was given and when the site last read the
  cloud to the end. The site refuses a step whose row does not stand by what it
  holds, naming what the step is for, and keeps a row processed without
  authorisation on its incidents, as the cloud does. Cut off, it acts on the
  last record it holds and `heardAt` stays where it was; what the clinic
  authorised or withdrew meanwhile reaches it once the line is back. An
  application embedding the store on the site reads the same in-process from
  the clinic's `RegisterStanding` service, which `standing.now().permits(purpose)`
  answers, and `whenChanged` tells it the moment a withdrawal arrives.
- **Each place keeps content for its own window.** The clinic declares the
  kinds of content it holds whole, each with how long a place keeps it and how
  long the clinic does:
  `"content":{"analyser-message":{"keepAtPlace":"P1D","keepAtTenant":"P30D"}}`.
  An analyser's message the site could not take is put as it arrived, at
  `POST /t/clinic/blob?kind=analyser-message`, or in-process through the
  clinic's `BlobStore` with the kind named. A kind the clinic does not declare
  is refused by name, and so is content put under none once the clinic
  declares any, with the kinds it does declare. The site's hourly sweep
  removes the message once a day has passed; the cloud keeps one for thirty. Each removal is on the
  trail by key and kind, without what the message held, and an archive carries
  each piece's kind. A kind may also be sealed to its window, so a message
  whose patient nobody can tell is not kept in the clear
  ([personal data](personal-data.md#content-whose-person-nobody-can-tell)).
- **Content goes home with the run it belongs to.** A message put at the site
  naming a run the site performed, `POST /t/clinic/blob?kind=analyser-message&run=<key>`
  or in-process while that run is the one in progress, goes home with the run
  in the site's sync round; so does a message a record the run wrote points at
  by its `/blob/<key>`. The cloud keeps each under the key the site gave it, so
  the record still points at it, filed under the site's run and sealed as the
  cloud seals its kind, and each arrives once. It travels as the run's records
  do, on the site's lane and credential, and waits on the site while the link
  is down. Content put outside any run, and content sealed to a person, whose
  key is the site's, stay on the site.
- **A place hands home only what is its own.** Each store mints its own ids,
  so a record a site's run wrote comes home naming what it points at by what
  that says it is, and the cloud stores it pointing at its own record of the
  same thing. A reference the cloud cannot answer refuses that record, naming
  the reference, rather than storing it pointing at nothing. A record the
  cloud holds as its own, the configuration's or anybody's, is never replaced
  from a site, found by its id or by what it says: the site's write stays on
  the site. Each write-back not taken is on the cloud's trail as
  `write-back.kept-on-the-place` or `write-back.refused`, under the run that
  produced it, about the record a reader there can follow. Which types a site
  may write at all is the type's handling: `projected-config` is the
  configuration's alone, there and here.
- **A place lets go of what stands in front of its origin.** A local record
  holding back a version the origin sends parks that version until the record
  is gone. What the place's old configuration applied there goes by itself, by
  the configuration's account or because its type is `projected-config`. For
  anything else, a person holding the `configuration` scope decides:
  `POST /t/clinic/overrides/preview` with
  `{"dependency": "...", "type": "PlanDefinition", "records": ["<id>"]}` lists
  each record and what the origin holds instead, and the same body to
  `POST /t/clinic/overrides` deletes them under that person and applies the
  origin's versions. Without `records` it is every one of that type under the
  dependency. A record nobody named keeps shadowing. Letting go is one run,
  which each delete names, and the answer names it too. An optional `"for"`,
  such as `{"work": "Task/...", "order": "..."}`, is what the run says it was
  for, so a service acting on an order from the origin can be followed from
  the record it deleted back to that order.

The [joins table](../arc42-003-context/user-stories/us-dbo-two-places.md#joins)
names the test behind each.

## What the store cannot do yet

- **Beyond a place's own lane, the store builds no channel.** A place hands
  its work and its trail up the lane it holds; between any other two
  replicas the store hands a caller a batch and accepts one back, and
  something outside carries the bytes, authenticates and reconnects. The
  sample applications carry none, so the story drives the hospital's lane
  itself.
- **Moving work between places is a person's act.** A replica that dies
  holding work it authored keeps that work until it returns.
- **A dependency on a type the upstream does not publish receives nothing**,
  which is indistinguishable from an upstream that has not published yet.
