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
| [001 The documentation tree is moved to match its map](001-documentation-shape/README.md) | Open. The context chapter and the worked deployment are done. Two disagreements are left and both wait on item 002: the guide is shell commands where the map says it is the sample's story, and `using-dbo.md` is a reference where the map says it is that sample's README. The second had no step and has one now. |
| [002 The sample application](002-sample-application/README.md) | Open. The actor surface exists and two scenes run over it, each having caught an assumption an in-JVM scene would have let stand. Next: the guide's chapters, one at a time, over that surface. |
| [003 Own-world tests move down the ladder](003-tests-move-down-the-ladder/README.md) | Open. The cast has started and one class has moved onto it: delegation runs on the hospital. Four families read against it since, each resisting for a named reason. Next: the shapes kept apart only by what the sample world declares. |
| [004 The guide runs three times in CI](004-the-guide-runs-three-times/README.md) | Open. The step is ported, the shell harness is gone from the tree run, and who moves the pin is written down. Decided on a count: the suite runs ninety-eight of the ninety-nine published snippets, so the guide's shell run's unique coverage is one command, while the quickstart's own run covers a compose file nothing else touches. Next: that command, then the deletion. |
| [006 The specification is cut to the house style](006-the-specification-in-house-style/README.md) | Open. The stories' authored prose is cut. The promise text was measured rather than cut: 7.5 per thousand whole, where the working rules are 7, and the one edit worth making was a promise reciting its neighbours. The crosscutting pages measure 7.5 too. The counter was reading code as prose and is fixed: the tree is 7.0 with no section above 8.5. Next: the guide, whose chapter list item 021 settles. |
| [008 IHE profiles](008-ihe-profiles/README.md) | Not scheduled. Analysis only: no profiled surface is served and no issue is filed. |
| [009 The step-scoped API](009-the-step-scoped-api/README.md) | Open. The store promises that reaching data means performing a step, and a plain read still bypasses it. The traversal half is item 021's to design: both items defer one question, and one answer has to serve both doors. |
| [010 Tenant kinds](010-tenant-kinds/README.md) | Open, and smaller than its title. All three costs it was filed for are closed and none was closed by a kind; the design is superseded by the activity selectors built meanwhile, and that finding has moved to the guide. What is left is a coarse label an outside bundle could select on, which nothing asks for. |
| [011 UBL is a face, described in FHIR's own tools](011-ubl-as-a-face/README.md) | Open. Three spikes are green and in the tree; nothing is built. The one open question narrowed: holding a signed document's received bytes as its truth keeps the store's promise about projections, and what it needs is a projection that is a whole document — which is the store's design to make, not UBL's. |
| [013 A neutral IFC repository](013-a-neutral-ifc-repository/README.md) | Not scheduled. Recorded so the reasoning exists before somebody needs it. |
| [014 The Karaf console](014-the-karaf-console/README.md) | Not scheduled. A proposal for seeing inside a running node: development and operator tooling, never production. |
| [015 The comparative load test](015-the-comparative-load-test/README.md) | Not scheduled. The bench runner carries the discipline; a second and third target, an ingest workload and resource sampling are missing. |
| [016 Where a neutral store earns its keep](016-where-a-neutral-store-earns-its-keep/README.md) | Not scheduled. A test for recognising the domains this engine's shape fits. |
| [017 Quality coverage is not folded](017-quality-coverage-is-not-folded/README.md) | Open. The twelve goals are declared and the tree is generated from them under a ratchet. Performance reads 1/6, and reading it out says four of the five missing points are PLANNED scaling promises no item describes — the figures item 015 would take are the fifth. |
| [021 Asking the store a question](021-asking-the-store/README.md) | Open. The vocabulary exists with two bindings a caller cannot tell apart, now for work as well as records: the surface serves a run search, and both hand back an Ongoing rather than a Run whose other eleven fields only one binding could fill. The chapter reads over a ward the sample compiles, and asking against unsealing is settled where the invariant it seemed to contradict is stated. Next: joins. |
| [023 The suite runs out of heap](023-the-suite-runs-out-of-heap/README.md) | Open. Four tenants in three classes, across two documentation-only changes — one of them this item's own. What a tenant costs IS measured: 226 MB for a face's first, 11 MB for the next on it. The floor is attributed now — twelve classes keep 1755 MB and seventy more keep 477, against a 2 GB heap. Nothing is being left unclosed: the floor is the FHIR definition corpus — model objects, their strings, and those strings' arrays, over half the live heap. It is the toolchain's SimpleWorkerContext, one per version; the store's own definitions have been in a database schema since before this was filed. Next: what still needs that context. |
| [019 The build repeats work whose inputs did not change](019-the-build-repeats-itself/README.md) | Open. The cache is on and CI has said what it is worth: thirty-seven minutes against forty-four on a documentation-only change, and nothing on one touching sources. Nearly all the remaining time is one task, so `org.gradle.parallel` cannot reach it and the only dial that can is a second fork. Next: items 002 and 003, or that one measurement. |
| [024 Definitions out of the heap](024-definitions-out-of-the-heap/README.md) | Open. A second face costs 444 MB on top of 225 for the first. Seven of the nine moves on the critical path are done, most closed by reading rather than by work, and ElementStore has no serving reaches left. What remains is a write judged by the database, then the context itself — and no megabyte moves until the ninth. |

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
  [item 023](023-the-suite-runs-out-of-heap/README.md). The guide's terminology
  step failed twice on one commit the same way; the pin it was waiting for
  landed, and on a runner carrying the whole suite the insurer is now met at
  the second attempt of a hundred and twenty.
- What is built, as against what is promised, is read from the
  [requirement catalogue](../arc42-006-runtime/req-catalogue.md) and the
  tests it cites. The page that used to answer that in prose was typed by
  hand and had drifted.
