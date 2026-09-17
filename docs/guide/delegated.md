---
title: Acting for somebody else
eyebrow: Guide
standfirst: >-
  A process that acts in a person's name carries both identities, attenuates
  rather than inherits their authority, and — where the work outlives the
  token — holds a delegation that can be ended.
template: essay.html
---

Most real work is started by a person and finished by something else. A matron
asks for a discharge summary; a process assembles it four minutes later, or at
two in the morning when the batch runs.

The usual answer to this is a service account with broad rights and a comment
explaining that it acts for clinicians. It works, and it destroys the only
question an auditor actually asks. *Who read this record?* — the service
account. *On whose behalf?* — nobody knows.

This store keeps both names on the access.

## Two identities, not one

A delegated token carries the person as its **subject** and the process as its
**actor**. Neither replaces the other:

| Claim | Holds | Which answers |
|---|---|---|
| `sub` | the person | on whose authority |
| `fhirUser` | the capacity they act in | as what — a practitioner, not just a login |
| `act` | the client | what actually made the call |

That `fhirUser` row is a distinction worth pausing on, because it is made
consistently and it is easy to miss. The credential binds to the **person**,
and the capacity they act in is resolved from the person's own record — their
link to a practitioner. A credential pointing straight at a practitioner would
authenticate somebody to a subject that grants nothing, and the failure would
read as a permissions problem when it is really a wiring one.

So the person signs in, and the store works out what they *are* here. The
matron from [Who may act](who-may-act.md) already exists as a practitioner with
a role; this gives her a person record linking to that capacity, and a login:

```bash
--8<-- "docs/guide/examples/snippets/a-person-signs-in.sh"
```

```bash
--8<-- "docs/guide/examples/snippets/her-credential.sh"
```

She signs in. There is no browser here, so the redirect the store would have
sent one to is read instead of followed, and the code in it is exchanged the
way the console would have:

```bash
--8<-- "docs/guide/examples/snippets/sign-in.sh"
```

And the token says who she is and what she may do:

```bash
--8<-- "docs/guide/examples/snippets/who-she-is.sh"
```

```
sub       01a0af8c-242e-…
fhirUser  Practitioner/01a0af8c-22f2-…
scope     user/Patient.read user/Observation.read
```

Nobody granted those scopes to her login. They came from the role her
practitioner record holds at this organisation — which is what
[Who may act](who-may-act.md) meant by authorisation coming from the records
you keep anyway.

## Authority attenuates, never widens

A process acting for somebody gets the intersection of what they hold and what
it asked for. Not a union, and not a copy.

!!! warning "Asking for more than the person has is not an error, it is a smaller token"

    Scopes the person does not hold are dropped rather than granted, and if
    nothing delegable remains the exchange is refused outright. A process
    cannot acquire, by being asked to act for somebody, an authority that
    person never had.

The exchange itself is one request, and the chapter makes it more than once,
so it is worth a function in your shell before going on:

```bash
--8<-- "docs/guide/examples/snippets/token-exchange.sh"
```

Here is a process taking her authority for one job:

```bash
--8<-- "docs/guide/examples/snippets/acting-for-her.sh"
```

```
sub       01a0af8c-242e-…
fhirUser  Practitioner/01a0af8c-22f2-…
act       night-ledger
scope     user/Patient.read
```

Both names, on one token. She is still the subject and still the practitioner;
`night-ledger` is what actually holds it. And the scope narrowed to the one
thing this job needs, out of the two she holds.

Now ask for something she cannot do:

```bash
--8<-- "docs/guide/examples/snippets/attenuation.sh"
```

```json
{"error":"access_denied","error_description":"no delegable scope remains"}
```

She has no write scope, so there is none to delegate, and the exchange is
refused rather than quietly handing back a token that can do nothing.

This is the property that makes the arrangement safe to use widely. The
question *what can this process do* has an upper bound that is written down —
the authority of whoever asked it — instead of being whatever its service
account accumulated over three years.

## When the work outlives the token

Token exchange covers work that happens while the person is still signed in.
Plenty of work is not like that: an overnight reconciliation, a referral that
completes next week, anything with a queue in front of it.

For those there is a **delegation** — recorded while the person's token is
live, and exchanged against long after it has expired. It is a record, so it
can be ended.

And it is deliberately *not* a stored copy of the person's authority:

!!! info "A delegation can neither outlive a revocation nor widen with a later grant"

    Each exchange re-evaluates the person's **current** grants and intersects
    them with the scopes the delegation recorded. Take a role away from
    somebody and the standing delegations that leaned on it stop working on
    their next use. Give them a broader role tomorrow and yesterday's
    delegation does not quietly inherit it.

```bash
--8<-- "docs/guide/examples/snippets/a-delegation.sh"
```

```json
{"delegation_id":"01a0af8c-51d8-…"}
```

That id is all the process keeps. Exchanging it produces the same two-named
token as before — no browser, nobody present, and her own token long expired:

```bash
--8<-- "docs/guide/examples/snippets/exchange-a-delegation.sh"
```

```
sub       01a0af8c-242e-…
fhirUser  Practitioner/01a0af8c-22f2-…
act       night-ledger
scope     user/Patient.read
```

A stored grant that answered from a snapshot would do the opposite of both, and
the second failure is the one nobody notices: a delegation created when
somebody was a junior nurse, still being exchanged after they became an
administrator, silently carrying the wider authority.

## Why the purpose is asked for every time

A delegation records **no** purpose of use. The purpose is stated by the
exchange that uses it, not by the grant that enables it.

That looks like an omission until you consider what the alternative stores. A
standing grant that named a reason would keep asserting that reason every time
it was exchanged, for as long as it lived — long after the occasion had passed.
A purpose that outlives its occasion is the one thing an audit trail cannot
afford to hold, because it is worse than no purpose at all: it is a confident,
specific, wrong answer.

So the reason travels with the act, and the standing grant stays silent about
it.

## Ending one

A delegation ends by being ended — a call against the delegation itself, and
only the person who granted it may make it. There is no waiting for an expiry
and no hunting for tokens already issued: the next exchange simply fails.

Ending it is a **status change on the record, not a deletion**. The delegation
stays, marked ended. That matters for the same reason a retired login is not a
deleted one: a grant that vanishes cannot be told from a grant that never
existed, and the period during which a process could act for somebody is
exactly the kind of fact an investigation needs to establish afterwards.

```bash
--8<-- "docs/guide/examples/snippets/ending-a-delegation.sh"
```

```json
{"error":"invalid_grant","error_description":"delegation invalid or ended"}
```

Between that and the re-evaluation above, there are two independent ways a
delegation stops being useful, and neither of them requires anybody to
remember it exists.

## What lands in the trail

Every delegated mutation is attributable to **both** the process and the
person. Not one entry naming a service account, and not two entries that a
reader has to correlate by timestamp.

That is the whole point of the arrangement, and it is why it is worth more than
a service account with a comment. [The trail](the-trail.md) is where those
entries are read back.

## What you would otherwise have written

A service account per integration, each with the union of every right any of
its callers might need, because working out the intersection per request was
too much work.

An `on_behalf_of` column, populated by whichever code paths remembered, and the
slow realisation that it is unvalidated — anybody who can call the service can
claim to be acting for anybody.

A revocation story that is really a deployment: rights were baked into a token
or a config file, so taking one away means reissuing something and restarting
something else.

And the audit answer that says a service account did it, which is true, and
useless.
