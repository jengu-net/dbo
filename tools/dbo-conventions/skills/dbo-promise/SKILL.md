---
name: dbo-promise
description: Adding or changing any behaviour of the store, fixing a defect, or editing the promise catalogue, a promise constant, a Proving citation, or the requirement catalogue document. Triggers on any work that would add a requirement or make an existing one true.
---

# dbo-promise

> **Generated from the constraints documents — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Adding or changing any behaviour of the store, fixing a defect, or editing the promise catalogue, a promise constant, a Proving citation, or the requirement catalogue document. Triggers on any work that would add a requirement or make an existing one true.

## Rules

- MUST declare new behaviour as a promise constant and cite it from the test
  that proves it. Adding behaviour means claiming or adding a promise; fixing
  a defect means adding the test that would have caught it.
- MUST run the catalogue projection after changing any promise constant or
  citation, in the same change. Declaring without projecting compiles cleanly
  and reddens the build in a test about the projection rather than about the
  work.
- MUST never hand-edit the generated block of the requirement catalogue; it is
  a projection, and the projection is refused when it disagrees with the
  model.
- MUST name a test after the behaviour it proves, as a sentence.
- MUST believe the implementation status page over any impression the code
  gives about what is built.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#claiming-a-behaviour`](../../../../docs/arc42-002-constraints/working-rules.md#claiming-a-behaviour)
