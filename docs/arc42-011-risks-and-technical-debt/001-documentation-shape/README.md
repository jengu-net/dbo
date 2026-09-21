**Open. The context chapter is done — the landscape is drawn and every story cites its constant and projects its joins. Two disagreements are left and one of them had no step: the worked deployment is a hand-written site page, not the guide world's compose file. Next: move it.**

# The documentation tree is moved to match its map

The [documentation rule](../../arc42-002-constraints/working-rules/documentation.md#where-a-thing-is-written)
says where each kind of documentation is written. The tree was shaped before
the map, and disagrees with it in the ways listed here. Each step below
closes one disagreement, lands as its own change, and leaves the site
building. This item is deleted when the list is empty.

## Where the tree disagrees

- ~~`arc42-003-context` has a README and user stories, and no three-level
  landscape; the stories do not cite Story constants.~~ Closed. The chapter
  opens on the landscape — four bands, and the relationship that matters most
  being the one that skips a band — and each story is a `DboStories` constant
  whose joins table is projected from it rather than written.
- `arc42-007-deployment` has its diagrams; the worked deployment is a
  hand-written site page rather than the guide world's compose file. It is
  `site/pages/technical/a-worked-deployment.md`: two hundred lines describing
  five tenants of three kinds, in prose, beside a world whose compose file
  declares exactly that and is run by three CI jobs.
- `docs/guide/` is written as shell commands a reader runs against the
  world. The map says it is the sample application's story, and there is no
  sample application.
- `docs/using-dbo.md` is a reference a builder reads; the map says it is the
  sample's README.

## The steps, in order

1. Move the worked deployment into `arc42-007-deployment`, built on the guide
   world's compose file rather than describing a deployment of its own. What
   the page argues — two separations that are not the same one, five tenants
   of three kinds — is what that world already IS, so the prose stops being a
   second description that can drift and becomes commentary on an included
   file that three CI jobs run. The site page becomes a pointer, because a
   reader arriving at the site still needs to be taken there.
2. Rewrite the guide one chapter at a time as the sample application's
   story, each chapter including the sample's source and replacing one
   shell chapter when it lands. The sample itself is
   [item 002](../002-sample-application/README.md); this step waits for it.

Step 2 is the largest and depends on nothing above it; it is last so that
the sample is written against a tree that holds still.

Step 1 had no step at all until now — the disagreement was listed and
nothing said what to do about it, which is how a list of disagreements
quietly becomes a list of observations.

## How a step lands

A step is one commit, and the site builds strictly at every one of them. A
pull request may carry several steps while they are all documentation,
because the suite behind a merge takes forty minutes and spending four of
those on four markdown changes buys nothing. The docs index lists what
moved. The map in the documentation rule is not edited to match the tree;
when the map is wrong, that is a change to the rule and its skill, made
deliberately.
