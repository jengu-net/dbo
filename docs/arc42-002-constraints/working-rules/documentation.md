# Documentation

`docs/` is the specification, structured per arc42. It is read in two
places: on GitHub, as plain markdown; and on the site, which `./gradlew site`
assembles from `docs/`, from the hand-written pages in `site/pages/`, and
from what the build generates. A page is written for where it is stored, and
the assembly repairs the frame for pages it moves.

## What the assembly does

- The tree is mounted under `/docs/`. Every page in it is published.
- A `why-*.md` essay lives beside the concept it argues and is published
  there. It is a long-form argument for one idea, where the concept's README
  is the reference.
- A `pattern-*.md` under `arc42-008-crosscutting/patterns/` is published
  where it lives, and its `pattern: <rank>` orders the section rather than
  the alphabet. The directory's README is the section index.
- Nothing is published anywhere other than where it is stored. A page is
  written for its own location, and every relative link in it resolves both
  on GitHub and on the site.
- A directory with a `README.md` and no `.nav.yml` takes the README's
  heading as its section title. A `.nav.yml` orders a section by hand; the
  guide has one.
- `mkdocs build --strict` fails on a link that names a file which is not
  there. A link to a directory resolves to its index.
- A figure is included with `--8<-- "assets/diagrams/<name>.svg"`. The SVGs
  sit in one flat directory whatever concept owns the source
  ([diagrams](diagrams.md)).
- `docs/README.md` carries front matter with a permalink; the assembly
  strips it.

## Where a thing is written

One place per kind of thing. Where the tree disagrees with this map, the
map is the rule and the disagreement is an item in
[risks and technical debt](../../arc42-011-risks-and-technical-debt/README.md).

| Place | Holds |
|---|---|
| `arc42-001-introduction` | What the store is, its goals, the founding requirements. |
| `arc42-002-constraints` | The rules the store and the work must meet: constraints, the promise model, the working rules. |
| `arc42-003-context` | The landscape at three levels, drawn as a C4 landscape: the store and what it depends on, the system built on it, and that system's actors. User stories, each citing its Story constant and playing on the guide world. |
| `arc42-004-solution-strategy` | The decisions as current fact, without reasoning. Edited whenever a decision, a constraint or the context changes. |
| `arc42-005-building-blocks` | Containers and components, drawn as C4, with the module map generated from the build. |
| `arc42-006-runtime` | Scenarios: how the blocks interact for the cases that matter, one sequence each. The requirement catalogue keeps its address here. |
| `arc42-007-deployment` | A deployment diagram per shape the store runs in. The worked deployment is the guide world's compose file, included. |
| `arc42-008-crosscutting` | One README per concept, a `why-` essay beside it where one idea earns a long argument, and the patterns as vocabulary. |
| `arc42-009-architecture-decisions` | Numbered records with a status and a "reflected in" line naming the page that states the result. History only; cited from nowhere. |
| `arc42-010-quality-requirements` | The quality tree as Quality classifications, the conformance reports, the evidence. |
| `arc42-011-risks-and-technical-debt` | Numbered items, each a directory with a README whose first line is its state. The chapter README lists every item with that line. A resolved item is deleted; the commit or the decision record is its record. |
| `guide/` | The sample application's story, chapter by chapter: declaring a tenant, writing a step service, a participant joining from outside, and on. A chapter includes the sample's source. HTTP appears only in the external participant's chapter. |
| `using-dbo.md` | The sample application's README: what the store provided and the sample did not write. |
| The site's landing page | The pitch, in full. There is no Why section. |

## What a page says

The current state, in the words the store uses. The `§` numbers are decoded
in the docs index; a reference names a REQ or a document. The docs index
lists every section and is edited when one is added, moved or removed.

## Checking a page

`./gradlew site` renders the tree and fails on what `--strict` reports.
`./gradlew siteServe` serves it at localhost:8000 with live reload.

<!-- skill: dbo-docs -->
```yaml
name: dbo-docs
applies-when: >-
  Adding, moving or editing a page under docs/, guide/ or site/pages/, a
  link into any of them, or deciding where a piece of documentation goes.
reference: docs/arc42-002-constraints/working-rules/documentation.md
```
**Rules**
- MUST write a page for where it is stored: relative links to siblings,
  `README.md` as a section index. The assembly repairs the frame for pages
  it collects.
- MUST NOT link relatively from a page under `docs/` to a file outside it;
  use the absolute repository URL.
- MUST give a pattern page a `pattern:` rank in its front matter.
- MUST reference behaviour by REQ code or by document, never by a `§`
  number.
- MUST write a thing where the map in this document says, and record a
  disagreement between the map and the tree as an item in 011 rather than
  following the tree.
- MUST show a whole file with `--8<--` rather than retyping it, and MUST
  name the source on the fence — ```` ```json title="sample/world/..." ````
  — when quoting a few of its lines. A titled fence is checked against the
  file it names; an untyped copy drifts silently.
- MUST update the docs index when a document is added, moved or removed.
- MUST run `./gradlew site` after editing and fix what `--strict` reports.
<!-- /skill -->
