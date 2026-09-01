# Working rules

The rules that govern how work is done in this repository, as opposed to what
the store does. [Promise](promise.md) is the neighbouring half: it constrains
how a *behaviour* is stated and proven. This constrains everything else — what
counts as having checked something, what a comment may lean on, what a change
to one module is allowed to drag in.

The distinction that makes this a constraints document rather than a concept
is that **none of it is enforceable by a test.** A rule that a test can check
does not need writing down; it needs a test. What is left is the set of rules
whose violation compiles, resolves, publishes, and is discovered by a person
much later — and that set is unusually large here, for a reason the first
trap explains.

## Stated once, and projected

A rule is written in exactly one place: a section of a constraints document,
which argues it, followed by a **skill-block** that states it imperatively.
Two things are generated from that single source, and neither is
hand-editable:

- **A skill per block**, into `tools/dbo-conventions/` — an installable plugin,
  so the rule arrives when the work that would trip it begins, rather than
  sitting in a file somebody was supposed to have read.
- **The trap section of `CLAUDE.md`**, copied from the marked prose region
  below, so the file read at the start of every session cannot drift from the
  document that owns the text.

`./gradlew generateSkills` regenerates both; `verifySkillProjection` fails the
build when what is committed disagrees with this document. That is deliberately
the same arrangement the requirement catalogue is under — one source, a
generated projection, and a ratchet that refuses a hand edit — because a
projection which can drift silently is not a projection, it is a copy.

The mechanism is worth its cost only for rules that nothing else can catch.
A rule that belongs in a test belongs in a test.

## The traps, in order of how often they bite

<!-- claude:begin -->
**Failures here are runtime failures.** This codebase's characteristic bug
compiles, resolves, publishes, and then dies on first use. It has happened
through hand-written `Import-Package` lists, through a lazily-reached jar
excluded from a bundle, and through slf4j binding itself into silence. A green
build proves very little. Prove changes by *exercising* — validate a resource,
convert one, ingest a CodeSystem, boot the container — never by compiling.

**A toolset can be built, proven and unreachable, and the tests will not say
so.** It has happened four times in the participation work alone: a lane, a
replication toolset, a type registered for no tenant, and a normalisation
nothing outside the container could call. Every one passed its own tests,
because a harness *is* the container and constructs whatever it needs. Two
questions catch it, and they have to be asked deliberately because nothing
fails: **who constructs this outside a test** — `new X(` matching nothing in
production sources is the whole signal — and **where does its own state live**,
which is cheaper to check and easier to miss, since a surface can be mounted
and correct while the type it writes is registered for no tenant.

**The fat bundles hand-write `Import-Package`.** A newly referenced sibling
package resolves at build and throws `NoClassDefFoundError` at runtime.
`EmbeddedContainerIT` is the ratchet; when it fails after your change it is
telling the truth. Two in-JVM containers exist (`EmbeddedContainerIT`,
`TenantOsgiIT`) and both must install what the distribution installs.

**The R5 validator needs heap.** It loads the FHIR core package eagerly and on
a default heap dies as `HAPI-2330` with a null message, three frames above an
`OutOfMemoryError` nobody sees. Test tasks set `maxHeapSize = "2g"`.

**`dbo-core` has no dependencies**, and `dbo-postgres` only the JDBC driver.
Adding a library to either needs a reason that survives being read aloud.
<!-- claude:end -->

## Proving a change, which compiling does not do

<!-- skill: dbo-runtime-proof -->
```yaml
name: dbo-runtime-proof
applies-when: >-
  Changing anything that has to survive being loaded in the OSGi container:
  a bundle's dependencies or its hand-written Import-Package, a new reference
  to a sibling package, a module added to the runtime module list, a service
  registration, the logging provider, or anything reached only on first use.
  Also whenever a change is about to be reported as working on the strength
  of a successful build.
reference: docs/arc42-002-constraints/working-rules.md#proving-a-change-which-compiling-does-not-do
```
**Rules**
- MUST prove a change by exercising it — validate a resource, convert one,
  ingest a CodeSystem, boot the container — and MUST NOT report a change as
  working on the strength of compilation, resolution or a green unit test.
