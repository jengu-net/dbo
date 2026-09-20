**Open. 44 harness classes build a runtime of their own and are undecided. Next: classify them into rungs.**

# Own-world tests move down the ladder

`config/worlds-ledger.txt` records every harness class that builds a
runtime of its own. Forty-four predate the ledger and carry no reason; the
[shared-world rule](../../arc42-002-constraints/working-rules/shared-world-tests.md)
says which rung each should be on, and the number may only fall.

## How it is run

The migration has a branch that outlives its merges, a worktree of its own,
and a trimmed CI job that runs the moved tests together. That is described
in [how the migration is run](how-it-is-run.md), which moved here from
`docs/tasks/`.

## Steps

1. Classify the 44 into rungs from their source: a guide step, a shared
   shape, a private tenant on the shared runtime, or one of the five
   reasons to keep a runtime. Record the result as the worklist here.
2. Move them, one or a few per change, re-recording the ledger each time so
   the allowance falls.
3. Give the classes that keep a runtime their reason in the ledger, so
   undecided reaches zero and this item is deleted.
