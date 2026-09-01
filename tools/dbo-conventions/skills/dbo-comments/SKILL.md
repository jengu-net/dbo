---
name: dbo-comments
description: Writing or reviewing a comment, a Javadoc block, a test display name, a commit message, or any prose inside this repository's source or its specification tree.
---

# dbo-comments

> **Generated from the constraints documents — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing or reviewing a comment, a Javadoc block, a test display name, a commit message, or any prose inside this repository's source or its specification tree.

## Rules

- MUST explain the constraint and only the constraint. A sentence that needs
  a ticket or a decision record to make sense has not yet said what it means.
- MUST NOT cite an issue number or a decision record anywhere in the source
  or the specification tree. Both are moment-bound, both are superseded, and
  a reader here frequently cannot open either.
- MAY name the issue holding the work in a `TODO` or a `FIXME`, and SHOULD
  when one is filed, because a reader wanting to know what became of the gap
  has nowhere else to look.
- MUST write documentation as a description of the current state. The journey
  belongs in the commit message that made it.
- MUST NOT name a consumer of this store, or any sibling repository, in source
  or specification prose — this store is neutral, and a domain is a face over
  it rather than a fact about it.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#what-a-comment-is-for`](../../../../docs/arc42-002-constraints/working-rules.md#what-a-comment-is-for)
