**Open. Next: replace the citations of a decision record by its section number.**

# The documentation tree is moved to match its map

The [documentation rule](../../arc42-002-constraints/working-rules/documentation.md#where-a-thing-is-written)
says where each kind of documentation is written. The tree was shaped before
the map, and disagrees with it in the ways listed here. Each step below
closes one disagreement, lands as its own change, and leaves the site
building. This item is deleted when the list is empty.

## Where the tree disagrees

- Forty-two Java files and several documents cite a decision record by its
  dissolved section number. The map says a record is cited from nowhere, so
  each of those names the concept page or the REQ that states the result
  instead.
- Records 006 and 007 are reflected in no page: the adoption path and the
  model behind a face were decided and never stated as current fact.
- `arc42-006-runtime` holds the requirement catalogue and no scenarios.
- `arc42-010-quality-requirements` does not exist; the conformance reports
  and the evidence sit under `docs/conformance` and `docs/evidence`.
- `arc42-003-context` has a README and user stories, and no three-level
  landscape; the stories do not cite Story constants.
- `arc42-005-building-blocks` describes the modules in prose, with no
  container or component diagram and no generated module map.
- `arc42-007-deployment` has its diagrams; the worked deployment is a
  hand-written site page rather than the guide world's compose file.
- Thirteen `why-*.md` essays under `arc42-008-crosscutting` paraphrase the
  guide and are collected into a Why menu the map does not have. The build
  carries an essay collector, rank front matter and link reframing for it.
- `docs/guide/` is written as shell commands a reader runs against the
  world. The map says it is the sample application's story, and there is no
  sample application.
- `docs/using-dbo.md` is a reference a builder reads; the map says it is the
  sample's README.

## The steps, in order

1. Replace every citation of a decision record by its section number with
   the page or the REQ that states the result, and state the results of
   records 006 and 007 where they belong.
2. Write the 006 scenarios, one Lini sequence each, and create 010 with the
   quality tree, the conformance reports and the evidence. Give the C4
   diagrams one shared Lini stylesheet head.
3. Redo 003 with the three-level landscape, and make each user story cite
   its Story constant.
4. Draw the 005 container and component diagrams, and generate the module
   map from the build with a ratchet like the other projections.
5. Write the pitch on the landing page, remove the Why menu, delete the
   essays, folding what survives into the concept READMEs, and remove the
   collector from the build.
6. Rewrite the guide one chapter at a time as the sample application's
   story, each chapter including the sample's source and replacing one
   shell chapter when it lands. The sample itself is
   [item 002](../002-sample-application/README.md); this step waits for it.

Step 6 is the largest and depends on nothing above it; it is last so that
the sample is written against a tree that holds still.

## How a step lands

One pull request per step. The site builds strictly at every step, the
docs index lists what moved, and the map in the documentation rule is not
edited to match the tree; when the map is wrong, that is a change to the
rule and its skill, made deliberately.
