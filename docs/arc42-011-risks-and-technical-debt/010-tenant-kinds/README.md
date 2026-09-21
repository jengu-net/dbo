**Open. A tenant's kind is re-derived at every call site instead of being declared. The first consumer is done ahead of the kind: the dispatcher starts from what the registrations say, and a dispatch that keeps failing now says so instead of being swallowed.**

# Tenant kinds

Issue: [280](https://github.com/jengu-net/dbo/issues/280)

## What this is

A tenant's kind is not declared. It is re-derived at each call site from the
shape of the spec — `faceRoot`, whether some dependency is the face chain,
whether anybody else names this tenant as their zone — and where the derivation
is wrong or missing, nothing says so.

This is to make the kind a declared thing, resolve it to a profile of traits,
and have the runtime consult the trait rather than the kind.

## What the absence costs, as found rather than as argued

Three instances, all found in one afternoon and none of them looked for.

**A dispatcher polls a domain that does not exist.** Every tenant gets a
subscription dispatcher started against `version.domain()` — the face's record
domain. A tenant holding only definitional types keeps its records in
`definitions` instead, so the domain it polls was never created there. The poll
loop catches `RuntimeException`, sleeps and retries, with no log line and no
counter, so the only trace is the database logging a failed statement once a
second per affected tenant: four of the sample world's seven, on the order of
345,000 errors a day.

Which tenants those are was got wrong twice, and the second time was in the
fix. A face root and a projection are two of them; the fourth is the **zone**,
which is neither a face root nor face-dependent. Where a tenant keeps its
records follows from `isDefinition(typeName) ? DEFINITIONS : recordDomain` — a
property of the types it declares, not of what kind of tenant it is.

A predicate that looked like the answer sits three lines above the call and is
not used — and it is not the answer, which is the point. It catches a face root
and a projection and misses the zone:

```java
boolean versionHeldAsRecords = spec.faceRoot()
        || spec.dependencies().stream().anyMatch(TenantSpec.Dependency::face);
FhirStoreFacade store = declared.store(engine, base, db.dataSource(), versionHeldAsRecords);
...
store.dispatchNotifications(new PgChangeFeed(db.dataSource(), version.domain()), ...)
```

The second cost is larger than the noise: because every failure in that loop is
swallowed identically, a **genuine** feed failure on an ordinary tenant is
indistinguishable from this one.

**A tenant is conscripted into being a zone by somebody else's file.** A zone
never declares itself. A hub is built for it because a member's spec says
`"zone": "X"`, and nothing checks that X is a zone — there is nothing recording
that X is a zone to check against. Since a zone that names no broker is its own,
a one-word typo is silently load-bearing:

```json
{ "code": "gringotts", "zone": "hogwarts" }
```

The hospital becomes an identity zone: a hub over its database, its authority
the ceremony the insurer federates to, and the deployment comes up green. Every
spec comes from the same declared configuration source, so this is not an
escalation by an untrusted party; it is a blast radius that no file records and
a misconfiguration that cannot be diagnosed from any single file. Which tenants
are zones is answerable only by scanning every spec and taking the union of
what they point at.

**A key the parser reads and the custom resource does not declare.**
`dependencies[].face` was read by the spec parser and absent from
`tenantregistration-crd.yaml`. An undeclared key is pruned rather than refused,
so such a tenant registers, comes up, and is not on the face chain it named.
The ratchet that guards this reads the spec's **root** only, so a nested key is
invisible to it. Fixed, but the check is still root-only.

The shape is the same in all three: behaviour depends on a declaration that
nothing checks, and the failure is quiet.

## The design

### The kind is declared, and optional

`kind` is one string on the tenant spec. Omitting it is legal and means the
ordinary thing — a tenant that holds records on its face and does nothing
special. Most tenants say nothing.

### `dbo.` is reserved, and abuse of it fails loudly

The `dbo.` namespace belongs to dbo. It declares the well-known kinds and
operates on them. A kind under `dbo.` that this version does not know is
**refused when the spec is read**, by name, naming the kinds it does know — it
is a typo or a version skew, and both want the same loud answer rather than a
tenant that comes up subtly wrong.

Everything outside `dbo.` is free, including having no kind at all. An
implementor's kind needs no registration and no prefix of its own.

### A kind resolves to a profile, and the runtime asks the profile

This is the part that decides whether the change is worth making. If the kind
is consulted at call sites, nine `spec.faceRoot()` tests become nine
`kind.equals("dbo.face")` tests and nothing improves: a new kind means editing
all nine, and a kind dbo has never heard of cannot be handled at all.

So a kind resolves to a set of traits, and the runtime asks the trait:

```java
// not this
if (spec.kind().equals("dbo.face")) { ... }

// this
if (spec.profile().holdsRecordsInFaceDomain()) { ... }
```

A free kind is then definable at all, because defining one means choosing trait
values over dbo's vocabulary rather than adding a branch to dbo's code.

The honest limit belongs in the documentation: a kind outside `dbo.` cannot
invent runtime behaviour. The traits are dbo's. What a free kind buys is a
named profile plus a stable label to hang configuration, policy and reporting
on — a configuration point, not a plugin point.

### Well-known kinds

`dbo.tenant`, `dbo.face`, `dbo.projection`. Exclusive and structural: they
answer *how does this tenant store and serve*, which is the axis all three
failures above fell along.

Two things that look like kinds and are not, decided deliberately:

**Zone is a property, not a kind.** A jurisdiction should be able to hold
ordinary records, so being a zone cannot be exclusive with being a tenant. It
is declared on the zone itself — which is what turns `"zone": "hogwarts"` into
a refusal by name — and membership stays the optional property it already is,
where absent means no terminology dependency, no zone services, no connection.

**Manager is an appointment, not a kind.** Which tenant manages the deployment
is decided by the file the deployment was pointed at, and the managing tenant
is also an ordinary record-holding tenant. Making it a kind would move an
appointment into the thing being appointed.

### Kinds are visible to the container

The mechanism that consumes this is built, and is described where a reader
will need it rather than in a task document: the guide's
[Lifecycle](../../guide/lifecycle.md) chapter, and the facts a tenant publishes in
`TenantFacts`. A kind would be one more of those facts, and a provisioning
activity would select on it.

That mechanism supersedes the trait-profile sketch below: rather than call
sites asking a profile, each activity declares which tenants it applies to, and
the composition root runs what matches.

**What building it since has established.** The kind is not needed for what the
points already do. Every selector in the runtime asks about a resolved fact —
whether a tenant has an authority, a vault, identities, steps, records in its
face's domain — and the one activity that first looked like it wanted a kind
turned out to want *where a tenant keeps its records*, which follows from the
types it declares rather than from what kind of tenant it is. So the coarse
fact is still worth having for what an outside bundle would select on, and it
is no longer the thing holding anything up.


The reason to build it rather than to fix the dispatcher and move on: a kind is
something other bundles can act on. A tenant's kind belongs in the runtime
identity it publishes, so an OSGi service can filter on it — hooks and
callbacks bound to a kind, billing a kind by action, policy applied per kind
without the policy having to re-derive what it is looking at.

That is a later piece of work and it is why the trait profile matters now:
hooks keyed on a kind are only useful if the kind is declared and stable.

## Migration

Existing specs infer their kind: `faceRoot: true` becomes `dbo.face`, a
synthesised projection becomes `dbo.projection`, everything else
`dbo.tenant`. `faceRoot` keeps working for a release and declaring both
inconsistently is refused by name.

`TenantSpec` is exported, so adding a component is a recorded ledger change and
the re-record belongs in the same commit. The custom resource gains `kind` at
the same time, or a tenant carrying one is refused at registration — which is
the failure this document already describes once.

## What keeps it from decaying

A ratchet asserting that **no production source outside the kind registry
compares a kind string literal**. That is greppable, it is the same shape as
the branding check, and it is what stops the nine derivations growing back
under a new spelling.

Widening the custom-resource check from the spec's root to the whole tree is a
second, smaller one. It would have caught `dependencies[].face`, which was
found by hand.

## First consumer

The dispatcher, and it is done — ahead of the kind rather than because of it,
which is worth saying plainly.

It starts only where the tenant holds records in the face domain, and that is
read from the REGISTRATIONS rather than from a kind: the registrations already
carry the domain each type lands in, so it stops guessing and stays right for a
kind of tenant nobody has invented yet. A kind would have been a second way to
answer a question the types already answer.

The poll loop no longer swallows. It counts consecutive failures, says the
first at once, repeats every sixtieth while it lasts, and says once when it
recovers. Both extremes are defects: silence is what this replaces, and a line
per pass is a line a second per tenant.

The cadence is unit-tested and the emission is not, deliberately. A test of the
emission needs a JVM-wide slf4j provider, and a test-only provider that became
this suite's binding once cost it two hours of blocked threads — so the rule is
held by a test and the binding is what the container tests are for.

**What this does NOT do is the item.** The kind is still re-derived at the
other call sites, and the two costs below it — a tenant conscripted into being
a zone by somebody else's file, and the rest — are untouched.
