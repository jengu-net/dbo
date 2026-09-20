---
name: dbo-reachability
description: Finishing a toolset, service, surface, lane, registered type or capability, or reviewing work reported complete because its tests pass.
---

# dbo-reachability

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Finishing a toolset, service, surface, lane, registered type or capability, or reviewing work reported complete because its tests pass.

## Rules

- MUST ask, at the end of the work, who constructs this outside a test. A
  `new X(` matching nothing in production sources is the finding.
- MUST ask where its state lives, and confirm the type it writes is
  registered for every tenant that needs it.
- MUST ask what the container hands each collaborator that a test hands
  itself: the catalogue, registry or credential a check resolves through.
- MUST NOT report a toolset delivered on the strength of its own tests.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/reachability.md`](../../../../docs/arc42-002-constraints/working-rules/reachability.md)
