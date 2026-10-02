**Open. The context chapter, the worked deployment and the guide are done:
the guide is the sample applications' story, one chapter per user story,
quoting their source. One disagreement is left — `using-dbo.md` is a reference
where the map says it is the samples' README — and it waits on nothing now.**

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
- ~~`docs/guide/` is written as shell commands a reader runs against the
  world.~~ Closed. It is the sample applications' story, a chapter per user
  story, each quoting `samples/` rather than retyping it.
- `docs/using-dbo.md` is a reference a builder reads; the map says it is the
  samples' README. **And it had no step**, which is worth saying because this
  item is deleted when the list is empty and a disagreement nobody scheduled
  could never leave it. It has one now, below. The samples' READMEs say how to
  run them; `docs/using-dbo.md` is organised by capability, the `dbo-using` skill
  projects from it, `docs/README.md` points at it, and a Gradle input names
  it — so the move is four references and a rewrite rather than a `git mv`.

## The steps, in order

1. ~~Move the worked deployment into `arc42-007-deployment`, built on the
   guide world's compose file rather than describing a deployment of its
   own.~~ Done. The site page is gone rather than made a pointer — a page
   whose whole content is "it is over there" is a third thing to keep in
   step — and the technical index links into the chapter instead.
2. ~~Rewrite the guide as the sample application's story.~~ Done: one
   chapter per user story, quoting the sample applications, the sample world
   and the story tests.

3. Rewrite `docs/using-dbo.md` as the samples' README — what the store
   provided and the applications did not write — and move the four references
   with it: the `dbo-using` skill's source, the docs index, the working-rules
   index and the Gradle input that reads it.

   It waits on nothing now: the applications are written and their tests
   are the stories, so what they did not have to write can be read off them.

## How a step lands

A step is one commit, and the site builds strictly at every one of them. A
pull request may carry several steps while they are all documentation,
because the suite behind a merge takes forty minutes and spending four of
those on four markdown changes buys nothing. The docs index lists what
moved. The map in the documentation rule is not edited to match the tree;
when the map is wrong, that is a change to the rule and its skill, made
deliberately.
