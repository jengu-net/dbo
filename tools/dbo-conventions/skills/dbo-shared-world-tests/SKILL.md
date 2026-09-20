---
name: dbo-shared-world-tests
description: Writing an integration test, deciding where one belongs, moving a promise onto a shared-world step, or deleting a test that builds a world of its own.
---

# dbo-shared-world-tests

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing an integration test, deciding where one belongs, moving a promise onto a shared-world step, or deleting a test that builds a world of its own.

## Rules

- MUST put a test in the shared world when what it proves is reachable over
  HTTP, and in a world of its own when it reaches for the store's API, the
  database, the server log or the container.
- MUST arrange every precondition with the tools a reader would use: a spec
  file, the tenant's token endpoint, a write through the face.
- MUST assert everything one action settles beside that action, including
  its audit entry.
- MUST read the state a step depends on, put a step in the story that
  creates what it reads, and assert that a shelled-out request ran before
  reading its answer.
- MUST NOT grow the shared world to fit one test.
- MUST claim a promise where it is proven, re-record the catalogue, and
  confirm the new site is listed before deleting the test it came from.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/shared-world-tests.md`](../../../../docs/arc42-002-constraints/working-rules/shared-world-tests.md)
