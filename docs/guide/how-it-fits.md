---
title: How it fits together
eyebrow: Guide
standfirst: >-
  One idea binds the rest: reaching data is an act of declared work. Read this
  before the chapters and the order of everything after it stops looking
  arbitrary.
template: essay.html
---

You have just written a record and read it back, which is the part of this
store that looks like every other store. This page is about the part that does
not, and it is worth five minutes before the chapters because it decides what
all of them are for.

## Work is the thing everything else attaches to

Three words carry it.

A **process** is a named piece of work — admitting a patient, settling a claim,
publishing a passport. A **step** is one stage of it. A **run** is one attempt
at one step, and it is an ordinary record in the tenant's own store rather than
a message on a queue.

The step is the unit everything else hangs off. It declares what data it may
touch, which role may perform it, and what it produces. That single attachment
point is doing three jobs at once, which is the whole reason it is built this
way:

**Authorisation.** A request is admitted because a step admits it, not because
a credential happened to be broad enough. There is no second permission model
to keep in step with the first.

**Purpose.** The step *is* the reason the data was reached. A read that
happened while dispensing a prescription carries its justification with it,
rather than having one reconstructed for an auditor a year later from a
timestamp and a user id.

**Accountability.** The trail names the run, so every access has a *why*
attached and not merely a *who*.

A general-purpose data API cannot give you those, because at the moment of the
call it knows only the credential and the query. That is the trade this store
makes, and everything in the chapters after this is either what work acts on or
how work reaches it.

## What the other sections are, in that light

**Core** is what work acts on: records, their history, how they are found, what
they are checked against, the vocabulary they use.

**Tenants** is where it all lives — one database per organisation, and the
faces they are served over.

**Zones** is whose rules apply: a jurisdiction, as a tenant, that others take
their terminology and constraints from.

**Work** is the section that goes into processes, steps, runs and the trail
properly, once the vocabulary above has something concrete to attach to.

**Security** is who may perform a step, derived from records the tenant already
keeps rather than from a user table beside them.

**Privacy** is what happens to the person inside all of that: sealed, referred
to by pseudonym, and erasable by destroying a key.

## One honest word about the examples

Every command in this guide talks to a tenant's general surface with a
credential the deployment holds. No step appears in any of them.

That is what exists today, and it is the deployment's own door rather than the
one an application is meant to come through. The step-addressed surface — where
a run is the context a request happens in, and the data a step may reach is
declared — is designed and not built. You can read
[the design](../arc42-008-crosscutting/reaching-the-data/README.md) if you want
to know where this is going.

So read the chapters with the frame above, and read the examples as showing
what the store does rather than how an application will eventually ask for it.
Nothing about records, tenants, faces, zones or privacy changes when the door
does; what changes is what has to be declared before you may knock.
