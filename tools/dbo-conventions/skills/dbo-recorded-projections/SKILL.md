---
name: dbo-recorded-projections
description: Changing anything a generated artefact is derived from: a promise constant or a Proving citation, any public or protected signature in a package a bundle exports — including adding a constant to an exported enum or a component to an exported record — or a skill-block or marked prose region in a constraints document. Also whenever a build fails saying a projection, ledger or catalogue disagrees with its source.
---

# dbo-recorded-projections

> **Generated from the constraints documents — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Changing anything a generated artefact is derived from: a promise constant or a Proving citation, any public or protected signature in a package a bundle exports — including adding a constant to an exported enum or a component to an exported record — or a skill-block or marked prose region in a constraints document. Also whenever a build fails saying a projection, ledger or catalogue disagrees with its source.

## Rules

- MUST re-record every generated artefact its change makes stale, in the SAME
  change: `./gradlew :core:harness:promiseProjection` for the requirement
  catalogue, `./gradlew :core:harness:apiLedger` for the exported-API ledger,
  `./gradlew generateSkills` for the skills and the trap section.
- MUST treat adding a constant to an exported enum, or a component to an
  exported record, as an API change. Neither feels like one; both move the
  ledger, and the second removes a canonical constructor that callers compile
  against.
- MUST read the ledger's report rather than only re-recording it: what is GONE
  stops anything compiled against it from linking, and that is worth knowing
  before it is committed rather than after a consumer finds out.
- MUST NOT hand-edit a generated artefact. Every one of them says so in its
  own header, and the ratchet refuses it.
- MUST NOT leave the re-record to a follow-up commit. The ratchet catches it
  either way; a follow-up leaves a red commit in the history for whoever
  bisects through it later.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#re-recording-what-is-generated-from-what-you-changed`](../../../../docs/arc42-002-constraints/working-rules.md#re-recording-what-is-generated-from-what-you-changed)
