---
name: dbo-comments
description: Writing or reviewing a comment, a Javadoc block, a test display name, a commit message, or prose in the specification tree.
---

# dbo-comments

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing or reviewing a comment, a Javadoc block, a test display name, a commit message, or prose in the specification tree.

## Rules

- MUST explain the constraint and only the constraint.
- MUST NOT cite an issue number or a decision record in the source or the
  specification tree. A `TODO` or `FIXME` MAY name the issue holding the
  work, and `docs/tasks/` is exempt.
- MUST write documentation as the current state. The journey belongs in the
  commit message.
- MUST NOT name a consumer of this store or a sibling repository.
- MUST state the thing without a contrast. Name an alternative only when a
  reader would plausibly reach for it, in one clause; keep history and the
  alternatives considered for the decision record and the commit message.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/comments.md`](../../../../docs/arc42-002-constraints/working-rules/comments.md)
