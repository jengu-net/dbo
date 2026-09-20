# Diagrams

A figure is a Lini source, a description, and a committed SVG.

- The source `<name>.lini` lives in the `diagrams/` directory of the concept
  whose figure it is. There is no second place.
- `<name>.desc` beside it holds the accessible name and description: the
  first line is the title, the rest is prose. Lini cannot carry either, and
  the build refuses a figure without both.
- `./gradlew siteDiagrams` compiles every source to
  `site/assets/diagrams/<name>.svg` and injects the description. The SVG is
  committed, so the site build needs no compiler. `siteDiagramsCheck` fails
  when a committed SVG differs from its source or its description.

## The compiler

Lini is pinned by `LINI_VERSION` in `.github/workflows/site.yml`. A compiler
upgrade rewrites every diagram and the font file, so it is a commit. The
pinned version installs with `cargo install lini --locked --version <v>`;
`-Plini=/path/to/lini` points the build at a binary off PATH.
`siteDiagramFont` rewrites `site/assets/lini-font.css` after an upgrade.

The language is documented by the crate itself: `SKILL.md` at the root of
its source tree, which cargo keeps under `~/.cargo/registry/src/*/lini-<v>/`
and GitHub serves at the version's tag. The language changes between
versions, so the pinned version's copy is the one to read.

## What is particular here

- Colours are variables: `--ink`, `--body`, `--muted`, `--faint`, `--panel`,
  `--line`, `--accent`, `--bg`, `--seal`, `--seal-soft`, `--accent-soft` and
  `--stroke`. `site/assets/dbo.css` maps each onto the site palette in both
  schemes, so the values in a source are only what the figure looks like
  opened away from the site. Copy the stylesheet head of an existing source.
- Every hex colour is written at six digits. A three-digit hex is
  indistinguishable from an issue reference to the branding ratchet, which
  is what enforces this: the check reads a three-digit pair as an issue
  number and refuses it in any file, this sentence included, which is why
  the shape is described rather than shown.
- Each source carries its own stylesheet head, copied. Lini has no include,
  and a file is one stylesheet block, so a shared head cannot be prepended
  without splicing the source.
- Text is `"Google Sans"` and code is `"Google Sans Code"`, served once by
  `lini-font.css`. `--embed-font` is never used; it writes the faces into
  every figure.
- A bare string is always centred in its parent. Text that must sit left is
  wrapped in a block, and the container aligns to start.
- Comments and layout in the source are for the reader. The assembly
  collapses the SVG to one line before inclusion.

<!-- skill: dbo-diagrams -->
```yaml
name: dbo-diagrams
applies-when: >-
  Creating, editing or reviewing a .lini source, a .desc, or a diagram SVG,
  or upgrading lini.
reference: docs/arc42-002-constraints/working-rules/diagrams.md
```
**Rules**
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
<!-- /skill -->
