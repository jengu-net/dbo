**Open. The guide is rewritten from where a Spring Boot application developer
stands, one chapter per user story, quoting the sample applications and the
sample world rather than `sample/`. The outline is in place: the nav, an index
naming every chapter, and a page per chapter. The chapters are written next,
then the guide tests and `sample/` are retired together
([item 026](../026-two-samples-tell-one-story/README.md)).**

# The guide moves onto the samples

## What this is

The guide used to teach the store as records written over HTTP to a
distribution, from source compiled in `sample/`, and to prove itself by running
every command a chapter showed against a container (`TheGuideRunsIT`,
`guide-on-tree`, `guide-as-tests`, `docs/guide/examples/check.sh`). The store
stopped being read that way: an application embeds it, declares its
organisations' work, asks for steps and performs them. `samples/` shows that,
and its tests are the user stories.

## How the guide is built now

**The reader is a Spring Boot application developer, and work is the spine.**
A process is made of steps, and a step reaches data only through what its task
carries in and what its result carries out. The general records surface
exists and a chapter may say so; it is not taught as the way to build.

**The chapters are the stories.** One chapter per developer-facing user story,
in the order the [stories README](../../arc42-003-context/user-stories/README.md)
lists them, with the story's people and scenes. Each chapter has the scene in a
paragraph, what the developer writes, what the store guarantees (linked to the
story page, whose joins table carries the proof), and what the store cannot do
yet, taken from the story page. Stories told from an operator's side are one
page, *Running it*. Concepts no single scene owns are short reference pages.

**Examples are quoted, never typed.** Every example is included from code the
stories already pass, through the site's snippet mechanism:

- `samples/spring-boot-server-app/src/main` — the clinic's application;
- `samples/spring-boot-worker-app/src/main` — the steps, embedded or in a JVM
  of their own;
- `samples/sample-world` — the declarations, always as whole files;
- a story test, where an act belongs to the test alone;
- `samples/check-separated.sh`, for the commands that start the applications
  by hand.

A part of a file is a marked region (`--8<-- [start:name]` and
`[end:name]` in a comment), so a renamed or removed region fails the site
build, and the code inside it is the code the stories run. Where quoted code
reads poorly as teaching, the code is improved rather than paraphrased.

**The guide has no tests of its own.** What it shows is already tested: the
stories walk the applications, and `samples/check-separated.sh` starts them by
hand, in both of the separated profiles, the way the quick start teaches.

## The outline

| Page | Follows |
|---|---|
| `index.md` | who the guide is for, the spine, the world and the two applications |
| `quick-start.md` | embedded, then separated under `edge` and `substrate` |
| `a-tenant-opens.md` | US-DBO-TENANT-OPENING |
| `care-is-recorded.md` | US-DBO-CLINICAL-RECORD |
| `what-a-person-can-ask-for.md` | US-DBO-PERSON-RIGHTS |
| `one-tenant-in-two-places.md` | US-DBO-TWO-PLACES |
| `the-standard-moves.md` | US-DBO-STANDARD-MOVES |
| `work-leaves-and-comes-back.md` | US-DBO-EDGE-ROUNDTRIP |
| `a-step-for-every-tenant.md` | US-DBO-FLEET-STEP |
| `on-the-stream.md` | US-DBO-ON-THE-STREAM |
| `running-it.md` | VERSION-MEASURED, VENDOR-CHANGE, A-TENANT-IS-ERASED, BUILT-OR-PLANNED, FLEET-HEALTH, BRING-UP-UNDER-STRAIN, A-DEPLOYMENT-IS-EQUIPPED, A-STEP-IS-RUN-FOR-THE-FLEET |
| reference | processes and steps, tenants and faces, zones, personal data, the trail, terminology, export and import |

## The order

1. The outline — done.
2. The chapters, committed two or three at a time; the earlier chapters stay
   in the nav, under their own heading, until the last of them has moved.
3. The guide tests, `sample/`, `sample:participant`, the CI jobs
   `guide-as-tests` and `guide-on-tree`, and `verify`'s third phase, retired
   in one change.

## Traps

**Five promises were proven only by the guide suite.** Deleting
`TheGuideRunsIT` leaves them uncited, and a promise nothing cites reads
PLANNED — which `PromiseCatalogueTest` refuses for a sentence that does not say
so. Each is declared by a story already, so each moves into a leg of that
story before the suite goes:

| Promise | Story |
|---|---|
| `PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS` | EDGE-ROUNDTRIP |
| `PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN` | EDGE-ROUNDTRIP |
| `AUTH_A_ZONE_IS_ITS_OWN_BROKER` | TENANT-OPENING |
| `VER_VALIDATION_WITHOUT_WRITING` | CLINICAL-RECORD |
| `IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED` | PERSON-RIGHTS |

**A run context closes when its run does**, and the sample worker performs
every step the world declares within a poll. A leg that reads through a run's
context needs a step nobody performs, or it races the worker.

**The guide's compose file is the worked deployment's too.**
[A worked deployment](../../arc42-007-deployment/a-worked-deployment.md)
includes it, so it is not retired with the guide; it moves beside the world it
mounts.

## What this is not

It is not the deletion of `sample/` on its own terms, which is
[item 026](../026-two-samples-tell-one-story/README.md). The two close
together, because `sample/` exists only for the chapters that include it.
