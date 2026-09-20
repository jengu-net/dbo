# Risks and technical debt

The outstanding work, as numbered items. Each item is a directory holding a
README whose first line is the item's state, and whatever the item needs
beside it: a measurement, a listing, a trace. A resolved item is deleted;
the commit that resolved it, or the decision record it produced, is its
record.

A risk that has not become an item is listed under Risks below, in one
sentence, until it either becomes one or stops being a risk.

## Items

| Item | State |
|---|---|
| [001 The documentation tree is moved to match its map](001-documentation-shape/README.md) | Open. Next: turn the architecture decisions into numbered records and cut the solution strategy to current state. |
| [002 The sample application](002-sample-application/README.md) | Open. Waits for nothing; starts when item 001 has reached its last step. |
| [003 Own-world tests move down the ladder](003-tests-move-down-the-ladder/README.md) | Open. 44 harness classes build a runtime of their own and are undecided. Next: classify them into rungs. |
| [004 The guide runs three times in CI](004-the-guide-runs-three-times/README.md) | Open. Next: port the one step the shell harness still covers. |
| [005 Re-recording runs before the commit](005-re-recording-before-the-commit/README.md) | Open. Five projections are re-recorded by hand. Next: one task that runs all five. |
| [006 The specification is cut to the house style](006-the-specification-in-house-style/README.md) | Open. Next: run the prose reviewer over the user stories, the highest count. |

## Risks

- The implementation status page's counts are typed by hand and have
  drifted; the catalogue is the checked answer. Resolved by item 001.
- `./verify` stops the Gradle daemon between its phases, and the daemon is
  shared across every worktree of this repository, so a verify in one
  checkout kills a suite running in another. The migration runs its phases
  with `--no-daemon` for this reason; nothing fixes it.
- The guide's compose file pins a server image that a person moves by
  hand, and a guide step asserting behaviour newer than the pin fails for a
  reason unrelated to the step. Item 004 carries the question of who moves
  it.
