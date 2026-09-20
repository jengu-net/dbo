# Prose

A comment explains the constraint, and only the constraint. A commit message
explains why. Documentation describes the current state; the journey belongs
in the commit message that made it.

## What a sentence may lean on

An issue and a decision record are moments. Both are superseded, both die
when a tracker moves, and a reader here often cannot open either. A sentence
that needs one to make sense has not yet said what it means.
`.github/scripts/check-branding.sh` refuses both anywhere in the source or
the specification tree, with two exceptions: `docs/tasks/`, whose documents
carry a topic between its issues and are deleted when they close; and a
`TODO` or `FIXME`, which is a statement about outstanding work and may name
the issue holding it.

A consumer of the store, or a sibling repository, is never named. The store
is neutral; a domain is a face over it.

## How much to say

State the thing. An alternative is named only when a reader would plausibly
reach for it, and then in one clause. History, comparison and the
alternatives considered belong in the decision record and the commit
message, and nowhere else in the tree.

<!-- skill: dbo-comments -->
```yaml
name: dbo-comments
applies-when: >-
  Writing or reviewing a comment, a Javadoc block, a test display name, a
  commit message, or prose in the specification tree.
reference: docs/arc42-002-constraints/working-rules/comments.md
```
**Rules**
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
<!-- /skill -->
