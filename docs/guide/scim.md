---
title: Provisioning people
eyebrow: Guide
standfirst: >-
  An identity provider creates the people who work here over SCIM 2.0 — and the
  credential that does it can reach nothing else, cannot enumerate through the
  front door, and cannot decide who is an administrator.
template: essay.html
---

Somebody has to create the staff. In most deployments that is an identity
provider — Entra, Okta, an HR system — pushing joiners and movers and leavers
at whatever it is integrated with.

This store speaks SCIM 2.0 for exactly that, and the interesting part is what
the provisioning credential is *not* allowed to do.

## Declared, or it does not exist

```json
"scim": { "system": "urn:rl:staff-directory" }
```

One block on the tenant, naming the system its `externalId` values come from.
Without it the endpoints are not mounted — not disabled, not answering `403`,
**not there**. A tenant that has not said it accepts provisioning has no
provisioning door.

It is also the tenant's declaration of *whose* ids these are. An `externalId`
without a system is a string that means something to somebody; with one it is a
claim about a directory.

!!! warning "It needs a vault, and it says so at bring-up"

    SCIM writes people, so the tenant must be behind the membrane —
    [Personal data](personal-data.md) is that switch. A tenant that declares
    `scim` without `pdi`, an authority, or the `Person` and `Practitioner`
    types **refuses to come up**, naming what is missing. Not a surface that
    quietly does nothing.

## A User is the person, and the capacity comes with them

```bash
--8<-- "docs/guide/examples/check.sh:scim-create"
```

```
externalId HOG-0042
userName   mmcgonagall
active     True
```

The `externalId` is claimed under the declared system, the identifying data is
authored on the **Person**, and a linked **Practitioner** capacity is ensured as
part of the create.

That linkage is not a convenience. It is the same one the authority walks when
it issues a token — [Acting for somebody else](delegated.md) showed `sub` as
the person and `fhirUser` as the capacity they act in, and this is where both
come from. Provisioning a user and being able to sign in are the same fact,
written once.

## The credential is blind to the store

The SCIM client's scope admits the SCIM surface and nothing else. Both
directions hold:

```bash
--8<-- "docs/guide/examples/check.sh:scim-blind"
```

```
403
403
```

A directory credential cannot read a patient, and a store credential cannot
reach the provisioning door. So an integration that was given the ability to
create staff was not thereby given the ability to read the records those staff
work on — which is the usual quiet outcome of putting provisioning behind the
same token as everything else.

## Listing people does not open a door

SCIM has to enumerate — an identity provider reconciles by asking who is here.
That is a real tension with a store whose whole design refuses
enumeration-shaped questions about people.

!!! info "The enumeration lives in the vault, not in the store's surface"

    Listing users is a vault method inside this server. No store API, no face
    and no FHIR search gains it, and an enumeration-shaped search is still
    refused at the front door exactly as it was before SCIM was declared.

    So the capability exists where it is needed and nowhere else. Turning on
    provisioning does not turn on *find me everybody*.

## Groups are read-only, permanently

```bash
--8<-- "docs/guide/examples/check.sh:scim-groups"
```

```
Groups are read-only: role governance does not arrive by provisioning
```

Groups render from the active role grants [Who may act](who-may-act.md)
described, and every write to them is refused with `405`.

This is the sharpest line in the design and worth stating as the store states
it: **who works here is the identity provider's call; who is an administrator
here is not.** A directory that could push group membership could grant itself
anything the roles grant, and the tenant would have no record of deciding it.

So role grants stay where they are — records in the tenant, changed
deliberately — and the directory says only who exists.

## Leaving is a state, not an erasure

Deactivating sets `active=false` on the person and on their capacity. It is
**not** erasure.

That distinction matters more here than anywhere, because the identity provider
is the wrong place to make an irreversible decision about somebody's data. A
leaver is deactivated; erasure remains the vault's own ceremony, asked for
deliberately, with its own audit shape — which is [Erasure](erasure.md).

An integration that deprovisions a thousand staff on a bad sync has changed a
thousand flags. It has not destroyed anything.

## Every operation is one recorded disclosure

Each SCIM call runs with the client as the caller and an administrative purpose
stated, so it lands in [the trail](the-trail.md) as a **single provisioning
disclosure** rather than as a series of resource reads a reviewer has to
reassemble into intent.

That is the same idea as the purpose on any other credential — the difference
is that here the purpose is fixed by what the surface is for.

## What you would otherwise have written

A provisioning endpoint with your own shape, and the integration work for every
identity provider that expected SCIM.

A service account for the directory that could read everything, because
narrowing it was a later ticket.

A `users` table that is a second copy of who exists, drifting from the records
that say what they may do — and the reconciliation job between them.

A deprovision path that deleted, discovered the morning after a bad sync.

And group push, enabled because the directory offered it, which quietly made
whoever administers the directory an administrator of the store.
