---
title: Lifecycle
eyebrow: Guide
standfirst: >-
  A tenant appears when its declaration does, changes where it stands rather
  than being replaced, and stops when the declaration goes. Each has a cost
  worth knowing — and two points where you can attach behaviour of your own.
template: essay.html
---

[Tenants](tenants.md) showed the two ends of this: a file appears and a tenant comes
up, the file goes and it stops. This is what happens in between, and what each
step actually costs.

--8<-- "assets/diagrams/a-tenants-life.svg"

<p class="diagram-caption">A declaration appears and the tenant is built in
order: its store and feeds first, then its surfaces, then it is announced as
serving. The three marked stages are where something of yours can be told —
moments in that same line rather than a mechanism beside it.</p>

## Coming up is not free

The first bring-up of a tenant is minutes, not seconds. A database is
provisioned, and then the part that dominates: a terminology baseline is loaded
so the tenant can validate anything at all, and the definitions of its face
arrive from its face root.

That cost is per tenant and paid once. It is why a world is something you bring
up and keep rather than start per test, and why a deployment that creates
tenants on demand should expect the first request after creation to wait.

A tenant that cannot come up yet — because the upstream it declared is not
serving, or storage somebody else provisions has not arrived — is not a
failure. It is retried on the next scan, and says so rather than reporting a
fault somebody would investigate.

## Changing it happens where it stands

Change the declaration of a tenant that is already running — here, by removing
a type from it:

```bash
--8<-- "docs/guide/examples/snippets/change-in-place.sh"
```

The tenant is not deleted and recreated. It is **rebuilt where it stands**: the
same database, the same records, the same history, with the declaration applied
over it. What it serves afterwards reflects the change:

```bash
--8<-- "docs/guide/examples/snippets/change-took.sh"
```

```
True False
```

`Patient` still served, `Observation` no longer. Nothing was migrated and
nothing was moved.

**There is a window.** A rebuild takes seconds, and during it the tenant does
not answer. That is not zero-downtime reconfiguration and this page will not
pretend otherwise — if you change declarations under live traffic, requests in
that window fail, and the caller retries.

What you get in exchange is that a configuration change is never a data
migration. The records were never the thing being changed.

## Going away

Removing the declaration stops the tenant serving, which
[Tenants](tenants.md) showed.
Worth being explicit about what that is and is not: the endpoint stops
answering. It is a retraction of service, not a deletion of data — destroying
what a tenant holds is [erasure](erasure.md) and the archive path, both of which
are deliberate acts with their own doors.

The loop that retracts undeclared tenants is also why the managing tenant sits
outside the watched directory. The thing that records retractions must not be
retractable by the loop that performs them.

Everything above is the tenant's own life. What follows is how you attach
something of yours to it — and there are two ways, differing in what happens
while your code is not running. **Choosing between them is the whole
decision.**

## A callback, for what is rare

A `TenantLifecycleListener` is told when a tenant reaches a point.

```java
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/NoticingATenant.java"
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
--8<-- "sample/src/main/java/cloud/jengu/dbo/sample/WatchingTheWork.java"
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

**Notice what is not in that list: a kind.** There is no `dbo.tenant.kind` to
filter on, and the obvious design — label a tenant a face root, a projection or
an ordinary tenant, and let activities select the label — was tried on paper
and did not survive contact with the selectors that exist. Every one of them
turned out to want a resolved fact instead, and the activity that looked most
like it wanted a kind wanted *where this tenant keeps its records*, which
follows from the types it declares rather than from what kind of tenant it is.

The difference matters on the day somebody invents a kind of tenant nobody
anticipated. A filter on `holdsRecordsInFaceDomain` keeps answering correctly
for it; a filter on `kind == "dbo.face"` has to be found and edited, and until
it is, it is wrong silently.

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

A provisioning script, and a second one for changes, and the question of which
of them owns a tenant that is half-created because the first failed.

A migration per configuration change, because the configuration lives in the
same tables as the data — so adding a type to one customer is a schema
operation with a rollback plan.

And a deprovisioning path that everyone is slightly afraid of, because it is
the one that deletes, and nothing distinguishes *stop serving this* from
*destroy it*.

A polling loop over the tenant list, and the question of how often.

A hook that fired for every tenant because filtering it was somebody's
`if` statement, in code, disagreeing with what the registration said.

A consumer that lost an hour of events because it was restarting, and a
reconciliation job written later to find out what it missed.

And a store handle passed to a listener "just for this one case", which is
now the way everything reads records.
