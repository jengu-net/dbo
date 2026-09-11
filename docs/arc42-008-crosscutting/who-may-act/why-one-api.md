---
title: Who works here is who may act
eyebrow: Why DBO
standfirst: >-
  The records a tenant keeps anyway say who works here, in what role, from when
  until when. Those are read as grants — so there is one surface to secure, and
  revoking access is ending a period on an ordinary record.
why: 8
template: essay.html
---

The records a tenant keeps anyway — who works here, in what role, in which part
of the organisation, from when until when — are read as grants. There is no
second list of users, because the first one was never only a list of users.

Most clinical servers keep a second interface for that: an admin console, a
user table, a client registry, settings in a file somebody edits. It is younger
than the interface everyone reviewed, and usually authorised by a different
mechanism, which is why it is where the security problems are.

Here there is one surface. `Person`, `Practitioner`, `Organization` and
`PractitionerRole` are not just records the store happens to hold — they **are**
the identity and authorisation model. Where FHIR has no resource for something
— client applications, signing keys, role grants, audit entries — the store
uses a regular versioned record in the tenant's own store, with the same
authority, the same history and the same audit as everything else.

## The organisation model is already the authorisation model

The records a tenant keeps anyway say who works here, in what role, in which
part of the organisation, from when until when. Those are read as grants.

A human's access derives from an active `PractitionerRole`. Revoking it is
ending a period on an ordinary record — not a row deleted from a table nothing
else can see.

A second system listing the same people with the same roles is a system that
drifts, and you find out about the drift when somebody who left last year still
has access.

## Authentication is shared; authorisation never is

--8<-- "assets/diagrams/authentication-shared-authorisation-never.svg"

<p class="diagram-caption">A valid identity is not access. The refusal on the
right is the same verified person as the two grants above it.</p>

The store authenticates nobody in production. A person goes to whatever
identity broker the deployment federates with — a national eID service, say —
and comes back verified.

Because a national ceremony is often billed per use and a person may work for
several organisations, one hub holds a short-lived session carrying the
verified identifier and the time of the ceremony. **No names.** Each tenant then
resolves that person in its own store, evaluates its own grants, and mints its
own token.

<div class="takeaway" markdown>
Each tenant is its own authority, with its own issuer and its own keys. A token
minted by another tenant fails at signature verification — wrong issuer, wrong
keys — *before any claim is read*. Cross-tenant confusion is unrepresentable
rather than filtered, and a compromised key is one tenant's problem.
</div>

## A token says who, without saying who they are

Tokens issued for people are pseudonymous by construction. The subject is the
person's record id; the token carries no name and no national identifier. A
captured token identifies nobody.

An interface that needs to show a name fetches it through an authorised read —
which is itself recorded, because reading who somebody is *is* a disclosure.

## What this buys on the day of a restore

A tenant's export carries its own authority. So restoring a tenant restores who
may access it, in the same operation.

Recovery has no separate hand-managed step for "and now re-establish the
credentials". That step is the one that gets missed, and it is missed at
exactly the moment when everyone is already having a bad day.

<div class="further" markdown>
The two doors — a system acting on its own behalf, and a person acting as
themselves — the enrolment keys, and the one declared cross-tenant relation are
in [Who may act](README.md).
</div>
