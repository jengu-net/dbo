---
name: dbo-shared-world-tests
description: Writing an integration test, deciding which world it runs in, moving a test onto a shared world, or deleting a test that builds a world of its own.
---

# dbo-shared-world-tests

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Writing an integration test, deciding which world it runs in, moving a test onto a shared world, or deleting a test that builds a world of its own.

## Rules

- MUST take the cheapest world that holds the proof, in this order: a guide
  step when a reader could do it with curl; a `SharedTenants` shape when the
  test needs the store's API, facade, feed or database; a private tenant on
  the shared runtime when no shape fits; a runtime of its own only for
  lifecycle, the container, tampering, a first boot, a claim about a
  deployment-wide sweep, a whole plane, or a deployment configured
  differently.
- MUST NOT take a world of its own merely to CALL a round. The scan loop
  calls `syncRound`, `shapesRound` and `scanOnce` continuously anyway, and a
  class that needs the effect on its own tenant runs one on the shared
  runtime. What cannot be shared is a claim about the round itself.
- MUST look for a shape that already exists before declaring one. Fifteen of
  the first twenty-three were used by a single class, because each was named
  after its mechanism rather than the part it plays. A shape named for a role
  — a hospital, an insurer, a zone — is one the next test can take.
- MUST call `SharedTenants.retire` in an `@AfterAll` for a shape only this
  class uses, and MUST NOT retire one several classes take: giving that back
  makes the next class rebuild what it came to reuse.
- MUST drive its OWN tenant's streams rather than the runtime's when it waits
  for a copy to arrive: `tenant.syncOnce()`, never `manager().syncRound()` in
  a loop. A round sweeps every tenant the runtime holds, so a poll that drives
  one pays for the whole world on every pass — which is how a suite that got
  faster by sharing tenants got slower again, by seventeen minutes.
- MUST give a class that builds its own runtime one of those five reasons in
  `config/worlds-ledger.txt`, re-recorded with `./gradlew
  :core:harness:worldsLedger`.
- MUST arrange every precondition with the tools a reader would use: a spec
  file, the tenant's token endpoint, a write through the face.
- MUST scope every assertion in a shared world to what the test itself
  made, and give anything claimed by identity the test's own name. Never
  count.
- MUST assert everything one action settles beside that action, including
  its audit entry, and assert that a shelled-out request ran before reading
  its answer.
- MUST NOT add a tenant to the guide world or a shape to the shared tenants
  for one test.
- MUST claim a promise where it is proven, re-record the catalogue, and
  confirm the new site is listed before deleting the test it came from.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/shared-world-tests.md`](../../../../docs/arc42-002-constraints/working-rules/shared-world-tests.md)
