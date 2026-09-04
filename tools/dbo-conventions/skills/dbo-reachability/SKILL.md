---
name: dbo-reachability
description: Finishing any new toolset, service, surface, lane, registered type or capability, and before calling such work done. Triggers on a type that only a test constructs, on a new object type that something writes, and on reviewing work that is reported as complete because its tests pass.
---

# dbo-reachability

> **Generated from the constraints documents — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Finishing any new toolset, service, surface, lane, registered type or capability, and before calling such work done. Triggers on a type that only a test constructs, on a new object type that something writes, and on reviewing work that is reported as complete because its tests pass.

## Rules

- MUST ask who constructs this outside a test, and MUST treat a constructor
  call that matches nothing in production sources as the finding itself —
  that is the whole signal, and nothing fails to announce it.
- MUST ask where the thing's own state lives, and confirm the type it writes
  is registered for the tenants that need it. A surface can be mounted,
  correct and proven while the type behind it is registered for nobody.
- MUST NOT report a toolset as delivered on the strength of its own tests
  passing; the test proves the thing works, never that anything can reach it.
- MUST ask what the CONTAINER hands a collaborator that a test hands itself:
  a rule enforced at a primitive is only as good as the catalogue, registry or
  credential every caller passes it, and a test that supplies one by hand
  proves the rule and not the wiring.
- MUST ask both questions deliberately at the end of the work, because
  nothing in the build asks them and nothing fails when the answer is wrong.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#reachability-which-tests-do-not-check`](../../../../docs/arc42-002-constraints/working-rules.md#reachability-which-tests-do-not-check)
