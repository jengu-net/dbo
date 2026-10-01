**Open, and widened: the guide is rewritten rather than re-pointed. It teaches
the store from where a Spring Boot application developer stands, and its spine
is work — processes made of steps, each step an activity that reaches data only
through what its task carries in and what its result carries out — rather than
records written over HTTP to a store. `samples/` gains a third application, the
hospital's own software. `sample/` is deleted when the last chapter has moved
([item 026](../026-two-samples-tell-one-story/README.md)). Nothing is written
yet.**

# The guide moves onto the samples

## What this is

A chapter includes the file compiled in `sample/`, so a chapter cannot show a
call that no longer exists. That is the arrangement working — and it is also
what pins the guide to one of the two stories this repository now tells.

`samples/` shows the same six tenants reached as a library: an application
that serves them because it added a dependency, and one that performs their
work because a bean implements an interface. Nothing in the guide mentions it.

## What the move actually is

**Thirteen include lines, across nine chapters.** Eleven name `sample/`'s own
source, two name `sample/participant`, and two more name the world — which has
already moved, so those are the cheap ones.

| chapter | includes |
|---|---|
| `index.md`, `tenants.md` | the world: `hogwarts.json`, `gringotts.json` |
| `records.md`, `history.md`, `references.md` | `Intake`, `Amending`, `TheWard`, `Publishing`, `Observing` |
| `performing-work.md`, `runners.md` | `AdmitStep`, `Admissions`, `Laboratory`, `Assay` |
| `lifecycle.md` | `NoticingATenant`, `WatchingTheWork` |
| `asking.md` | the vocabulary, over a ward |

**And two of them stop existing.** `Admissions` is fifty-four lines
constructing an executor identity, a runner with two durations, a step
registration and a lane — every one of which is configuration under
`dbo-spring-boot-worker`. `AdmitStep`'s own comment says *this is the whole of
what an integrator writes*, and that only became true when the assembly
arrived. A chapter that keeps including `Admissions` teaches wiring the
wrapper exists to delete.

`NoticingATenant` and `WatchingTheWork` are the same shape from the serving
side: beans annotated `@DboTenantListener` and `@DboObserver` in the
assembly's world, hand-registered in `sample/`'s.

## Why it is not a rename

**The guide is executable.** `TheGuideRunsIT` runs every command a chapter
shows against a live world, and `guide-on-tree` runs it against an image built
from the tree on every change. So the chapters and the applications move
together or the build says so — which is the good news, and the reason this is
a real piece of work rather than a find-and-replace.

**And the compose file is the deployment the chapters talk to.** It runs the
distribution, so a guide whose spine is the library story needs a second thing
for the chapters to talk to — the application, run from the tree — and the
compose file becomes what the deployment chapter shows.

## The guide is rewritten, not re-pointed

Decided on 2026-10-01. Re-pointing thirteen includes would have kept a guide
whose first half is records written over HTTP to a store — the reading the
store has stopped being. Two things change together.

**The reader is a Spring Boot application developer.** Every chapter starts
from code that developer writes in one of the sample applications — a step
bean, a listener, a block of `application.yaml`, a `@DboSpringBootTest` — and
explains the store from there. The FHIR surface, the distribution and the
framework-free path are still taught, as what a developer reaches for when the
application is not the whole of the deployment; they are not the way in.

**Work is the spine.** A process is made of steps, and a step is an activity:
it is handed what its task carries — records referred to or given — and
answers with result data. That is how a step reaches data, and the guide says
so before it shows a record. What used to be the guide's Core — reading,
history, references, transactions — is taught as what a step reads and what a
result commits. The navigation file's reason for putting Work late (that it had
nothing to demonstrate) stopped being true when the worker application could
perform a step.

### The shape

1. **Introduction and quick start** — the server application serving Rowling
   Land, the worker application performing a step, the hospital application
   starting a process and reading its result.
2. **Work** — a process and its steps; what a step declares (slots, result,
   milestones); a run, its task content in and its result out; performing a
   step as a bean; asking what is open or needs somebody; the trail.
3. **Data, as a step sees it** — inputs referred to or given; what a result
   commits (references, history, transactions); validation against the step's
   profiles; terminology.
4. **Tenants and zones** — where a process runs, largely as now.
5. **Security and privacy** — a step's entitlement, the claim as the
   intersection of credential and step; the tenant's authority and directory;
   personal data through pseudonyms and sealed payloads; erasure; retention.
6. **Deployment** — the distribution and its compose file, the framework-free
   path, export and import, fleet steps.

### The code behind it

- **`samples/spring-boot-hospital-app`** — the hospital's own software, new.
  What `sample/`'s `Intake`, `Amending`, `Publishing`, `Observing` and
  `TheWard` showed, rewritten to start work and read its result rather than to
  write records.
- **`samples/spring-boot-server-app`** — gains the serving side's beans:
  `NoticingATenant` and `WatchingTheWork` as a `@DboTenantListener` and a
  `@DboObserver`.
- **`samples/spring-boot-worker-app`** — already performs steps
  (`AdmittingAPatient`, `MeasuringASpecimen`); `Admissions` has no successor,
  because what it wired by hand is configuration.
- **The executable guide** follows: `TheGuideRunsIT` moves under `samples/`
  and runs the chapters' code against Rowling Land
  (`samples/sample-world`), the world the user stories walk.

### The order

1. The hospital application and its tests.
2. The chapters, in reading order, the guide green at each one.
3. `sample/` deleted, and items 026 and 027 closed together.

## What has been settled

**Spring Boot is where the reader stands.** The quick start is
`./gradlew :samples:spring-boot-server-app:run` — a Spring Boot application
that serves the sample world because it added a dependency. Every aspect of
the store is then explained against an application of that shape, because
Spring is what most developers arriving here already know.

**The framework-free path is the advanced chapter, not the lesser one.** It is
the layer the assemblies are built on, and a reader who wants no framework is
reading a real path rather than a workaround
([building blocks](../../arc42-005-building-blocks/README.md) states the rule).
The distribution is likewise still taught — as deployment, which is the
question it answers.

**`sample/participant` keeps its point by being made smaller.** A party joining
from outside is a line the assemblies do not redraw, and the worker
application is not the same thing. What `Assay` teaches — that a step can
declare its own capability — moves to a bean in
`samples/spring-boot-worker-app` returning `Optional.of(DECLARED)`, which is
the same claim in the shape a reader will write it.

## What this is not

It is not the deletion of `sample/`, which is
[item 026](../026-two-samples-tell-one-story/README.md) — but it is what
unblocks it. `sample/` exists because the guide compiles against it; once the
chapters compile against `samples/` instead, nothing holds it, and it goes.
Until then it stays current.
