---
name: dbo-diagrams
description: Creating, editing or reviewing a .lini source, a .desc, or a diagram SVG, or upgrading lini.
---

# dbo-diagrams

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Creating, editing or reviewing a .lini source, a .desc, or a diagram SVG, or upgrading lini.

## Rules

- MUST read the Lini skill for the pinned version before writing a source:
  `SKILL.md` in the crate's source tree, at the `LINI_VERSION` named in
  `.github/workflows/site.yml`.
- MUST put the source in the concept's `diagrams/` directory with a `.desc`
  beside it: a title line, then prose.
- MUST use the site's colour variables and six-digit hex, name the font
  families the site serves, and never embed a font.
- MUST run `./gradlew siteDiagrams`, look at the render, and commit the SVG
  with the source.
- MUST NOT edit an SVG by hand.
- MUST treat a compiler upgrade as one commit that rewrites every diagram
  and the font file.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/diagrams.md`](../../../../docs/arc42-002-constraints/working-rules/diagrams.md)
