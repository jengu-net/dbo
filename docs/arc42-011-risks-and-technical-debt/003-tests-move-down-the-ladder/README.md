**Open. 44 harness classes build a runtime of their own and are undecided. Next: classify them into rungs.**

# Own-world tests move down the ladder

`config/worlds-ledger.txt` records every harness class that builds a
runtime of its own. Forty-four predate the ledger and carry no reason; the
[shared-world rule](../../arc42-002-constraints/working-rules/shared-world-tests.md)
says which rung each should be on, and the number may only fall.

## Which class, in which order

`./gradlew :core:harness:storyCoverage` reports where each story's legs are
proven. That is the order to work in: take a story, look at the legs it
proves only in a world built for one class, and read those classes before
moving them. Two stories have been read this way.

**Fleet health keeps everything it has.** All three of its private-world
classes call the deployment's own scan, which cannot join a shared world
because it visits every tenant in the runtime. They are recorded as sweeps.

**Person rights is four and three.** Four of its classes sweep and are
recorded. The three that remain are the story's real worklist:
`ThePlaintextInFlightLeavesNoTraceIT`, which wants a tenant nothing else has
written to rather than a runtime of its own, and `HumanAuthIT` and
`FederatedAuthIT`, which want an authority and clients of their own and can
have both on a shared runtime.

**Two places is all sweeps.** Every leg it proves privately is proven by a
class that calls a deployment pass, and the four not already recorded are
now. Read the report's rows carefully here: it lists every place a leg is
proven, so a row naming a private world may still have a cheaper proof
beside it. What is owed is the rows naming a private world and nothing else.

**Clinical record is one sweep and two unproven legs.** Its single private
world belongs to a class that runs a shapes pass, so it stays. Its other two
outstanding legs are proven nowhere because nothing cites them yet: both are
PLANNED in the catalogue, which is a gap in what is built rather than a test
in the wrong world, and moving tests will never close it.

**Edge roundtrip is one sweep and two whole-plane assertions.** The sweep is
the usual case. The other two read every table of a substrate to say nothing
readable landed there, which is a claim about the entire plane rather than
about their own tenant — on a shared runtime it would be a claim about every
other class's work. That is a sixth reason, added to the ledger by finding
it rather than by imagining it.

All eight stories have now been read this way, and not one has produced a
test to move. What the reading produced instead is two reasons the ledger
did not have, four stories whose private worlds are correct, one defect in
the report, and one defect in the store. The three authentication classes
person rights named remain the only genuine candidates.

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
