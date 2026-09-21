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
| [001 The documentation tree is moved to match its map](001-documentation-shape/README.md) | Open. Next: write the runtime chapter's scenarios and create the quality-requirements chapter. |
| [002 The sample application](002-sample-application/README.md) | Open. The module owns its sources and the world's specs, and two runners join it. Next: the actor surface, so a story's steps are calls and the promises come as consequences. |
| [003 Own-world tests move down the ladder](003-tests-move-down-the-ladder/README.md) | Open. The cast has started and the first class has moved onto it: delegation runs on the hospital. Next: read the remaining fourteen single-use shapes against what a cast can hold. |
| [004 The guide runs three times in CI](004-the-guide-runs-three-times/README.md) | Open. Next: port the one step the shell harness still covers. |
| [006 The specification is cut to the house style](006-the-specification-in-house-style/README.md) | Open. Next: run the prose reviewer over the user stories, the highest count. |
| [007 The face contract](007-the-face-contract/README.md) | Open. The epic closed and a second face exists; one slice remains. |
| [008 IHE profiles](008-ihe-profiles/README.md) | Not scheduled. Analysis only: no profiled surface is served and no issue is filed. |
| [009 The step-scoped API](009-the-step-scoped-api/README.md) | Open. The store promises that reaching data means performing a step, and a plain read still bypasses it. |
| [010 Tenant kinds](010-tenant-kinds/README.md) | Open. A tenant's kind is re-derived at every call site instead of being declared. |
| [011 UBL as a face](011-ubl-as-a-face/README.md) | Open. Three spikes are green and in the tree; nothing is built. |
| [012 Work without a poll](012-work-without-a-poll/README.md) | Open. The third binding the issue asks for exists; what remains is what the issue was really about. |
| [013 A neutral IFC repository](013-a-neutral-ifc-repository/README.md) | Not scheduled. Recorded so the reasoning exists before somebody needs it. |
| [014 The Karaf console](014-the-karaf-console/README.md) | Not scheduled. A proposal for seeing inside a running node: development and operator tooling, never production. |
| [015 The comparative load test](015-the-comparative-load-test/README.md) | Not scheduled. The bench runner carries the discipline; a second and third target, an ingest workload and resource sampling are missing. |
| [016 Where a neutral store earns its keep](016-where-a-neutral-store-earns-its-keep/README.md) | Not scheduled. A test for recognising the domains this engine's shape fits. |
| [017 The quality goals are not declared](017-quality-goals-are-not-declared/README.md) | Open. The eleven quality goals are matched to requirement areas by reading. Next: declare them as Quality classifications. |
| [020 The insurer will not come up beside the zone](020-the-insurer-will-not-come-up-beside-the-zone/README.md) | Fixed. A projection hands one canonical down one dependency under two ids; the stream read the second as a stale claim of its own and threw. The apply path now asks whether the content is identical first. Kept until CI has run it a few times. |
| [019 The build repeats work whose inputs did not change](019-the-build-repeats-itself/README.md) | Open. The cache is on, the container tests are kept out of it, and three module test tasks went 342s to 28s. Next: watch a docs-only pull request in CI. |
| [018 The insurer's copy does not arrive](018-the-insurers-copy-does-not-arrive/README.md) | Open. Slow, not stopped: the carrier is a projection tenant that spends twenty-eight seconds reading a face through the chain. Next: move the pin, which has face images. |

## Risks

- `./verify` stops the Gradle daemon between its phases, and the daemon is
  shared across every worktree of this repository, so a verify in one
  checkout kills a suite running in another. The migration runs its phases
  with `--no-daemon` for this reason; nothing fixes it.
- The guide's compose file pins a server image that a person moves by
  hand, and a guide step asserting behaviour newer than the pin fails for a
  reason unrelated to the step. Item 004 carries the question of who moves
  it.
- **The suite fails non-deterministically in one known place, so a red build
  is not by itself a regression.**
  `SeveralTenantsDeclaredAtOnceComeUpTogetherIT` brings four tenants up at
  once and has died as Java heap exhaustion inside one of them, on a change
  that touched documentation only. It has no item because it has not been
  reproduced deliberately. The guide's terminology step has now failed twice
  on one commit and is [item 018](018-the-insurers-copy-does-not-arrive/README.md).
- What is built, as against what is promised, is read from the
  [requirement catalogue](../arc42-006-runtime/req-catalogue.md) and the
  tests it cites. The page that used to answer that in prose was typed by
  hand and had drifted.