- MUST assume a fat bundle's `Import-Package` is hand-written: a newly
  referenced sibling package resolves at build time and throws
  `NoClassDefFoundError` on first use. Add the package when adding the
  reference.
- MUST keep both in-JVM containers installing what the distribution installs.
  When one of them fails after a change, it is reporting the truth about the
  distribution; MUST NOT adjust the container test to make it pass.
- MUST give any test that loads the validator a 2g heap. It loads the core
  package eagerly and dies as an error with a null message, several frames
  above an `OutOfMemoryError` that is never printed.
- MUST NOT add a dependency to the dependency-free core module, or anything
  beyond the JDBC driver to the storage module, without a reason that
  survives being read aloud.
<!-- /skill -->

## Reachability, which tests do not check

A harness *is* the container: it constructs whatever it needs, so it proves a
type works without proving anything can get to it. Every occurrence of this
has passed its own tests.

<!-- skill: dbo-reachability -->
```yaml
name: dbo-reachability
applies-when: >-
  Finishing any new toolset, service, surface, lane, registered type or
  capability, and before calling such work done. Triggers on a type that only
  a test constructs, on a new object type that something writes, and on
  reviewing work that is reported as complete because its tests pass.
reference: docs/arc42-002-constraints/working-rules.md#reachability-which-tests-do-not-check
```
**Rules**
- MUST ask who constructs this outside a test, and MUST treat a constructor
  call that matches nothing in production sources as the finding itself —
  that is the whole signal, and nothing fails to announce it.
- MUST ask where the thing's own state lives, and confirm the type it writes
  is registered for the tenants that need it. A surface can be mounted,
  correct and proven while the type behind it is registered for nobody.
- MUST NOT report a toolset as delivered on the strength of its own tests
  passing; the test proves the thing works, never that anything can reach it.
- MUST ask both questions deliberately at the end of the work, because
  nothing in the build asks them and nothing fails when the answer is wrong.
<!-- /skill -->

## What a comment is for

<!-- skill: dbo-comments -->
```yaml
name: dbo-comments
applies-when: >-
  Writing or reviewing a comment, a Javadoc block, a test display name, a
  commit message, or any prose inside this repository's source or its
  specification tree.
reference: docs/arc42-002-constraints/working-rules.md#what-a-comment-is-for
```
**Rules**
- MUST explain the constraint and only the constraint. A sentence that needs
  a ticket or a decision record to make sense has not yet said what it means.
- MUST NOT cite an issue number or a decision record anywhere in the source
  or the specification tree. Both are moment-bound, both are superseded, and
  a reader here frequently cannot open either.
- MAY name the issue holding the work in a `TODO` or a `FIXME`, and SHOULD
  when one is filed, because a reader wanting to know what became of the gap
  has nowhere else to look.
- MUST write documentation as a description of the current state. The journey
  belongs in the commit message that made it.
- MUST NOT name a consumer of this store, or any sibling repository, in source
  or specification prose — this store is neutral, and a domain is a face over
  it rather than a fact about it.
<!-- /skill -->

## Claiming a behaviour

The catalogue is generated, and the generation is a separate step from the
declaration. Forgetting the second step is the most reliable way to redden a
build here, because everything compiles and the failure surfaces in a test
about the projection rather than about the change.

<!-- skill: dbo-promise -->
```yaml
name: dbo-promise
applies-when: >-
  Adding or changing any behaviour of the store, fixing a defect, or editing
  the promise catalogue, a promise constant, a Proving citation, or the
  requirement catalogue document. Triggers on any work that would add a
  requirement or make an existing one true.
reference: docs/arc42-002-constraints/working-rules.md#claiming-a-behaviour
```
**Rules**
- MUST declare new behaviour as a promise constant and cite it from the test
  that proves it. Adding behaviour means claiming or adding a promise; fixing
  a defect means adding the test that would have caught it.
- MUST run the catalogue projection after changing any promise constant or
  citation, in the same change. Declaring without projecting compiles cleanly
  and reddens the build in a test about the projection rather than about the
  work.
- MUST never hand-edit the generated block of the requirement catalogue; it is
  a projection, and the projection is refused when it disagrees with the
  model.
- MUST name a test after the behaviour it proves, as a sentence.
- MUST believe the implementation status page over any impression the code
  gives about what is built.
<!-- /skill -->
