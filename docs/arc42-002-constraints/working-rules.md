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

**Imports are computed, and what is hand-written is policy.** Every bundle
with source lets bnd compute `Import-Package` from bytecode; the hand-written
part is a filter — which JDK surfaces may be absent, and a trailing `!*` that
drops what a private stack reaches for and the container does not provide —
so a new reference to a sibling package is picked up on its own. The one
exception is `dbo-fhir-stack`, which has no source: it keeps a closed list, and
`TheStackImportsWhatItReachesForTest` walks every class it embeds so an
omission fails the build. What still bites is the OSGi side of it: a package
resolves and dies on first use, so `EmbeddedContainerIT`, `TenantOsgiIT` and
`ServerDistIT` are the ratchets, and when one fails after your change it is
telling the truth. Both in-JVM containers must install what the distribution
installs.

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
- MUST let bnd compute a bundle's `Import-Package` and write only policy by
  hand — which JDK surfaces may be absent, and the `!*` that drops a private
  stack's reach — and MUST NOT write a package inventory: `dbo-fhir-stack` is
  the one closed list, because it has no source, and it is checked by a test
  that walks what it embeds.
- MUST keep both in-JVM containers installing what the distribution installs.
  When one of them fails after a change, it is reporting the truth about the
  distribution; MUST NOT adjust the container test to make it pass.
- MUST give any test that loads the validator a 2g heap. It loads the core
  package eagerly and dies as an error with a null message, several frames
  above an `OutOfMemoryError` that is never printed.
- MUST NOT add a dependency to the dependency-free core module, or anything
  beyond the JDBC driver to the storage module, without a reason that
  survives being read aloud.
- MUST add a new module to the OSGi bundle set in the same commit that makes
  another module import it, in all three places that carry it — the root
  build's runtime module list, the harness's jar properties, and the
  container test's own ordered install list, which is hand-written rather
  than derived from the others. bnd computes the import from bytecode, so
  the bundle resolves on the classpath and dies in the framework.
- MUST run the negative for a test written to prove a fix: break the thing
  deliberately and watch the test go red. A test that has never failed for
  the reason it was written has not been shown to test that reason, and two
  ways it passes anyway are common — a wait that outlives the condition it
  was racing, and an assertion on text the answer contains regardless, such
  as a search echoing its own query in a bundle with no results.
<!-- /skill -->

## Reachability, which tests do not check

A harness *is* the container: it constructs whatever it needs, so it proves a
type works without proving anything can get to it. Every occurrence of this
has passed its own tests.

**The second form is what the container hands it that a test hands itself.**
Reports land through the step's declared actions, and the check lives on
`Runs` so the lane, the authoring surface and the console meet one copy of it.
`Runs` resolves declarations through a step catalogue — and the container built
the lane's `Runs` without one, so it resolved nothing, narrowed nothing, and
the lane accepted every verb of every step. A step whose declaration says its
closure is a person's act was closed by a participant reporting done, and
nothing anywhere failed. It survived because the promise's own test builds
`Runs` with a catalogue by hand. A dependency a test supplies and the container
does not is the same absence as an unreachable type, arriving through the one
door that looks wired.

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
- MUST ask what the CONTAINER hands a collaborator that a test hands itself:
  a rule enforced at a primitive is only as good as the catalogue, registry or
  credential every caller passes it, and a test that supplies one by hand
  proves the rule and not the wiring.
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

## Re-recording what is generated from what you changed

Three artefacts in this repository are generated from something else and
committed alongside it: the requirement catalogue from the promise constants,
the exported-API ledger from the bundles' own signatures, and the skills and
trap section from the constraints documents. Each has a ratchet that fails the
build when what is committed disagrees with its source.

The ratchets work. What they cannot do is run before you push, and the failure
they produce is always one commit too late — the change compiles, the tests
that cover the *behaviour* pass, and the build goes red on a file nobody was
thinking about. Re-recording belongs in the same change as the edit that made
it stale, not in a follow-up, because a follow-up means a red commit sits in
the history and anybody bisecting through it lands on a failure that has
nothing to do with what they are looking for.

The exported-API ledger is the one that catches people out, because what makes
it stale is rarely what you were doing. Adding a constant to a promise
catalogue is an API change: the catalogue is an exported enum. Adding a
component to a record removes its canonical constructor, which is a breaking
change to anything compiled against it. Neither feels like touching an API,
and both are.

<!-- skill: dbo-recorded-projections -->
```yaml
name: dbo-recorded-projections
applies-when: >-
  Changing anything a generated artefact is derived from: a promise constant
  or a Proving citation, any public or protected signature in a package a
  bundle exports — including adding a constant to an exported enum or a
  component to an exported record — or a skill-block or marked prose region in
  a constraints document. Also whenever a build fails saying a projection,
  ledger or catalogue disagrees with its source.
reference: docs/arc42-002-constraints/working-rules.md#re-recording-what-is-generated-from-what-you-changed
```
**Rules**
- MUST re-record every generated artefact its change makes stale, in the SAME
  change: `./gradlew :core:harness:promiseProjection` for the requirement
  catalogue, `./gradlew :core:harness:apiLedger` for the exported-API ledger,
  `./gradlew generateSkills` for the skills and the trap section.
