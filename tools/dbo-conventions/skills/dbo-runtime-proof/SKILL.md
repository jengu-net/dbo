---
name: dbo-runtime-proof
description: Changing anything that has to survive being loaded in the OSGi container: a bundle's dependencies or its hand-written Import-Package, a new reference to a sibling package, a module added to the runtime module list, a service registration, the logging provider, or anything reached only on first use. Also whenever a change is about to be reported as working on the strength of a successful build.
---

# dbo-runtime-proof

> **Generated from the constraints documents — do not edit.** Change the
> skill-block in the source document and run `./gradlew generateSkills`.

**Apply when:** Changing anything that has to survive being loaded in the OSGi container: a bundle's dependencies or its hand-written Import-Package, a new reference to a sibling package, a module added to the runtime module list, a service registration, the logging provider, or anything reached only on first use. Also whenever a change is about to be reported as working on the strength of a successful build.

## Rules

- MUST prove a change by exercising it — validate a resource, convert one,
  ingest a CodeSystem, boot the container — and MUST NOT report a change as
  working on the strength of compilation, resolution or a green unit test.
- MUST assume a fat bundle's `Import-Package` is hand-written: a newly
  referenced sibling package resolves at build time and throws
  `NoClassDefFoundError` on first use. Add the package when adding the
  reference.
- MUST keep both in-JVM containers installing what the distribution installs.
  When one of them fails after a change, it is reporting the truth about the
  distribution; MUST NOT adjust the container test to make it pass.
- MUST give any test that loads the validator a 2g heap. It loads the core
  package eagerly and dies as an error with a null message, several frames
  above an `OutOfMemoryError` that is never printed.
- MUST NOT add a dependency to the dependency-free core module, or anything
  beyond the JDBC driver to the storage module, without a reason that
  survives being read aloud.

---

Where this is stated and argued: [`docs/arc42-002-constraints/working-rules.md#proving-a-change-which-compiling-does-not-do`](../../../../docs/arc42-002-constraints/working-rules.md#proving-a-change-which-compiling-does-not-do)
