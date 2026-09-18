---
name: dbo-shared-world-tests
description: Writing a new integration test, deciding where one belongs, moving a promise onto a shared-world step, or considering deleting an integration test that builds a world of its own. Triggers on adding a test that needs a tenant, a credential or a record, and on any change that would add a world to the build.
---

# dbo-shared-world-tests

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing a new integration test, deciding where one belongs, moving a promise onto a shared-world step, or considering deleting an integration test that builds a world of its own. Triggers on adding a test that needs a tenant, a credential or a record, and on any change that would add a world to the build.

## Rules

- MUST put a test in the shared world when what it proves is reachable over
  HTTP, and keep it in a world of its own when it reaches for the store's own
  API, the database, the server log or the container.
- MUST arrange every precondition with the tools a reader would use — a spec
  file, the tenant's token endpoint, a write through the face — and never by
  inserting state behind the surface.
- MUST assert everything one action settles beside that action, including the
  audit entry it emits, rather than in a later pass that goes looking.
- MUST read the state a step depends on rather than counting the writes above
  it, and put a step in the story that creates what it reads.
- MUST assert that a shelled-out request actually ran before reading anything
  into its answer.
- MUST NOT grow the shared world to fit one test; that cost is paid by every
  run of every test in it.
- MUST prove a promise where the assertion is made, run the catalogue
  projection, and confirm the new site is listed before deleting the test the
  promise came from.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#testing-against-the-shared-world`](../../../../docs/arc42-002-constraints/working-rules.md#testing-against-the-shared-world)
