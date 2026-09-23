# The host both assemblies stand on

Boot a framework, hand it a bundle set and a package list, let a Spring
context reach what it registers, and take it all down again. Nothing here is
specific to serving tenants or to performing steps, and nothing here is
visible to an application: no bean this module publishes names a `Bundle`, a
`BundleContext` or a `ServiceReference`.

It exists because the host is shared code from its first line, and because
an application holding both assemblies must get **one** framework. Two Felix
instances in one JVM each hold a copy of every bundle, and for the element
bundle that is a parsed set of FHIR definitions — measured in the container
harness at roughly 100 to 215 MB per framework — held twice.

- [`../spring-boot-server`](../spring-boot-server) — tenants, their surfaces,
  their authority.
- [`../spring-boot-worker`](../spring-boot-worker) — step services and the
  lanes they are offered work over.

## The three decisions

### One class space, or nothing works

A Spring bean implements `StepService`. The runner's activator, inside the
framework, tracks `StepService`. If those are two classes loaded by two
classloaders, the bean is never seen — or is seen and fails a cast. This is
the defect this repository calls characteristic: it compiles, it resolves,
and it dies on first use.

The fix is not an adapter and not reflection. It is that the API packages are
exported by the **system bundle**, from the application's own classloader:

```
org.osgi.framework.system.packages.extra=
    cloud.jengu.dbo.core.api;version="0.1.0",
    cloud.jengu.dbo.runner;version="0.1.0",
    ...
```

Every DBO bundle carries a *substitutable* export — bnd emits an
`Import-Package` for a package the bundle also exports, which is visible in
any built manifest:

```
Bundle-SymbolicName: cloud.jengu.dbo.runner
Export-Package:  cloud.jengu.dbo.runner;version="0.1.0", ...
Import-Package:  cloud.jengu.dbo.core.api;version="[0.1,1)",
                 cloud.jengu.dbo.runner;version="[0.1,1)", ...
```

So `dbo-runner` imports `cloud.jengu.dbo.runner`. When the system bundle
exports it, the resolver wires that import to the system bundle — bundle zero
wins a tie — and the bundle's own export goes unused. Every bundle in the
framework and the application outside it then hold the same
`StepService.class`.

**The version has to match or nothing resolves**, and a hand-written package
list drifts the first time a package is added. So the list is not written
down: it is read at boot from the `Export-Package` headers of the bundle jars
themselves. See step 2.

**The set has to be closed over what its own API refers to**, and this is
checked at boot rather than trusted. Sharing a package means the
application's copy wins for everybody; if a shared type's signature mentions
a type from a package that is NOT shared, that second class exists twice and
passing an instance between them fails. Sharing the authority without the
guard seam it implements produced exactly that — `IncompatibleClassChangeError`
on the first guarded read, as a fatal inside an OperationOutcome, from a
container that had started perfectly. bnd already records the relation as
`uses:=` on each export clause, so the set checks itself and names both
packages rather than leaving it to whoever edits the list.

**And a bundle only wires to the system bundle for a package it IMPORTS.**
This is the sharp edge, and it is not obvious: bnd writes an import for a
bundle's own export only when some *other* package inside that bundle uses
it. `dbo-tenant`, `dbo-auth` and `dbo-rest` are each a single package, so
none of them imports what it exports, and none of them wires to the system
bundle's copy however that copy is configured — it keeps its own.

What that costs is silent. The framework hides a service whose type the
consuming bundle loads differently, so a `TenantLifecycleListener` the
application registers is never seen by `dbo-tenant`: nothing is logged,
nothing throws, and the extension point looks like one with nothing to do.

Boot delegation is the wrong cure — it reaches past the wiring for every
bundle, including the ones whose classes are in nested jars the application
cannot load, and it made a working container stop bringing tenants up. It was
tried and reverted.

**The right one is done, for thirteen of the fourteen.** Each now names its
own exported package in its own `Import-Package`, so the export is
substitutable and the bundle wires to whatever provides that package —
itself, in a container with no other provider, and the application's copy in
one of these. It is policy rather than inventory: the packages are named and
the rest is still bnd's to compute, which is the line the runtime-proof rule
draws.

It changes the serving distribution's manifests, so the distribution is what
says whether it was safe: `EmbeddedContainerIT`, `TenantOsgiIT`,
`ADriverBundleContributesAStepIT`, `AHostHoldsALaneByInstallingABundleIT` and
`ServerDistIT` all pass against it unchanged.

### The fourteenth: `dbo-tenant`, and why it is now two packages

Substituting `cloud.jengu.dbo.tenant` stops tenants coming up at all. The
measurement was unambiguous: with it, this assembly's application test ran for
twelve minutes and three assertions failed because no tenant ever served;
without it, the same test ran in forty seconds.

The reason is that `dbo-tenant` was one package and that package was the whole
bundle — the manager, the activator, every handler — and the bundle carries
HikariCP privately, on its own `Bundle-ClassPath`. It is the only shared
bundle that carries anything that way. Substituting the export made the bundle
load *its own implementation* from the application's classloader, where the
embedded pool is not reachable, and a bring-up that cannot make a connection
pool fails into the trouble ledger rather than loudly.

So the rule underneath is sharper than "make exports substitutable": **a
package can be shared only if it carries no implementation that depends on
anything bundle-private.**

**The split is done.** `cloud.jengu.dbo.tenant.api` now holds what a host
implements against — `TenantLifecycleListener`, `TenantObserver`,
`TenantPoint`, `TenantDomain`, `TenantFacts`, `Change` — and nothing else. It
depends on the JDK and on one already-shared type. `TenantFacts.of` went the
other way, into the implementation as `PublishedFacts`, because it reads a
`TenantSpec` and an API type that knew this deployment's configuration would
drag that configuration into every application implementing an extension
point.

bnd writes the substitutable import by itself, because the implementation
package uses the api one. So the api package is shared and the implementation
is withheld, and a bean that implements `TenantLifecycleListener` is now told
when a tenant reaches a point.

**This is why the shared index names packages rather than bundles.** A bundle
can hold both halves, and only one of them can come from outside.

## What a shared package has to be

Three things, and each was learnt from a failure rather than reasoned out:

1. **Exported at a version the application's copy matches** — computed from
   the manifests, never written down.
2. **Closed over what its own API refers to** — checked at boot from bnd's
   own `uses:` directives, because sharing the authority without the guard
   seam it implements produced `IncompatibleClassChangeError` on the first
   guarded read, from a container that had started perfectly.
3. **Free of implementation that needs anything bundle-private** — which is
   what the tenant split is about, and what no build can check for you.

## Deliberately not done

- **No `BundleContext` bean, not even as an escape hatch.** An escape hatch
  becomes the supported API the first time somebody ships against it.
- **No hot reload of bundles.** The application is the unit of deployment.
- **No bundle set of its own.** See the header.

## The commands

```
./gradlew :assembly:spring-boot-core:test
./verify
```
