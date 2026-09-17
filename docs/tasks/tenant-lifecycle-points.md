# Tenant lifecycle extension points

Issue: jengu-net/dbo#281
Depends on [tenant kinds](tenant-kinds.md), which supplies the coarse fact
this selects on.

## What this is

Bringing a tenant up is already a sequence of conditional activities. The
conditions are written inline and derived from the spec's shape at each site,
which is why one of them — subscription dispatch — has no condition at all and
errors against a domain that does not exist on a face root.

This names the points, publishes what a tenant is as properties, and lets an
activity **declare** which tenants it applies to instead of working it out.
dbo's own provisioning goes through the same mechanism as anybody else's: it is
not an extension mechanism bolted onto a fixed sequence, it is how the sequence
is assembled.

## The points

Bring-up, in the order the runtime performs them today.

| Point | What happens | Conditional today on |
|---|---|---|
| `resolving` | the spec is read and its face version resolved | — |
| `provisioning` | the database is created and migrated | — |
| `authority` | the tenant's own authority is built | authority configured for the deployment |
| `types` | declared types are resolved against the face | — |
| `engine` | the policy object store is built | — |
| `store` | the store facade is built | whether the version is held as records |
| `terminology` | the terminology facade is built | — |
| `dispatch` | subscription dispatching starts | **nothing — this is the defect** |
| `work` | lane feed, runs and replication lanes are built | — |
| `runtime` | the runtime is assembled and staged | — |
| `surfaces` | the tenant's HTTP surfaces are mounted | one condition each, below |
| `zone` | the zone hub is built or joined | this tenant names a zone |
| `warmup` | the face is warmed | face root, or cuttable |
| `serving` | the tenant is announced as serving | — |

The surfaces are the clearest evidence that this design already exists
implicitly, because every one of them already carries its own condition:

| Surface | Mounted when |
|---|---|
| `/oidc` | the tenant has an authority |
| `/scim/v2` | the spec declares `scim` |
| `/configuration`, `/admin` | the tenant has an authority |
| `/erasure` | a person vault exists |
| `/blob` | a request guard exists |
| `/identity` | an identity-holding type is declared |
| `/work` | — |
| `/step`, `/run` | `steps` is non-empty |
| `/fleet`, `/replication` | — |

Then the points that are not bring-up:

| Point | What happens |
|---|---|
| `changing` | a spec changed under a running tenant and it is rebuilt where it stands |
| `withdrawing` | the spec is gone; surfaces come down, dispatch closes, the runtime closes |
| `removing` | the tenant is erased, which may drop the database, per its deletion policy |

## What a tenant publishes

Selection needs facts to select on, so a tenant's properties are published under
the reserved `dbo.tenant.` namespace:

```
dbo.tenant.code                      = hogwarts
dbo.tenant.kind                      = dbo.tenant
dbo.tenant.face                      = r5
dbo.tenant.zone                      = rl
dbo.tenant.holdsRecordsInFaceDomain  = true
dbo.tenant.hasSteps                  = true
dbo.tenant.hasScim                   = false
dbo.tenant.hasVault                  = false
```

The kind is the coarse fact and the rest are the specific ones. Both matter:
the step surface keys on *having steps* rather than on a kind, so selecting on
kind alone would not express the conditions that already exist.

Property names under `dbo.tenant.` belong to dbo, on the same terms as the
`dbo.` kind namespace: a service that publishes one is refused.

## Several APIs, not one

The points above are the tenant's own lifecycle. They are not the only thing
worth extending, and the rest should not be piled into the same interface: a
bundle implements the one it cares about, and each carries a different contract.

The split is not a taste decision — it is already in the storage. A tenant's
records are partitioned into **domains**, each with a feed of its own
(`PgChangeFeed(dataSource, domain)`): the content domain, `work`, `audit` and
`identity`. So the extension APIs mirror the domains, plus one that has no
domain because it is not a record in the tenant's database at all:

| API | What it observes | Backed by |
|---|---|---|
| `TenantLifecycle` | the points above | **nothing today — this is the new one** |
| `WorkEvents` | runs, claims, milestones, closes | the `work` domain feed |
| `RecordEvents` | writes to the tenant's records | the content domain feed |
| `AuditEvents` | the trail | the `audit` domain feed |
| `IdentityEvents` | credentials, delegations, provisioning | the `identity` domain feed |

### The rule that decides which is which

**Where a durable feed already exists, the API is a consumer of it and not a
callback.**

