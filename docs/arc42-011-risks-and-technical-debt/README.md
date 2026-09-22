# Risks and technical debt

The outstanding work, as numbered items. Each item is a directory holding a
README whose first line is the item's state, and whatever the item needs
beside it: a measurement, a listing, a trace. A resolved item is deleted;
the commit that resolved it, or the decision record it produced, is its
record.

A risk that has not become an item is listed under Risks below, in one
sentence, until it either becomes one or stops being a risk.

## What an item carries

An issue says what is to be done and whether it is done. The specification
says how the store works once it is. An item carries what neither holds: the
problem as it stands, the order the steps become possible in, what was
decided along the way and what bit. It names the issue holding the work by
address, because a number is not something a reader here can open.

A useful item answers, in this order: what this is, where it stands, the
sequence, the decisions and why each beat the alternative, the traps, what
is deliberately not being done, and the commands that prove it.

## Items

| Item | State |
|---|---|
| [001 The documentation tree is moved to match its map](001-documentation-shape/README.md) | Open. The context chapter and the worked deployment are done. One disagreement is left, and it waits on item 002: the guide is shell commands, and the map says it is the sample application's story. |
| [002 The sample application](002-sample-application/README.md) | Open. The actor surface exists and two scenes run over it, each having caught an assumption an in-JVM scene would have let stand. Next: the guide's chapters, one at a time, over that surface. |
| [003 Own-world tests move down the ladder](003-tests-move-down-the-ladder/README.md) | Open. The cast has started and one class has moved onto it: delegation runs on the hospital. Four families read against it since, each resisting for a named reason. Next: the shapes kept apart only by what the sample world declares. |
| [004 The guide runs three times in CI](004-the-guide-runs-three-times/README.md) | Open. The step is ported, the shell harness is gone from CI, and who moves the pin is written down. Next: whether the pinned shell run earns its place, which item 002 forces. |
| [006 The specification is cut to the house style](006-the-specification-in-house-style/README.md) | Open. The stories' authored prose is cut. The promise text was measured rather than cut: 7.5 per thousand whole, where the working rules are 7, and the one edit worth making was a promise reciting its neighbours. The crosscutting pages measure 7.5 too. The counter was reading code as prose and is fixed: the tree is 7.0 with no section above 8.5. Next: the guide, whose chapter list item 021 settles. |
| [007 The face contract](007-the-face-contract/README.md) | Open. One slice remains and it is still theoretical: the contract's silent answer is that one payload is one object, and the second face shares that shape rather than probing it. |
| [008 IHE profiles](008-ihe-profiles/README.md) | Not scheduled. Analysis only: no profiled surface is served and no issue is filed. |
| [009 The step-scoped API](009-the-step-scoped-api/README.md) | Open. The store promises that reaching data means performing a step, and a plain read still bypasses it. |
| [010 Tenant kinds](010-tenant-kinds/README.md) | Open. A tenant's kind is re-derived at every call site instead of being declared. The first consumer is done: the dispatcher reads the registrations, and a failing dispatch says so. |
| [011 UBL as a face](011-ubl-as-a-face/README.md) | Open. Three spikes are green and in the tree; nothing is built. |
| [013 A neutral IFC repository](013-a-neutral-ifc-repository/README.md) | Not scheduled. Recorded so the reasoning exists before somebody needs it. |
| [014 The Karaf console](014-the-karaf-console/README.md) | Not scheduled. A proposal for seeing inside a running node: development and operator tooling, never production. |
| [015 The comparative load test](015-the-comparative-load-test/README.md) | Not scheduled. The bench runner carries the discipline; a second and third target, an ingest workload and resource sampling are missing. |
| [016 Where a neutral store earns its keep](016-where-a-neutral-store-earns-its-keep/README.md) | Not scheduled. A test for recognising the domains this engine's shape fits. |
| [017 Quality coverage is not folded](017-quality-coverage-is-not-folded/README.md) | Open. The twelve goals are declared and the quality tree is generated from them, coverage and all, under the catalogue's own ratchet. Four goals are short — isolation 5/7, process-as-storage 7/9, portability 6/7, performance 1/6 against an open gap. Next: the figures that gap names, which is item 015. |
| [021 Asking the store a question](021-asking-the-store/README.md) | Open. A product building a screen has no vocabulary for asking this store anything; the in-JVM binding is already on the whiteboard and hands back an engine handle. Carries the guide's chapter list. |
| [023 The suite runs out of heap](023-the-suite-runs-out-of-heap/README.md) | Open. Four tenants in three classes, across two documentation-only changes — one of them this item's own. What a tenant costs IS measured: 226 MB for a face's first, 11 MB for the next on it. The floor is attributed now — twelve classes keep 1755 MB and seventy more keep 477, against a 2 GB heap. Nothing is being left unclosed: the floor is the FHIR definition corpus — model objects, their strings, and those strings' arrays, over half the live heap. It is the toolchain's SimpleWorkerContext, one per version; the store's own definitions have been in a database schema since before this was filed. Next: what still needs that context. |
| [020 The insurer will not come up beside the zone](020-the-insurer-will-not-come-up-beside-the-zone/README.md) | Fixed. A projection hands one canonical down one dependency under two ids; the stream read the second as a stale claim of its own and threw. The apply path now asks whether the content is identical first. Kept until CI has run it a few times. |
| [019 The build repeats work whose inputs did not change](019-the-build-repeats-itself/README.md) | Open. CI has said what the cache is worth: a docs-only pull request went 44 to 37 minutes, one touching sources went to 45. The remaining forty minutes is the container suites, which no cache can reach. Next: items 002 and 003. |
| [024 Definitions out of the heap](024-definitions-out-of-the-heap/README.md) | Open. Nothing is built. A version's definitions are parsed into a HAPI worker context once per face and held while any tenant on that face is up — 226 MB, against 11 MB for the second tenant. The store's own copy is already a database schema read with SQL, so this is the toolchain's second one. Nothing is built at arrival and dropped — every holder but the parity references serves — and the other half is already built: FaceBase is a context made from the records a tenant holds, shared per face, taken by any tenant with a version root. Weighed: 101 MB against 225, and 3 against 11 for the next tenant on each — so 226 is what the fallback costs, not what definitions cost. The criterion is that a new face must not cost another hundred megabytes; measured, a second face costs 444 on top of 225 for the first. Both paths build the same object graph, so the aim is a serving process holding neither. Three of the four pieces exist already — a definitions schema the face's SQL reads, a database at parity on tier one and the envelope, and an image cut once per release carrying everything derived. Next: conversion as a step dbo provides rather than a projection tenant holding a face — nothing synchronous waits on a converter today, which is what makes it available. |
| [018 The insurer's copy does not arrive](018-the-insurers-copy-does-not-arrive/README.md) | Open. The numbers are off CI and the insurer is not the problem: through the projection it is met at attempt 2 of 120, identically in four guide jobs. The hospital's direct wait is the long one at 6 to 8, absorbing the world's start-up. Missing is the one job it ever failed in, because the last two builds died in phase one on item 023's heap. Next: a green build. |

## Risks

- `./verify` stops the Gradle daemon between its phases, and the daemon is
  shared across every worktree of this repository, so a verify in one
  checkout kills a suite running in another. The migration runs its phases
  with `--no-daemon` for this reason; nothing fixes it.
- The guide's compose file pins a server image that a person moves by
  hand, and a guide step asserting behaviour newer than the pin fails for a
  reason unrelated to the step. Item 004 carries the question of who moves
  it.
- **A test that scans once and then uses what it declared is a flake waiting
  for a loaded runner.** A pass is one reconciliation, not a promise that it
  finished, so the tenant answers 404 — which is what a tenant nobody
  declared answers, so the class dies in its setup naming neither. `ZoneIT`
  did exactly this and is fixed; the door is `UntilServed.scan`, which waits
  and which fails loudly with the runtime's own reason. Around twenty other
  calls to `scanOnce` remain and are NOT all this mistake: a test about the
  scan itself asserts what one pass returned, correctly. Which are which is
  read, not grepped.
- **The suite fails non-deterministically in one known place, so a red build
  is not by itself a regression.**
  `SeveralTenantsDeclaredAtOnceComeUpTogetherIT` brings four tenants up at
  once and has died as Java heap exhaustion inside one of them, on a change
  that touched documentation only. It is no longer one class's problem —
  two more failed the same way in one run — and it is now
  [item 023](023-the-suite-runs-out-of-heap/README.md). The guide's terminology step has now failed twice
  on one commit and is [item 018](018-the-insurers-copy-does-not-arrive/README.md).
- What is built, as against what is promised, is read from the
  [requirement catalogue](../arc42-006-runtime/req-catalogue.md) and the
  tests it cites. The page that used to answer that in prose was typed by
  hand and had drifted.
