**Open. There are twelve goals, not eleven — the twelfth had no row in the quality tree at all, which is this item's own argument arriving by accident. They are matched to areas by reading. Next: declare them as Quality classifications.**

# The quality goals are not declared

[The quality tree](../../arc42-010-quality-requirements/README.md) maps each
goal to the areas that carry it, and the mapping lives in a table a person
wrote.

How well that goes is already on the record. The introduction lists twelve
goals; the table had eleven rows, and the missing one was the last — operational
honesty, which is strict search, an honest CapabilityStatement and an entry per
boundary crossing. It has a row now. Nothing could have noticed it was gone,
because a table is a list somebody keeps in step by hand, and this item is
about not doing that. The promise model already has the classification this wants: a
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
