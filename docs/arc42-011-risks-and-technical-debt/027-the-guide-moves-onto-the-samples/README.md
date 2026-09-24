**Open, and not started. The guide teaches the store as a distribution because
that is what `sample/` is. The applications under `samples/` teach it as a
library, and the guide has not moved onto them. Thirteen include lines across
nine chapters name `sample/`, and two of its classes stop existing the moment
an assembly is used.**

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

## What has been settled

**Spring Boot is the spine.** The quick start is
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
