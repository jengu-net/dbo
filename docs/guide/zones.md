---
title: Zones
eyebrow: Guide
standfirst: >-
  A zone is a jurisdiction as a tenant: the rules, identity arrangements and
  terminology its members take. Joining one is a word in a member's file and a
  commitment rather than a label.
template: essay.html
---

Rowling Land is `rl`, and it is a tenant like any other. What makes it a zone is
that it says so:

```json
--8<-- "samples/sample-world/tenants/rl.json"
```

## Joining is one word

`"zone": "rl"` in a member's file is the whole of membership
([the hospital's file](a-tenant-opens.md#a-declaration-is-the-whole-of-opening-a-clinic)).
Naming a tenant that has not declared itself a zone is refused by name.

Membership is about people. A zone is where a jurisdiction's identity
arrangements live: which identifier system names a person, which brokers a
login may come from. A member's people authenticate under the zone's terms.

Taking the zone's terminology is a different statement — a dependency, declared
separately ([one tenant in two places](one-tenant-in-two-places.md)). A tenant
can take a zone's code systems without being in it, and be in it without
taking them, because *whose rules apply to me* and *what content do I hold* are
different questions. A tenant takes only from what stands above it — a zone, a
face root, its provider — and a dependency on a tenant beside it is refused by
name: what one tenant needs of another is asked for through their zone.

## A zone belongs to a zone

A zone names the zone it belongs to the way a member does, with `"zone"`:
a Land is in its country, and the country in the EU. Zones form a tree, so a
chain of them that leads back to one of its own is refused by name, listing
every zone on the way round. A deployment may hold several roots.

**A zone says what its members may do for each other**, by process. `rl`
permits `care.records`, and nothing else. A zone that lists nothing permits
nothing, and only a zone may list anything.

**A zone is bound by the one it belongs to.** A Land may permit only what its
country does, so a zone listing a process its parent does not is refused at
its declaration, naming both zones and the process. A jurisdiction may be
stricter than the one above it, never looser.

**A zone also says where its members ask.** A step a zone declares within a
process it permits is offered at every member's own door, the way the
deployment's steps are: `rl` declares `care.records.request`. A member asks it
naming the tenant whose data it is, `"crossing": {"to": "hogwarts"}`, and the
run it asks for records where the work goes. Tenants that share no zone, or a
process a zone on the way does not permit, are refused at the asker's door by
name.

**What was asked is carried, and the zones keep no copy.** The deployment
carries the run asked for up to the lowest zone the two tenants share and down
to the one whose data it is: it takes the asker's run as a participant would,
so the asker's trail records the hand-over, and authors a run at the owner over
the same inputs, naming the run it answers. Every zone on the way records that
the crossing passed, from where and to where, and keeps no run of it.

**The owner answers.** A participant of the owner's takes the run the request
was carried to, decides, and commits its answer as the exchange declares it:
`rl`'s answer is a `List` naming the records the owner shares, written in the
owner's own store, so the owner keeps the account of what it shared and for
which run.

**The answer comes back sealed to the asker's reader alone.** What the
owner's answer names is sealed to the reader the asker named, under the key the
asker's own authority holds for it, and carried back the way it came: nothing
that carries it holds a key that opens it, and every zone records that the
crossing passed again. The run that asked is then offered to that reader and
nobody else. The reader takes it on its own tenant's lane, as it takes any work,
and is handed the answer as it came back; it opens it with its own key and
reports each opening before it reads, so the asker's trail records the access
as the reader's, under the run, naming what was opened.

**And the owner sees it opened.** The opening is reported back along the route:
the owner records the access on its own document, under its own run, naming
the reader, the tenant it was opened in and the run it was opened for, with the
reader's signed link as evidence; every zone on the way records that the
answer was opened and by whom, never which document. Each report is recorded
once, however often it is carried.

**What came back goes with its purpose.** The asker holds the answer beside the
run that asked for it, never in the run, so no earlier version of the run
keeps a copy. Once that run is over the answer goes: at once, unless the
jurisdiction declared — as its exchange's answer window — that it may be kept
for a time, and then once that has passed. The owner is told, once, on its own
trail, that the copy it handed over ended. Whatever the asker's people wrote of
their own from it is the asker's, under the rules of the zone they share.

## The zone runs its own ceremony

A zone that names no external identity broker is its own: every tenant carries
an authority, and a zone is a tenant. Its hub stands at `/z/<zone>/hub` with
keys of its own, and members federate to it without anybody standing up an
identity provider first. A deployment that federates elsewhere declares those
brokers on the zone, and members choose among them.

## A zone on another face

A zone serves a face it was not written in through one projection per zone per
face — a tenant that takes the zone and stands on the target face — so the
conversion happens once rather than once per member. Nobody declares
projections; they follow from the zone's version and the faces of the tenants
that asked for it.
