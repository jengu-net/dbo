# Documentation

`docs/` is the specification, structured per arc42. It is read in two
places: on GitHub, as plain markdown; and on the site, which `./gradlew site`
assembles from `docs/`, from the hand-written pages in `site/pages/`, and
from what the build generates. A page is written for where it is stored, and
the assembly repairs the frame for pages it moves.

## What the assembly does

- The tree is mounted under `/docs/`. `docs/tasks/` and `docs/plans/` are not
  published, so a published page reaches either only by an absolute
  repository URL.
- A `why-*.md` essay lives beside the concept it argues and is collected to
  `/why/`. Its front matter carries `why: <rank>`, which orders the section.
- A `pattern-*.md` under `arc42-008-crosscutting/patterns/` is collected to
  `/patterns/` by `pattern: <rank>`. The directory's README is the section
  index.
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

## What a page says

The current state, in the words the store uses. The `§` numbers are decoded
in the docs index; a new reference names a REQ or a document. The docs index
lists every section and is edited when one is added, moved or removed.

## Checking a page

`./gradlew site` renders the tree and fails on what `--strict` reports.
`./gradlew siteServe` serves it at localhost:8000 with live reload.

<!-- skill: dbo-docs -->
```yaml
name: dbo-docs
applies-when: >-
  Adding, moving or editing a page under docs/ or site/pages/, or a link
  into either.
reference: docs/arc42-002-constraints/working-rules/documentation.md
```
**Rules**
- MUST write a page for where it is stored: relative links to siblings,
  `README.md` as a section index. The assembly repairs the frame for pages
  it collects.
- MUST NOT link relatively from a published page into `docs/tasks/` or
  `docs/plans/`; use the absolute repository URL.
- MUST give a `why-*.md` a `why:` rank and a pattern page a `pattern:` rank
  in its front matter.
- MUST reference behaviour by REQ code or by document, never by a `§`
  number.
- MUST update the docs index when a document is added, moved or removed.
- MUST run `./gradlew site` after editing and fix what `--strict` reports.
<!-- /skill -->
