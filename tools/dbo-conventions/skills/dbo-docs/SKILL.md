---
name: dbo-docs
description: Adding, moving or editing a page under docs/, guide/ or site/pages/, a link into any of them, or deciding where a piece of documentation goes.
---

# dbo-docs

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Adding, moving or editing a page under docs/, guide/ or site/pages/, a link into any of them, or deciding where a piece of documentation goes.

## Rules

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

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/documentation.md`](../../../../docs/arc42-002-constraints/working-rules/documentation.md)
