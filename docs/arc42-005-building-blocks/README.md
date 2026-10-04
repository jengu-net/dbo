# Building blocks — layering

What the code is divided into, why the divisions fall where they do, and which
divisions the build enforces. The behaviour behind each name is in
[the crosscutting concepts](../README.md); this page is the map, not the
territory.

## The four layers, and what each may depend on

Every module is in one of four, and the rule is what a layer may depend on
rather than what it contains.

| | |
|---|---|
| `core/` | the store. No heavyweight framework — no Spring, no Micronaut, no application container. Names nothing outside `core/` and `promise/`. |
| `promise/` | requirements as code: promises declared once and cited where they are kept. The same rule, for the same reason — production code cites it. |
| `assembly/` | glue, binding the store into a development framework somebody already uses. **May** depend on a framework; depends on `core/`. Nothing in `core/` may depend on it. |
| `samples/` | applications built on an assembly, to be read and run. Depends on whatever an application depends on. |

**The direction is the whole rule.** `core/` never reaches up. An assembly is
optional — the store is complete without one, and a framework-free application
is not a lesser path but the layer every assembly is built on. `core/dbo-embedded`
is that layer's edge: boot a framework, install a bundle set, share one class
space with the host, and no framework anywhere in it.

**So a module belongs to `assembly/` by what it imports, not by who calls it.**
One that names no framework is `core/` even when an assembly is its only
caller today — otherwise the first binding to arrive quietly claims the
shared host, and the second one inherits a dependency on the first one's
framework.

**And that constraint is what makes the assemblies possible**, which is worth
saying the right way round. The store takes no framework (R2), so it can be
hosted in any of them: `assembly/spring-boot-*` exists because most developers
know Spring, and a Micronaut assembly is a module rather than a port for
exactly the same reason. A store that had chosen a framework internally could
not have been glued to a second one at all.

Which module names which is the module map, below, generated from the build
rather than asserted here: nothing under `core/` or `promise/` names an
`assembly/` module in it.

## The shapes a deployment runs

Three, and the difference between them is not packaging taste.

--8<-- "assets/diagrams/what-a-deployment-runs.svg"

<p class="diagram-caption">Three shapes, and a fourth that is deliberately
not shipped. What separates them is privilege: everything beside the runtime
holds less than the runtime does.</p>

**One OSGi container** (Felix) holds the serving runtime. Everything that
touches a tenant's data is a bundle in it, because a tenant arrives and leaves
at runtime and its services are registered and retracted with it — a
lifecycle a flat classpath cannot express. `core/dbo-server` is the shipped
distribution: no source of its own, just the assembly and its launcher.

**Plain jars beside it**, each its own process with its own credentials and no
bundle: `core/dbo-operator` (provisions tenants against Kubernetes),
`core/dbo-fleet` (reads a deployment from outside and acts on it), and
`core/dbo-verify` (checks a sealed archive on somebody's laptop with no
network and no account). They are separate deliberately — the operator is
less privileged than the tenant, the fleet reader holds no store at all, and
the verifier must run where nothing else of ours does.

**A console that is not shipped.** `karaf/` assembles a development shell;
`karaf/commands` reads runs and the step catalogue from inside a running
node. It binds tenant-plane services from the registry, which a shipped
console must not do, and it is kept out of the serving distribution for
exactly that reason. See [the console proposal](../arc42-011-risks-and-technical-debt/014-the-karaf-console/README.md).

## The layering, as the build enforces it

Dependencies point one way. `dbo-core` names nothing else, and
`dbo-postgres` names only the JDBC driver — those two are load-bearing
constraints, not observations, and adding a library to either needs a reason
that survives being read aloud.

--8<-- "assets/diagrams/the-layers.svg"

<p class="diagram-caption">Dependencies point down and never up. The engine
at the bottom names nothing above it, so a face, a door or a composition
root can be replaced without it learning anything.</p>

