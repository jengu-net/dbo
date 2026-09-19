---
title: Extending the lifecycle
eyebrow: Guide
standfirst: >-
  Two ways to attach your own behaviour to a tenant — a callback for what is
  rare and re-derivable, a durable consumer for what must not be missed — and
  one selector language saying which tenants it is for.
template: essay.html
---

[A tenant's life](lifecycle.md) described what happens when a tenant comes up
and goes away. This is how you attach something of your own to it.

There are two APIs, and **choosing between them is the whole decision**. They
differ in what happens while your code is not running.

## A callback, for what is rare

A `TenantLifecycleListener` is told when a tenant reaches a point.

```java
--8<-- "docs/guide/examples/java/cloud/jengu/dbo/guide/examples/NoticingATenant.java"
```

Registered against the point it wants, and optionally a filter saying which
tenants:

```
dbo.tenant.point  = serving
dbo.tenant.target = (&(dbo.tenant.kind=ext.clinic)(dbo.tenant.zone=rl))
```

Three points are published. `dispatch` — the store is built and its feeds
exist. `surfaces` — the HTTP surfaces are mounted. `serving` — everything is
mounted and nothing after this changes what the tenant can be asked.

This is the one extension API that is a callback, and the reason is that it is
the only one with no feed behind it: **a tenant coming up is not a record in a
database**, so there is no stream of it to read. Which also says what belongs
here — noticing a tenant, registering it somewhere, warming something. If
missing it would matter, it is not this.

## A durable consumer, for what must not be missed

A `TenantObserver` watches one of a tenant's streams.

```java
--8<-- "docs/guide/examples/java/cloud/jengu/dbo/guide/examples/WatchingTheWork.java"
```

Registered with the stream, the consumer name it reads as, and the same
optional filter:

```
dbo.tenant.domain   = work
dbo.tenant.consumer = billing
dbo.tenant.target   = (dbo.tenant.kind=ext.clinic)
```

The name is the point. An observer reads as a **named durable consumer**, so
one that was absent for an hour resumes where it left off rather than missing
the hour — and the position it resumes from is the store's, not its own. A
callback in this role would lose every event that happened while its process
was down, which is tolerable for something re-derivable and wrong for anything
that bills.

!!! info "A feed says what changed, not what it says"

    For a tenant's records and for its trail, a change carries the type, the
    id, the version and the time — and not the content.

    Running inside the container is not an argument against this. Code beside
    the store could open the tables anyway; what a **run** confers is
    authorisation, a stated purpose and accountability, and a feed has none of
    them — there is no run to name in the trail and no reason attached to what
    was seen. An observer that needs the record [performs a
    step](performing-work.md) and reads it in the run context.

## One selector language, and a ratchet on it

Both take the same filter, over facts the tenant publishes under the reserved
`dbo.tenant.` namespace — its code, face and zone, whether it holds records in
the face domain, whether it has steps, SCIM, an authority, a vault, identities.

A filter may ask only about those, and **a filter naming a fact that does not
exist is refused where it is registered**. That is deliberate rather than
strict: `dbo.tenant.hasVualt` parses perfectly and matches nothing, for ever,
in silence. A deployment would see an activity that simply never runs, with
nothing to search for — which is the exact shape of wrongness this mechanism
was built to replace.

## What an activity may and may not do

It **declares where it applies**, or it runs for everything on purpose.

What it may not do is work its own applicability out. An activity that
inspected a tenant and decided for itself whether it was interested would put
the answer in two places — its registration and its code — and the two would
disagree eventually, silently, in a deployment nobody can inspect.

!!! warning "A failing activity is named, and cannot stop a tenant serving"

    If your listener throws, the failure is reported against your registration
    by name. The tenant still comes up.

    This reads as a bug until you see the trade: a tenant degraded by
    somebody's listener is better than a deployment that an absent or broken
    listener can take down. An observer that throws is treated differently
    again — the batch is left unacknowledged, so it arrives again rather than
    being skipped.

An observer is handed no store, no connection and no reference it can resolve,
for the reason the lane holds none either: a primitive widened for one caller
is widened for every caller holding that scope, for ever, and the widening is
invisible at the site that asked for it.

## What you would otherwise have written

A polling loop over the tenant list, and the question of how often.

A hook that fired for every tenant because filtering it was somebody's
`if` statement, in code, disagreeing with what the registration said.

A consumer that lost an hour of events because it was restarting, and a
reconciliation job written later to find out what it missed.

And a store handle passed to a listener "just for this one case", which is
now the way everything reads records.
