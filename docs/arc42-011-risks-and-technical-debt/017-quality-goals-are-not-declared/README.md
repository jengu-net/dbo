**Open. The eleven quality goals are matched to requirement areas by reading. Next: declare them as Quality classifications.**

# The quality goals are not declared

[The quality tree](../../arc42-010-quality-requirements/README.md) maps each
goal to the areas that carry it, and the mapping lives in a table a person
wrote. The promise model already has the classification this wants: a
**Quality** is the technical view of a promise, it declares the promises that
fulfil it, and coverage is folded from their statuses rather than asserted.

So nothing computes a goal's coverage, and nothing fails when a goal loses
its last proof.

## Steps

1. Declare a `DboQualities` catalogue, one constant per goal, each declaring
   the promises that fulfil it. The enum is exported, so the change moves
   the API ledger and the catalogue projection in the same commit.
2. Replace the tree's hand-written table with the projection, under the same
   ratchet the requirement catalogue is under.
3. Take the performance figures the goal asks for, which is
   [item 015](../015-the-comparative-load-test/README.md), and cite them from
   the quality that needs them.

## What this is not

It is not a second catalogue of behaviour. A quality declares promises that
already exist; it states no behaviour of its own, and a goal with nothing to
declare is a goal this store has not yet made true.