The edges themselves are [`config/module-map.txt`](https://github.com/jengu-net/dbo/blob/main/config/module-map.txt),
generated from the build by `./gradlew moduleMap` and refused by the build
when the committed file disagrees with the project. A picture of forty edges
would be a second source of truth and a worse one; this one draws the bands,
and the map answers which module names which.

- **`dbo-core`** — the version-agnostic object engine: envelope model,
  identifiers, references, the outbox, the search-criteria SPI, the
  `ObjectStore` contract. Plain Java, zero framework, zero FHIR.
- **`dbo-postgres`** — that contract over JDBC on virtual threads, with
  Liquibase behind advisory session locks. The JDBC driver is its only
  dependency.
- **`dbo-terminology`** — concept-per-row terminology and its operations. It
  names the engine and nothing else, which the module map is what to believe
  about.
- **`dbo-fhir-stack`** — the HL7 core and HAPI, embedded once and exported, so
  a container pays for the engine once rather than once per version. It has
  **no source**: it is a repackaging, and the one bundle whose imports are a
  hand-written closed list rather than computed.
- **`dbo-fhir-element`** — the shared FHIR implementation every version is
  served through, and the element-model face in its own right. At ~7,000 lines
  it is the largest module, and both version personalities delegate to it.
- **`dbo-fhir-r4`, `dbo-fhir-r5`** — the version personalities, each binding
  the shared implementation to one release's model classes.
- **`dbo-rest`** — the FHIR HTTP surface, plus the contracts the surfaces
  behind it implement (`RequestAuthenticator`, `AuditSurface`, `WorkSurface`,
  `AuditProjection`).
- **`dbo-auth`** — the per-tenant OIDC authority, JDK crypto only.
  **`dbo-scim`** — staff provisioning served inside the membrane.
- **`dbo-pdi`** — personal-data isolation: identifying elements encrypted
  inside the payload, and the vault that holds the keys.
- **`dbo-policy`** — audit and write discipline as tenant policy.
- **`dbo-work`** — runs as records, step declarations, the claim, contact
  with the workers of a step, and the initiator that asks a tenant for a run.
  **`dbo-runner`** — the participation lane, the embeddable step runner, and
  the transport SPI: the dispatch every transport hands its verbs to, and the
  carrier the stream protocol rides.
  **`dbo-stream`** — the stream protocol and its carrier over the store's own
  substrate: the door a tenant mounts and the lane a participant's container
  holds, which it builds from what the container was told.
  **`dbo-sync`** — declared content dependencies and the replication lane
  between two appliances of one tenant.
- **`dbo-subscriptions`** — durable subscription delivery over the change feed.
- **`dbo-maintenance`** — sealed archives: backup, restore, export, import.
- **`dbo-tenant`** — the composition root. It brings a tenant up, mounts every
  door, and registers the service set; `dbo-tenant-k8s` adds the in-cluster
  provisioning seam.
- **`promise` / `dbo-promises`** — requirements as code: the promise framework
  and this store's catalogue of them, which the REQ tables are generated from.
  See [promise](../arc42-002-constraints/promise.md).
- **`dbo-telemetry`** and **`dbo-telemetry-otlp`** — the measurement seam and
  its one exporter, both installed everywhere and idle without an endpoint,
  because a code path first run in production is the last place to first run
  it.
- **`dbo-logging`** — the single slf4j binding for the runtime, with SPI-Fly
  mediating the ServiceLoader lookup.

Not in any deployment: `core/harness` and `core/conformance` (the test
suites), `core/dbo-test-model` (a face for tests that must not be FHIR), and
`bench/` (throughput measurement, its comparison harness a separate Gradle
build of its own).

## Level 2 — a tenant's store, as bring-up assembles it

One chain per tenant, built once at bring-up and shared by everything wired
for that tenant. Reading it outward-in tells you the order the guards run.

```
FhirStoreFacade                 what a face offers the world
  └─ PolicyObjectStore          audit and write discipline — OUTERMOST, so
       │                        audit sees interactions after authorisation
       │                        and never content
       └─ PdiObjectStore        the §14 membrane — only when the tenant
            │                   declares personal-data isolation
            └─ PgObjectStore    the storage engine
```

The ordering is a decision, not an accident, and the comment at the wiring
says so: audit must not see payloads the membrane would have hidden. Two
stores stand outside this chain — the authority keeps its own, so minting a
token does not write an audit entry, and each zone keeps one for its
declarations.

Layers are added by wrapping, never by inheritance, and each decorator
forwards what it has no opinion about. `ObjectStore` deliberately declares no
default methods: a default that a wrapper could inherit would let it discard
an argument silently, and the failure would be toward permissive.

## The one block this store did not write

`dev.dbos:transact` — DBOS's Java library — is the only third-party runtime
of any size inside the store's own layer, and it is there for the part that is
hardest to get right and least interesting to own: making a piece of work
survive the process that was doing it. It keeps workflow state in Postgres
tables of its own, so a workflow half done when a process died is resumed by
whatever picks it up next and a step already taken is not taken again.

**It is a library, not a service.** No deployment of this store runs a DBOS
server. What runs is a Java object in a process, pointed at a database, and the
durability is the table it writes.

**It is embedded privately, in two bundles.** `dbo-subscriptions` and
`dbo-stream` each carry DBOS and its whole dependency closure in `lib/` as
non-exported packages, so nothing Spring-adjacent reaches the container's
wiring and each bundle's exports stay DBO-owned types. That packaging is
[the first decision record](../arc42-009-architecture-decisions/001-dbos-runs-inside-a-bundle.md),
adopted after every scenario was proven on Felix against a real Postgres —
including the one that matters most here, that `DBOS` is an instantiable class
rather than a singleton, so several run in one JVM over separate databases.

**One package is deliberately shared with the container**, and it is the
exception that proves what private embedding costs. `org.postgresql` is
imported from the driver bundle, because DBOS unwraps a pooled connection to
`org.postgresql.PGConnection` to reach `LISTEN`: a private copy makes the class
it asks for and the class the connection implements two classes with one name,
the unwrap can never succeed, and the listener degrades to polling for ever.
What has to agree is the interface two parties pass an object across. The rest
of the driver stays private.

Where it sits in the layering is ordinary — both bundles are `core/`, and
nothing above them knows it is there. What the store asks of it, what it
deliberately does not ask of it, and the three places it is used are
[the durable layer](../arc42-008-crosscutting/processes-and-work/the-durable-layer.md).

## Where the rules about all this are written

- **Imports are computed.** Every bundle with source lets bnd derive
  `Import-Package` from bytecode; the hand-written part is a filter. The one
  exception is `dbo-fhir-stack`, which has no source and keeps a closed list
  that a test walks every embedded class to check.
- **A new module another module imports must join the bundle set in the same
  commit**, in all three places fed from one list. bnd resolves it on the
  classpath and the container dies on first use otherwise.
- **Green builds prove little here.** `EmbeddedContainerIT`, `TenantOsgiIT`
  and `ServerDistIT` are the ratchets, and both in-JVM containers must install
  what the distribution installs.

These are stated as rules, with the failures behind them, in
[working rules](../arc42-002-constraints/working-rules/README.md) and in the
repository's `CLAUDE.md`.
