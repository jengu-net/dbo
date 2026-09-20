# Proving a change

A change is proven by being exercised. Compilation, resolution and a green
unit test each prove a narrower thing, and the characteristic defect here
passes all three: it compiles, resolves, publishes and dies on first use. It
arrives through a hand-written `Import-Package`, through a jar a bundle
excludes and reaches lazily, and through a logging binding that resolves to
silence.

## What the build enforces

Every bundle with source lets bnd compute `Import-Package` from bytecode.
What is hand-written is policy: which JDK surfaces may be absent, and a
trailing `!*` that drops what a private stack reaches for and the container
does not provide. `dbo-fhir-stack` has no source, so it keeps a closed list,
and `TheStackImportsWhatItReachesForTest` walks every class it embeds.

`EmbeddedContainerIT`, `TenantOsgiIT` and `ServerDistIT` install what the
distribution installs. They fail when a package resolves and dies on first
use.

The R5 validator loads the FHIR core package eagerly. On a default heap it
dies as `HAPI-2330` with a null message, three frames above an
`OutOfMemoryError` nobody sees. Test tasks set `maxHeapSize = "2g"`.

## What only a rule enforces

A new module reaches the container through three hand-written lists: the
root build's runtime module list, the harness's jar properties, and the
container test's ordered install list. bnd computes the import, so a module
missing from any of them resolves on the classpath and dies in the framework.

`dbo-core` has no dependencies and `dbo-postgres` only the JDBC driver. The
build does not check this.

<!-- skill: dbo-runtime-proof -->
```yaml
name: dbo-runtime-proof
applies-when: >-
  Changing a bundle's dependencies or Import-Package policy, adding a module
  to the runtime, registering a service, touching the logging provider, or
  reporting a change as working.
reference: docs/arc42-002-constraints/working-rules/runtime-proof.md
```
**Rules**
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
<!-- /skill -->
