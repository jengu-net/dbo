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
different questions.

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
