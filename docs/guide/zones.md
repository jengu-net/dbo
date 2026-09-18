---
title: A zone is a jurisdiction
eyebrow: Guide
standfirst: >-
  Somewhere, somebody decides the rules everyone in a territory follows. A zone
  is that, as a tenant — and joining one is a commitment rather than a label.
template: essay.html
---

Rowling Land is `rl`, and it is a tenant like any other: a database, a bring-up,
a surface, records with history. What makes it a zone is what the tenants around
it say about it.

## Joining is one word

```json
{ "code": "hogwarts", "face": "r5", "zone": "rl", ... }
```

That is the whole of membership. The hospital is in Rowling Land because it
says so, and the store treats that sentence as a commitment rather than
metadata.

Notice what it is *not*. It is not how the hospital gets the zone's
terminology — that is a dependency, declared separately, and
[the agreed vocabulary](zone-terminology.md) is the chapter for it. A tenant can
take a zone's code systems without being in it, and be in it without taking
them. The two are deliberately different statements, because they answer
different questions: *whose rules apply to me* and *what content do I hold*.

## What joining commits you to

Membership is about **people**. A zone is where a jurisdiction's identity
arrangements live — who may be a practitioner here, which identifier system
names a person, which brokers a login may come from.

So when the hospital declares its zone, it is saying its people authenticate
under that jurisdiction's terms. That is a heavier statement than taking a code
list, which is why it is a separate word.

## The zone runs its own ceremony

A zone that names no external identity broker is not misconfigured. It is its
own: every tenant carries an authority, and a zone is a tenant.

```bash
--8<-- "docs/guide/examples/snippets/zone-ceremony.sh"
```

```
200
```

That is the zone's own login ceremony answering for the keys it signs its
assertions with, standing at `/z/rl/hub`. Members federate to it without anybody
standing up an identity provider first.

A deployment that *does* federate elsewhere — a national eID, a hospital
group's directory — declares those brokers on the zone instead, and members
choose among them. Either way the member tenant says only which zone it is in;
where the people actually come from is the zone's business, which is the point
of the arrangement.

## A zone does not push

Nothing leaves a zone because a tenant is nearby. Members pull what they
declared, which has two consequences worth stating.

Adding a tenant to a zone changes nothing about the zone. And a zone cannot
place content in a member that did not ask for it — a jurisdiction can publish
rules, and cannot reach into an organisation's database.

That asymmetry is deliberate. A zone is an authority over what things *mean*,
not an authority over what a tenant *holds*.

## What you would otherwise have written

A country column, and the switch statement that reads it — then the second
switch somewhere else that has drifted from the first.

A configuration file per jurisdiction, deployed with the application, so
changing a national rule is a release.

And an identity integration per customer, because the thing that decides who a
practitioner is has no natural home above the customer and below the
application.