A callback is a fine mechanism for something rare that happens once per tenant
and can be re-derived if missed. It is the wrong one for anything at volume or
anything that must not be lost: a synchronous callback puts another bundle in
the path of the work, and it loses every event that happened while its bundle
was down.

The feed is the opposite on both counts. It has named consumers and durable
keyset cursors, so a consumer that was absent for an hour resumes where it left
off rather than missing the hour.

This is what makes the billing case work properly. Billing a kind **by action**
is a `WorkEvents` or `RecordEvents` consumer filtered to that kind: durable,
replayable, and it survives a restart without losing money. Billing a kind
**per tenant created** is a `TenantLifecycle` activity. Two different APIs,
each with the semantics its job actually needs, instead of one callback
interface that is wrong for one of them.

Tenant lifecycle gets a callback because it genuinely has no feed: a tenant
coming up is not a record in the database being created.

### What they share

One selector language. *Which tenants does this apply to* is the same question
for all of them, so every API is filtered the same way, over the same published
properties. Each API defines its own vocabulary of points or events; none of
them defines its own way of saying where it applies.

## How an activity says where it applies

An activity is an OSGi service registered against the point it runs at, with a
filter in the universal syntax already used everywhere else:

```
# dbo's own: subscription dispatch, which is the bug, expressed as a selector
dbo.tenant.point  = dispatch
dbo.tenant.target = (dbo.tenant.holdsRecordsInFaceDomain=true)

# dbo's own: the step surface, which already has this condition inline
dbo.tenant.point  = surfaces
dbo.tenant.target = (dbo.tenant.hasSteps=true)

# an implementor's
dbo.tenant.point  = serving
dbo.tenant.target = (&(dbo.tenant.kind=ext.clinic)(dbo.tenant.zone=rl))
```

The filter comes from Configuration Admin rather than from the bundle, so
retargeting an activity is a configuration change and not a release.

An activity with no filter runs for every tenant — which stays legal, because
some genuinely do, and is then a statement rather than an omission. That is the
whole difference from today.

Order within a point is `service.ranking`, applied one at a time. Racing
activities would make the same tenant come up differently under load, which is
the argument executor resolution already makes for the same reason.

## Whose responsibility

What a registered service does is its implementor's business. dbo does not
sandbox it, does not second-guess it, and does not try to make a third party's
code safe.

What dbo owns is whether a failure is **visible**. The defect that started this
was dbo's own bundle swallowing every failure in a retry loop identically —
no log line, no counter — so the only trace was the database's error log, and a
genuine feed failure on a real tenant would have looked exactly the same. An
activity that fails is reported once per tenant, named, and a tenant that fails
an activity is classified rather than silently degraded.

Classification rather than refusal, consistent with mandatory steps: a tenant
whose activity failed still serves, and the failure is an incident by name that
clears when it stops failing. Converting a degradation into an outage is the
worse trade, and this codebase has already decided that once.

## Why this rather than a profile of traits

An earlier draft had the kind resolve to a profile that call sites consult —
`spec.profile().holdsRecordsInFaceDomain()`. This is better, because it removes
the call sites. The composition root stops knowing what a face root is; it runs
the activities that match the tenant in front of it.

It also gives a ratchet something exact to enforce: **no activity derives its
own applicability**. It declares a selector, or it runs for everything on
purpose.

## Where it stands

Built: the facts a tenant publishes, the selector, the two points activities
run at (`dispatch` and `serving`), the whiteboard that takes up both extension
APIs, and dbo's own subscription dispatching registered through it with the
selector that excludes a face root — which is the defect, fixed by the
mechanism rather than beside it.

Both APIs exist because they carry different contracts, and the split is not
cosmetic:

- `TenantLifecycleListener` is a callback, because a tenant reaching a point
  is not a record anywhere and there is no feed of it to read.
- `TenantObserver` reads one of the tenant's streams as a **named durable
  consumer**, because the streams it watches are records. It covers the work,
  audit, identity and content domains through one interface, and an observer
  absent for an hour resumes rather than missing the hour.

`content` is the domain that needs resolving, and resolving it once is the
point: a face root and a projection hold no records on their face, so an
observer of content is **not started** for them. That is the original defect in
its second disguise, and the mechanism now refuses it in both.

The remaining points in the table are named and not yet run at. They are
converted one at a time, because a point nothing runs at is a promise rather
than a mechanism.

## First conversions

The defect, and the ones that already carry their condition inline, in that
order: `dispatch`, then the surfaces. Converting the surfaces is what proves
the mechanism covers the conditions the runtime actually has, rather than the
one it forgot.
