**Open. The context chapter is done, and so is the worked deployment: it is a
chapter now, built on the guide world's compose file. Two disagreements are
left and both wait on item 002 — the guide is shell commands where the map says
it is the sample application's story, and `using-dbo.md` is a reference where
the map says it is that sample's README. The second had no step and has one
now; an item deleted when its list empties cannot empty a line nobody
scheduled.**

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
- ~~`arc42-007-deployment` has its diagrams; the worked deployment is a
  hand-written site page rather than the guide world's compose file.~~ Closed.
  It is `arc42-007-deployment/a-worked-deployment.md`, and it opens on the
  compose file itself. The world turned out to be richer than the page that
  described one: seven tenants rather than five, three organisations rather
  than two, and two face roots — so the sentence about a second face root
  being another version stopped being hypothetical and became the insurer,
  a release behind, taking the zone through a projection.
- `docs/guide/` is written as shell commands a reader runs against the
  world. The map says it is the sample application's story, and there is no
  sample application.
- `docs/using-dbo.md` is a reference a builder reads; the map says it is the
  sample's README. **And it had no step**, which is worth saying because this
  item is deleted when the list is empty and a disagreement nobody scheduled
  could never leave it. It has one now, below. `sample/README.md` does not
  exist; `docs/using-dbo.md` is organised by capability, the `dbo-using` skill
  projects from it, `docs/README.md` points at it, and a Gradle input names
  it — so the move is four references and a rewrite rather than a `git mv`.

## The steps, in order

1. ~~Move the worked deployment into `arc42-007-deployment`, built on the
   guide world's compose file rather than describing a deployment of its
   own.~~ Done. The site page is gone rather than made a pointer — a page
   whose whole content is "it is over there" is a third thing to keep in
   step — and the technical index links into the chapter instead.
2. Rewrite the guide one chapter at a time as the sample application's
   story, each chapter including the sample's source and replacing one
   shell chapter when it lands. The sample itself is
   [item 002](../002-sample-application/README.md); this step waits for it.

   **Which chapters there should be is
   [item 021](../021-asking-the-store/README.md).** The Core group is
   organised by store feature and the map says the guide is a story, and those
   are different things — the first chapter where they part company is
   `search.md`, which walks a query surface FHIR documents. Converting a
   chapter that should not exist is the one way this step can waste work, so
   the list is settled there first.

3. Rewrite `docs/using-dbo.md` as the sample's README — what the store
   provided and the sample did not write — and move the four references with
   it: the `dbo-using` skill's source, the docs index, the working-rules index
   and the Gradle input that reads it.

   **After step 2, not beside it.** Today the document is organised by
   capability, which is the shape of a reference; as a README it is organised
   by what the sample would otherwise have had to write. That second list is
   read off the sample, and the sample is still growing a class per chapter —
   `Observing` arrived with References. Writing it now means writing it
   against a sample that is a third of its eventual size, which is the one way
   this step can waste work, in the same way converting a chapter that should
   not exist is the one way step 2 can.

Step 2 is the largest, and with step 3 it is no longer the only one. Both wait
on [item 002](../002-sample-application/README.md) rather than on anything
here, so this item is finished when that one is.

## How a step lands

A step is one commit, and the site builds strictly at every one of them. A
pull request may carry several steps while they are all documentation,
because the suite behind a merge takes forty minutes and spending four of
those on four markdown changes buys nothing. The docs index lists what
moved. The map in the documentation rule is not edited to match the tree;
when the map is wrong, that is a change to the rule and its skill, made
deliberately.
