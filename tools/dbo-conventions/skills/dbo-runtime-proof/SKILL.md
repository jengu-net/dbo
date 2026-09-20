---
name: dbo-runtime-proof
description: Changing a bundle's dependencies or Import-Package policy, adding a module to the runtime, registering a service, touching the logging provider, or reporting a change as working.
---

# dbo-runtime-proof

> **Generated from its source document — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Changing a bundle's dependencies or Import-Package policy, adding a module to the runtime, registering a service, touching the logging provider, or reporting a change as working.

## Rules

- MUST prove a change by exercising it: validate a resource, convert one,
  ingest a CodeSystem, boot the container. A build, a resolution or a green
  unit test is not proof.
- MUST let bnd compute `Import-Package` and hand-write only policy.
  `dbo-fhir-stack` is the one closed list.
- MUST add a new module to all three hand-written lists in the commit that
  first imports it: the runtime module list, the harness jar properties, and
  the container test's install list.
- MUST NOT adjust a container test to make it pass. When one fails after a
  change it is reporting the distribution.
- MUST give any new test task that loads the validator a 2g heap.
- MUST NOT add a dependency to `dbo-core`, or anything beyond the JDBC driver
  to `dbo-postgres`, without a reason that survives being read aloud.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules/runtime-proof.md`](../../../../docs/arc42-002-constraints/working-rules/runtime-proof.md)