- MUST treat adding a constant to an exported enum, or a component to an
  exported record, as an API change. Neither feels like one; both move the
  ledger, and the second removes a canonical constructor that callers compile
  against.
- MUST read the ledger's report rather than only re-recording it: what is GONE
  stops anything compiled against it from linking, and that is worth knowing
  before it is committed rather than after a consumer finds out.
- MUST NOT hand-edit a generated artefact. Every one of them says so in its
  own header, and the ratchet refuses it.
- MUST NOT leave the re-record to a follow-up commit. The ratchet catches it
  either way; a follow-up leaves a red commit in the history for whoever
  bisects through it later.
<!-- /skill -->

## Testing against the shared world

Most integration tests here build a world of their own — a database, a tenant
runtime, a spec file, a credential — to run a few seconds of assertions. That
is the right shape for some of them and pure overhead for the rest, and the
difference is worth deciding deliberately rather than by copying whichever test
was open.

### Which world a test belongs in

**The shared world**, driven over HTTP by `guide/` — one set of tenants brought
up once, and every step runs against it. A test belongs here when what it
proves is reachable through a face: a write, a read, a search, a refusal, a
token, a run, a provisioning call, an entry in the trail.

**A world of its own**, in the harness. A test belongs here when it reaches for
something a face does not expose — the store's own API, the database behind it,
the server log, the OSGi container — or when the situation it needs is one no
other test may see: a tampered row, a first boot, a tenant held out of service.
These are not failures to migrate. A test that tampers with a version behind
the store's back cannot share a world with anything, because the tampering is
the point.

The question that decides it: **could a reader do this with curl?** If yes, the
shared world already has a tenant for it.

### Preconditions come from the tools a reader would use

A tenant is declared by writing its spec where the deployment reads specs. A
credential comes from the tenant's own token endpoint. A record is written
through the face. Nothing is inserted into the database to arrange a situation,
and nothing reaches past the surface to set one up — a test whose precondition
was hacked into place proves that the store handles a state it cannot itself
produce.

The corollary is that the world's shape is shared cost. Adding a tenant to it
is paid by every run of every test in it, so a test that does not fit what is
declared is better left where it is than used as a reason to grow the world.

### One action, then everything worth asserting about it

A story reads as a sequence of acts, and each act is asserted from every angle
that act settles. Audit is the clearest case: that an action is recorded
belongs beside the action, where the act and its entry are asserted together,
not in a later pass that goes looking for entries and hopes it finds the right
one. The same holds for what a write returns, what it leaves behind, and what
it makes findable.

Only situations that can really arise. A refusal nobody would ever provoke is a
test that fails one day for a reason nobody can act on.

### What a shared world does to an assertion

Four rules, each learnt by breaking it.

- **Read the state you depend on; never count the writes above you.** A record
  is on whatever version the stories before yours left it on, and one of them
  gaining a write should not redden your test.
- **A step belongs in the story that creates what it reads.** Grouping by
  subject rather than by dependency puts a read before the write it needs.
- **Assert the request happened before reading anything into the answer.** A
  step that shells out can fail to run at all — an unset variable, a quoted
  `$` — and an assertion that something is absent is then satisfied by nothing
  having happened.
- **Do not assert what the tenant's own configuration takes away.** Behind the
  membrane the identifying elements are sealed out of a payload and reassembled
  on the way out, so their order is not the author's to keep.

### Moving a promise out of a test that owns a world

Claim it where it is proven, not where it is mentioned. A step that
acknowledges an answer does not prove a promise about what comes back
afterwards; if the citation needs an assertion the step does not make, add the
assertion to that step rather than a story of its own.

Then, in the same change: run the catalogue projection, read the promise's row,
and check the new site is listed. Only then delete the old test, and only when
everything it asserted is asserted somewhere — a promise's row going from two
sites to one is not the same as its coverage surviving.

<!-- skill: dbo-shared-world-tests -->
```yaml
name: dbo-shared-world-tests
applies-when: >-
  Writing a new integration test, deciding where one belongs, moving a promise
  onto a shared-world step, or considering deleting an integration test that
  builds a world of its own. Triggers on adding a test that needs a tenant, a
  credential or a record, and on any change that would add a world to the
  build.
reference: docs/arc42-002-constraints/working-rules.md#testing-against-the-shared-world
```
**Rules**
- MUST put a test in the shared world when what it proves is reachable over
  HTTP, and keep it in a world of its own when it reaches for the store's own
  API, the database, the server log or the container.
- MUST arrange every precondition with the tools a reader would use — a spec
  file, the tenant's token endpoint, a write through the face — and never by
  inserting state behind the surface.
- MUST assert everything one action settles beside that action, including the
  audit entry it emits, rather than in a later pass that goes looking.
- MUST read the state a step depends on rather than counting the writes above
  it, and put a step in the story that creates what it reads.
- MUST assert that a shelled-out request actually ran before reading anything
  into its answer.
- MUST NOT grow the shared world to fit one test; that cost is paid by every
  run of every test in it.
- MUST prove a promise where the assertion is made, run the catalogue
  projection, and confirm the new site is listed before deleting the test the
  promise came from.
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
