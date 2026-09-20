---
name: dbo-tests
description: Writing or naming a test, or writing one to prove a fix.
---

# dbo-tests

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing or naming a test, or writing one to prove a fix.

## Rules

- MUST name a test as a sentence about the behaviour it proves.
- MUST run the negative for a test written to prove a fix: break the thing
  and watch the test go red.
- MUST NOT accept a pass from a wait that outlives its condition, or from an
  assertion on text the answer contains regardless.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/tests.md`](../../../../docs/arc42-002-constraints/working-rules/tests.md)
