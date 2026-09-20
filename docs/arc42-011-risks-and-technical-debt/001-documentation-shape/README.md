**Open. Next: redo the context chapter with the three-level landscape.**

# The documentation tree is moved to match its map

The [documentation rule](../../arc42-002-constraints/working-rules/documentation.md#where-a-thing-is-written)
says where each kind of documentation is written. The tree was shaped before
the map, and disagrees with it in the ways listed here. Each step below
closes one disagreement, lands as its own change, and leaves the site
building. This item is deleted when the list is empty.

## Where the tree disagrees

- `arc42-003-context` has a README and user stories, and no three-level
  landscape; the stories do not cite Story constants.
- `arc42-007-deployment` has its diagrams; the worked deployment is a
  hand-written site page rather than the guide world's compose file.
- `docs/guide/` is written as shell commands a reader runs against the
  world. The map says it is the sample application's story, and there is no
  sample application.
- `docs/using-dbo.md` is a reference a builder reads; the map says it is the
  sample's README.

## The steps, in order

1. Rewrite the guide one chapter at a time as the sample application's
   story, each chapter including the sample's source and replacing one
   shell chapter when it lands. The sample itself is
   [item 002](../002-sample-application/README.md); this step waits for it.

Step 1 is the largest and depends on nothing above it; it is last so that
the sample is written against a tree that holds still.

## How a step lands

A step is one commit, and the site builds strictly at every one of them. A
pull request may carry several steps while they are all documentation,
because the suite behind a merge takes forty minutes and spending four of
those on four markdown changes buys nothing. The docs index lists what
moved. The map in the documentation rule is not edited to match the tree;
when the map is wrong, that is a change to the rule and its skill, made
deliberately.
