---
name: dbo-promise
description: Adding or changing behaviour of the store, fixing a defect, or editing a promise constant, a Proving citation, or the requirement catalogue.
---

# dbo-promise

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Adding or changing behaviour of the store, fixing a defect, or editing a promise constant, a Proving citation, or the requirement catalogue.

## Rules

- MUST declare new behaviour as a promise constant and cite it from the test
  that proves it.
- MUST run the catalogue projection in the same change as any change to a
  constant or a citation.
- MUST NOT hand-edit the generated block of the requirement catalogue.
- MUST read what is proven from the requirement catalogue, and what is built
  from the implementation status page. The status page is believed over the
  impression the code gives; its test counts are typed by hand.

---

Where this is stated and argued: [`docs/arc42-002-constraints/promise.md#claiming-a-behaviour`](../../../../docs/arc42-002-constraints/promise.md#claiming-a-behaviour)
