---
title: Personal data
eyebrow: Guide
standfirst: >-
  A tenant can put what identifies a person behind a membrane: sealed under
  that person's own key, reassembled for a reader who may see it, answered for
  only when the asker says why, and erased by destroying the key.
template: essay.html
---

## One line turns it on

`"pdi": true` in a tenant's declaration, decided when the tenant is declared,
because it moves where identifying data lives. It is per tenant: a deployment
can hold a clinical tenant behind the membrane and a terminology tenant with no
people in it at all.

## What is sealed

The face says which elements identify somebody, for the types that are about
people — `Person`, `Patient`, `Practitioner`, `RelatedPerson`. Their
identifiers, names, contact details, addresses and photos are held in the
tenant's person vault under the person's own key; the birth date is sealed too,
with the year kept in the clear beside it so a reader not entitled to the date
can still work with an age band. What the main store holds is pseudonymous: a
sealed blob and a pseudonym, and none of the person in a form an operator, a
backup or a replica could read.

Each person has a key of their own, stored wrapped under the tenant's working
key. That key is wrapped in turn by the deployment's key from custody, or by a
key the tenant's owner holds
([the owner's key](a-tenant-opens.md#the-tenants-working-key)). A person's
`Patient` and the `Person` who is them are one human under one key.

## Reading is unchanged; asking is not

A caller who may see the person gets the whole record back, reassembled, with
nothing to remember. A caller who may not gets it without the person. What a
recipient sees is declared, not inferred from how much it may write, and the
strict mode is the default
([what a person can ask for](what-a-person-can-ask-for.md)).

What changes is asking. A search by name is refused rather than answered empty,
because an empty answer would say nobody here is called that. A lookup by an
identifier is an identification, refused until the request states a purpose in
its `Purpose-Of-Use` header, and the trail records the purpose. A purpose is an
assertion, never an authorisation: it widens nothing.

## Doors of their own

Identifying somebody, deriving and resolving a pseudonym, keeping content
sealed to a person, and erasing somebody are each a door with a scope a grant
over the records does not reach. A credential that may write every type may
knock on none of them.

## Erasure destroys the key

Erasing a person destroys their key. Every copy of their identifying data —
the history, the archives, a second place that replicated it last week — becomes
pseudonymous at once, with nobody chasing rows across systems that may not be
reachable. History stays byte for byte; the person goes out of it. Being erased
and being undisclosed are different states, and only the second is reversible
by a better credential.

Erasure is asked for like work and answered by a run: asking twice finds the
same run, and its tally says how far it got and whether there was a key to
destroy. The trail survives it, and stops naming anybody.

## Content whose person nobody can tell

A device's message may name a patient, and nothing can tell where. Content
like that is put under a kind the tenant declares sealed to its window:

```json
"content": {"device-message": {"keepAtPlace": "P1D", "keepAtTenant": "P7D",
                               "sealed": "window", "purpose": "required"}}
```

Put naming no person, it is held under a key of the tenant's for that kind and
the day it was put, wrapped as its people's keys are, and read back as it was
sent. Once the window has passed for the last of a day's content, the retention
sweep destroys that day's key and the trail says so by kind and day. A copy
still held under it, in a backup or a replica, answers `410` that its window
passed. Content put under the same kind naming a person is sealed to that
person instead, so erasing them reaches it. Only a tenant behind the membrane
holds keys, so a declaration outside it is refused.

With `"purpose": "required"` the message is read only by a reader who says why,
as a PurposeOfUse code in the `Purpose-Of-Use` header or on the token; one who
does not is refused `403`. Whether or not a kind asks for a purpose, every read
of its content is an access entry on the trail about that content, naming who
read it, its kind and the purpose stated. The store cannot tell whether opaque
bytes name somebody, so it records every opening rather than guessing which
were disclosures.

Erasing a whole tenant is different: an operator drops its database
([running it](running-it.md#a-tenant-is-erased)).
