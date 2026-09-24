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
distribution. A guide teaching the library story needs either a second
deployment or a chapter that says plainly which of the two it is showing —
and that choice is the first thing to settle, because every other decision
follows from it.

## What has to be decided first

- **Does the guide teach one story or both?** Both is honest and longer: the
  store is deployed as a distribution and embedded as a library, and a reader
  arriving with either question deserves an answer. One is shorter and picks a
  winner.
- **If both, which is the spine?** The chapters are a narrative, not a
  reference, and a narrative that switches deployment shape halfway needs a
  reason a reader can follow.
- **What happens to `sample/participant`?** It is a party joining from outside
  and the worker application is not the same thing: a participant compiles
  against the lane and nothing else, which is a line the assemblies do not
  redraw.

## What this is not

It is not the deletion of `sample/`. That is
[item 026](../026-two-samples-tell-one-story/README.md), which lists what has
to be true first — and this item is the largest of those things. `sample/`
stays current until the chapters are somewhere else.
