**Open, and small. `core/dbo-embedded` is the node every application stands
on and `:core:dbo-embedded:test` is NO-SOURCE. Its floor is proved by a test in
`assembly/spring-boot-worker` — three cases, in the host's own
package, from another module. That arrangement has a reason and the reason is
not permanent: the host carries no bundle set of its own, so today it cannot
boot itself to be asked anything.**

# The embedded host proves itself

## What this is

`EmbeddedRuntime` boots a framework, installs a bundle set, computes the
package list the host and the container share, and hands back a lookup. Every
application reaching this store in its own JVM goes through it — the Spring
assemblies and the framework-free path alike — and it has no test of its own.

What proves it is `TheContainerComesUpInsideTheApplicationIT`, which lives in
`assembly/spring-boot-worker` and declares `package cloud.jengu.dbo.embedded`.
Three cases: every bundle reaches ACTIVE, a class the application holds and a
class the container holds are the same class, and a set naming a bundle no jar
carries is refused by that name.

**The middle one is the claim that matters**, and it is the one no build can
see. Two classloaders, two `StepService` classes, a bean that implements one
and a whiteboard that tracks the other: the container starts perfectly and
performs nothing, with nothing in any log to say why.

## Why it is where it is

Two constraints, both deliberate, and together they put the test in another
module.

**A `Bundle` is not API.** `EmbeddedRuntime.bundles()` is package-private and
says so in a comment. The class-identity assertion has to read bundle
identity, so it has to be a neighbour — widening the accessor to place the
test would be changing the API to suit a test, which is the trade this project
does not make.

**And the host carries no bundle set.** A set is an assembly's statement about
what it installs; the host unions every `META-INF/dbo/bundles.index` it finds
on the classpath, precisely so that an application holding two assemblies gets
one framework rather than two. So the host has nothing to boot with, and the
only modules that do are the assemblies.

## Where that leaves it

**The module can be changed and the thing that would catch it is elsewhere.**
Not hypothetically: the package rename that created `core/dbo-embedded` moved
this test twice before landing, and the compiler only objected because
`bundles()` is package-private. A change that kept the package would have been
caught by nothing local.

**A second assembly would not re-prove the host.** The floor test proves the
worker's set boots. Another binding — Micronaut, or the framework-free path
having a sample of its own — installs a different set and inherits no
assertion, while the shared host is exactly the part they have in common.

**And the risk is the one this repository is built around.** The host is
where a package list is computed. Getting it wrong is the characteristic
defect here: it compiles, it resolves, and it dies on first use.

## The sequence

1. **A test-only bundle set in `core/dbo-embedded`.** One line naming
   `cloud.jengu.dbo.core`, generated into the test runtime the way the
   assemblies generate theirs — `bundleIndex` in either assembly's build file
   is the shape. `dbo-core` names nothing else, so it is the smallest set that
   can resolve.
2. **The three cases, against that set.** Boot, one class space, refusal by
   name. They are the same assertions; what changes is that the module
   answers them about itself.
3. **What stays in the worker.** Whatever is about the worker's own set —
   that a worker installs no store, no face and no database, which is a claim
   about what that assembly declares and not about the host.

## What is deliberately not done

**The host does not get a production bundle set.** The test set is
test-scope, and the property that makes two assemblies share one framework is
that the host has nothing of its own to install. An item that fixed a testing
gap by giving it one would have traded the arrangement for the test.

**And `bundles()` stays package-private.** If step 1 lands, the test is a
neighbour honestly rather than by arrangement.

## What proves it

```
./gradlew :core:dbo-embedded:test
```

which today runs nothing, and is the whole item.
