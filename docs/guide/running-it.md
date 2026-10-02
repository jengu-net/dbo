---
title: Running it
eyebrow: Guide
standfirst: >-
  The stories told from the side of whoever runs the deployment. An
  application developer meets them from the outside, so each is a paragraph
  here and a story page with the whole scene and its proof.
template: essay.html
---

The chapters before this one are what an application writes. These are what a
deployment is given, what it can be asked from outside, and what only its
operator does. They are in the order the
[stories README](../arc42-003-context/user-stories/README.md) lists them.

## A version is measured

Before a FHIR version's database answers are trusted, the release is measured:
the checkers in the process and the SQL in the database, held to each other
and to the specification the release carries, over everything the version
publishes rather than a handful of examples. It happens before there is a
deployment, on a store opened up with the face's own libraries.
[US-DBO-VERSION-MEASURED](../arc42-003-context/user-stories/us-dbo-version-measured.md).

Not yet: the faster face is measured and not served; what a tenant needs from a
face is derived and not yet what the sync sends; and some divergences remain,
each explained in the baseline.

## A vendor changes

The clinic leaves and takes everything with it, in a sealed archive the
operator keeps without being able to open, restorable somewhere else with its
identities intact and safe to restore twice
([export and import](export-and-import.md)).
[US-DBO-VENDOR-CHANGE](../arc42-003-context/user-stories/us-dbo-vendor-change.md),
walked by
[`TheClinicChangesVendorIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicChangesVendorIT.java).

Not yet: there is no partial export — it is the tenant or nothing.

## A tenant is erased

A clinic is erased by dropping its database, so its records and whole
recordings go with it and nothing survives because a sweep forgot a second
system. Retracting a declaration stops serving; erasing removes.
[US-DBO-A-TENANT-IS-ERASED](../arc42-003-context/user-stories/us-dbo-a-tenant-is-erased.md).

It is asked at the deployment's erasure door, `POST /runtime/erase/<code>`,
under a token the deployment gives for erasure and nothing else, with the
reason stated in the body. A tenant still declared is refused, and asking again
answers the same.

## What is built, and what is planned

Every promise the store makes is a constant, its status is derived from the
tests that cite it, and one generated table — the
[requirement catalogue](../arc42-006-runtime/req-catalogue.md) — tells what is
carried by a test from what is only intended. Every story's joins table is a
projection of it.
[US-DBO-BUILT-OR-PLANNED](../arc42-003-context/user-stories/us-dbo-built-or-planned.md).

Not yet: coverage is a count rather than a judgement, a proof is a citation
rather than a review, and the table has no history of its own.

## The fleet, read and steered

A deployment is read from outside every container: what each node serves and
knows how to do, who is present, and what sits behind them, assembled from
one-hop reports into a tree. Presence is derived from a moving cursor rather
than declared, and nothing is dropped for being stale. It is steered through
the door a participant would use.
[US-DBO-FLEET-HEALTH](../arc42-003-context/user-stories/us-dbo-fleet-health.md),
walked by
[`AnOperatorReadsAndSteersTheFleetIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/AnOperatorReadsAndSteersTheFleetIT.java).

Not yet: the tree is polled and pushes nothing, keeps no history, and carries a
participant's own small state map rather than a measurement series.

## Bring-up under strain

Tenants come up while what is around them is slow, half-arrived, racing or
wrong, and each reads as what it is: a wait, a failure naming what failed, or
nothing at all. Tenants declared together come up together; a mandatory step
nobody performs is an incident, not an outage; a node stopped mid-sync resumes.
[US-DBO-BRING-UP-UNDER-STRAIN](../arc42-003-context/user-stories/us-dbo-bring-up-under-strain.md).

Not yet: a wait has no deadline, a teardown is no state at all, and how many
tenants come up at once is the deployment's number rather than one sized from
its heap.

## A deployment is equipped

What a deployment is given before it starts takes effect exactly as given:
face images refused when they are not this release's, a bootstrap secret per
tenant that opens its own tenant and no other, the brokers a zone names, and a
zone reaching a face it was not written in.
[US-DBO-A-DEPLOYMENT-IS-EQUIPPED](../arc42-003-context/user-stories/us-dbo-a-deployment-is-equipped.md).

Not yet: the deployment's hub federates to one upstream; broker secrets are
keyed by broker code across the deployment; what a deployment was given is read
when it is built; and what loading the specification costs is measured rather
than configured.

## The deployment's step, from inside

The [fleet step](a-step-for-every-tenant.md), seen by whoever operates it:
which tenants admitted or declined it, what each register says is opened, rows
nobody authorised and the posture they obey, a processor enrolled per tenant, a
substrate prepared and kept, and a writeback held to each tenant's rules.
[US-DBO-A-STEP-IS-RUN-FOR-THE-FLEET](../arc42-003-context/user-stories/us-dbo-a-step-is-run-for-the-fleet.md).

A tenant reads its register and its incidents at `/t/<code>/register`, and the
operator reads which tenants have rows standing unauthorised at
`/runtime/fleet`. Not yet: a deployment's configuration cannot name a
processor, and the scenes that need the management tenant to declare more than
the sample world does are walked on a deployment of their own.
