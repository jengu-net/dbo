---
name: dbo-recorded-projections
description: Changing a promise constant or citation, a public or protected signature in an exported package, a skill-block or marked region in a constraints document, or a diagram source; or when a build says a projection disagrees with its source.
---

# dbo-recorded-projections

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Changing a promise constant or citation, a public or protected signature in an exported package, a skill-block or marked region in a constraints document, or a diagram source; or when a build says a projection disagrees with its source.

## Rules

- MUST re-record every generated artefact a change makes stale, in the same
  change: `promiseProjection` for the catalogue, `apiLedger` for the ledger,
  `generateSkills` for the skills and trap section, `siteDiagrams` for the
  SVGs.
- MUST treat adding a constant to an exported enum, or a component to an
  exported record, as an API change.
- MUST read the ledger's report. What is GONE stops anything compiled
  against it from linking.
- MUST NOT hand-edit a generated artefact.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/recorded-projections.md`](../../../../docs/arc42-002-constraints/working-rules/recorded-projections.md)
